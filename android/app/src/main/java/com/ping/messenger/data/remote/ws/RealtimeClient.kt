package com.ping.messenger.data.remote.ws

import android.util.Log
import com.ping.messenger.core.network.NetworkMonitor
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class RealtimeStatus { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * The realtime connection.
 *
 * Responsibilities kept deliberately narrow: own one WebSocket, expose inbound events as a
 * [SharedFlow], and stay connected. Interpreting events is the repositories' job.
 *
 * Reconnection uses full-jitter exponential backoff capped at 60 s. Jitter matters more than it
 * looks: without it, a server restart brings every client back in lockstep and the thundering
 * herd knocks it over again.
 *
 * An application-level heartbeat runs alongside OkHttp's ping frames because a TCP connection
 * behind a NAT can stay "open" long after it stopped carrying traffic; only an unanswered
 * application ping reliably detects that.
 */
@Singleton
class RealtimeClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenStore: TokenStore,
    private val networkMonitor: NetworkMonitor,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(RealtimeStatus.DISCONNECTED)
    val status: StateFlow<RealtimeStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var attempt = 0
    private var intentionallyClosed = false

    /** Queued while offline, replayed on connect so subscriptions survive a reconnect. */
    private val subscriptions = mutableSetOf<String>()

    @Volatile private var baseUrl: String = ""

    fun connect(wsUrl: String) {
        baseUrl = wsUrl
        intentionallyClosed = false
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { connectLoop() }
    }

    fun disconnect() {
        intentionallyClosed = true
        heartbeatJob?.cancel()
        connectJob?.cancel()
        webSocket?.close(NORMAL_CLOSURE, "client disconnect")
        webSocket = null
        _status.value = RealtimeStatus.DISCONNECTED
    }

    fun send(event: RealtimeEvent): Boolean {
        val socket = webSocket ?: return false
        return runCatching {
            socket.send(json.encodeToString(RealtimeEvent.serializer(), event))
        }.getOrDefault(false)
    }

    fun subscribe(conversationIds: Collection<String>) {
        subscriptions += conversationIds
        send(RealtimeEvent.Subscribe(subscriptions.toList()))
    }

    private suspend fun connectLoop() {
        while (scope.isActive && !intentionallyClosed) {
            if (!networkMonitor.isOnline || !tokenStore.isSignedIn) {
                _status.value = RealtimeStatus.DISCONNECTED
                delay(3_000)
                continue
            }

            _status.value = RealtimeStatus.CONNECTING
            val closed = openSocket()

            if (intentionallyClosed) break

            // Reconnect with full-jitter backoff: sleep for a random slice of the window
            // rather than the whole window, so clients spread out instead of syncing up.
            attempt = min(attempt + 1, MAX_BACKOFF_EXPONENT)
            val window = (BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong().coerceAtMost(MAX_BACKOFF_MS)
            val wait = (Math.random() * window).toLong().coerceAtLeast(500)
            Log.d(TAG, "reconnecting in ${wait}ms (closed=$closed, attempt=$attempt)")
            _status.value = RealtimeStatus.DISCONNECTED
            delay(wait)
        }
        _status.value = RealtimeStatus.DISCONNECTED
    }

    /** Opens a socket and suspends until it closes. Returns the close reason for logging. */
    private suspend fun openSocket(): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val token = tokenStore.tokens.value?.accessToken
        if (token == null) {
            if (cont.isActive) cont.resume("no token") { _, _, _ -> }
            return@suspendCancellableCoroutine
        }

        val request = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $token")
            .build()

        var finished = false
        fun finish(reason: String) {
            if (finished) return
            finished = true
            heartbeatJob?.cancel()
            webSocket = null
            if (cont.isActive) cont.resume(reason) { _, _, _ -> }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "connected")
                webSocket = ws
                attempt = 0
                _status.value = RealtimeStatus.CONNECTED
                if (subscriptions.isNotEmpty()) {
                    ws.send(
                        json.encodeToString(
                            RealtimeEvent.serializer(),
                            RealtimeEvent.Subscribe(subscriptions.toList()),
                        ),
                    )
                }
                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val event = decode(text) ?: return
                _events.tryEmit(event)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _status.value = RealtimeStatus.DISCONNECTED
                finish("closed $code $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure: ${t.message}")
                _status.value = RealtimeStatus.DISCONNECTED
                finish("failure: ${t.message}")
            }
        }

        val socket = okHttpClient.newWebSocket(request, listener)
        cont.invokeOnCancellation {
            runCatching { socket.close(NORMAL_CLOSURE, "cancelled") }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!send(RealtimeEvent.Heartbeat)) break
            }
        }
    }

    /**
     * Decodes an inbound frame, degrading to [RealtimeEvent.Unknown] rather than throwing.
     * A malformed or unrecognised frame must never take the connection down.
     */
    private fun decode(text: String): RealtimeEvent? = try {
        json.decodeFromString(RealtimeEvent.serializer(), text)
    } catch (e: Exception) {
        Log.w(TAG, "undecodable frame: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "RealtimeClient"
        const val NORMAL_CLOSURE = 1000
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_EXPONENT = 7
        const val HEARTBEAT_INTERVAL_MS = 25_000L
    }
}
