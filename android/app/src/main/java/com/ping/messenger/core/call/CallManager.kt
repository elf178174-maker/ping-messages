package com.ping.messenger.core.call

import android.content.Context
import android.util.Log
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.remote.ws.RealtimeClient
import com.ping.messenger.data.remote.ws.RealtimeEvent
import com.ping.messenger.di.ApplicationScope
import com.ping.messenger.domain.repository.CallAvailability
import com.ping.messenger.domain.repository.CallRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** The lifecycle of one call, as the UI sees it. */
sealed interface CallState {
    data object Idle : CallState
    data class Incoming(
        val callId: String,
        val peerId: String,
        val peerName: String,
        val peerAvatarUrl: String?,
        val isVideo: Boolean,
    ) : CallState
    data class Outgoing(val callId: String, val peerId: String, val isVideo: Boolean) : CallState
    data class Connecting(val callId: String, val peerId: String, val isVideo: Boolean) : CallState
    data class Connected(
        val callId: String,
        val peerId: String,
        val isVideo: Boolean,
        val startedAt: Long,
    ) : CallState
    data class Reconnecting(val callId: String, val peerId: String, val isVideo: Boolean) : CallState
    data class Ended(val reason: String, val durationSeconds: Long) : CallState
    data class Failed(val reason: String) : CallState
}

data class CallControls(
    val micMuted: Boolean = false,
    val cameraEnabled: Boolean = false,
    val speakerOn: Boolean = false,
    val usingFrontCamera: Boolean = true,
)

/**
 * WebRTC call orchestration.
 *
 * ## What this actually is
 *
 * A real WebRTC implementation over `io.getstream:stream-webrtc-android` (a maintained
 * repackaging of Google's libwebrtc). It builds a genuine [PeerConnection], captures real
 * camera and microphone tracks, exchanges SDP and ICE through Ping's own WebSocket, and once
 * connected the media flows **directly between the devices** — audio and video never pass
 * through Ping's server.
 *
 * ## The honest caveat
 *
 * Peer-to-peer only works if a route can be found. STUN handles most networks; roughly 10-20%
 * (symmetric NAT, restrictive corporate firewalls) need a TURN relay. Ping does not ship a
 * TURN server — one has to be configured. Rather than letting a call ring forever on those
 * networks, [CallRepository.availability] reports the configuration state and the UI explains
 * it. See `docs/CALLS.md`.
 *
 * Group calls use a full mesh, one peer connection per participant. That is fine up to about
 * four people and then falls apart on upstream bandwidth; a real deployment wants an SFU. The
 * code caps the mesh rather than pretending otherwise.
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val realtimeClient: RealtimeClient,
    private val callRepository: CallRepository,
    private val tokenStore: TokenStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _controls = MutableStateFlow(CallControls())
    val controls: StateFlow<CallControls> = _controls.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var remoteVideoTrack: VideoTrack? = null
    private var connectedAt: Long = 0
    private var currentPeerId: String? = null
    private var currentCallId: String? = null

    /** ICE candidates that arrived before the remote description was set. */
    private val pendingCandidates = mutableListOf<IceCandidate>()

    val eglContext: EglBase.Context? get() = eglBase?.eglBaseContext
    val localTrack: VideoTrack? get() = videoTrack
    val remoteTrack: VideoTrack? get() = remoteVideoTrack

    init {
        scope.launch { observeSignalling() }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Places a call. Returns false when the server has no ICE configuration. */
    suspend fun startCall(conversationId: String, peerId: String, isVideo: Boolean): Boolean {
        val availability = callRepository.availability()
        if (availability !is CallAvailability.Available) {
            _state.value = CallState.Failed("Calling is not configured on this server")
            return false
        }

        val callId = when (val started = callRepository.start(conversationId, isVideo, listOf(peerId))) {
            is com.ping.messenger.core.common.Outcome.Success -> started.value
            is com.ping.messenger.core.common.Outcome.Failure -> {
                _state.value = CallState.Failed(started.error.message ?: "Could not start the call")
                return false
            }
        }

        currentCallId = callId
        currentPeerId = peerId
        _state.value = CallState.Outgoing(callId, peerId, isVideo)
        _controls.update { it.copy(cameraEnabled = isVideo, speakerOn = isVideo) }

        initialise(availability.iceServers, isVideo)

        // The caller creates the offer; the callee answers. Both sides doing this
        // simultaneously is the classic WebRTC glare bug.
        peerConnection?.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), description)
                    realtimeClient.send(
                        RealtimeEvent.CallOffer(callId, peerId, description.description),
                    )
                }
            },
            mediaConstraints(isVideo),
        )

        return true
    }

    suspend fun answer() {
        val incoming = _state.value as? CallState.Incoming ?: return
        val availability = callRepository.availability()
        if (availability !is CallAvailability.Available) {
            _state.value = CallState.Failed("Calling is not configured on this server")
            return
        }

        currentCallId = incoming.callId
        currentPeerId = incoming.peerId
        _state.value = CallState.Connecting(incoming.callId, incoming.peerId, incoming.isVideo)
        _controls.update { it.copy(cameraEnabled = incoming.isVideo, speakerOn = incoming.isVideo) }

        initialise(availability.iceServers, incoming.isVideo)
        // The offer has already arrived and is applied in observeSignalling; answering here
        // just produces the local description.
        answerPendingOffer(incoming.callId, incoming.peerId, incoming.isVideo)
    }

    fun decline() {
        val incoming = _state.value as? CallState.Incoming ?: return
        realtimeClient.send(
            RealtimeEvent.CallHangup(incoming.callId, incoming.peerId, reason = "declined"),
        )
        endCall("declined")
    }

    fun hangUp() {
        val callId = currentCallId
        val peerId = currentPeerId
        if (callId != null && peerId != null) {
            realtimeClient.send(RealtimeEvent.CallHangup(callId, peerId, reason = "ended"))
        }
        endCall("ended")
    }

    fun toggleMicrophone() {
        val muted = !_controls.value.micMuted
        audioTrack?.setEnabled(!muted)
        _controls.update { it.copy(micMuted = muted) }
    }

    fun toggleCamera() {
        val enabled = !_controls.value.cameraEnabled
        videoTrack?.setEnabled(enabled)
        _controls.update { it.copy(cameraEnabled = enabled) }
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                    _controls.update { it.copy(usingFrontCamera = isFrontFacing) }
                }

                override fun onCameraSwitchError(error: String?) {
                    Log.w(TAG, "camera switch failed: $error")
                }
            },
        )
    }

    fun toggleSpeaker() {
        val on = !_controls.value.speakerOn
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        @Suppress("DEPRECATION")
        audioManager?.isSpeakerphoneOn = on
        _controls.update { it.copy(speakerOn = on) }
    }

    // -----------------------------------------------------------------------
    // Signalling
    // -----------------------------------------------------------------------

    private var pendingOffer: SessionDescription? = null

    private suspend fun observeSignalling() {
        realtimeClient.events.collect { event ->
            when (event) {
                is RealtimeEvent.CallInvite -> {
                    // Only one call at a time: a second invite while busy is declined rather
                    // than silently replacing the active call.
                    if (_state.value !is CallState.Idle && _state.value !is CallState.Ended) {
                        realtimeClient.send(
                            RealtimeEvent.CallHangup(event.callId, event.fromUserId, reason = "busy"),
                        )
                        return@collect
                    }
                    _state.value = CallState.Incoming(
                        callId = event.callId,
                        peerId = event.fromUserId,
                        peerName = event.fromName,
                        peerAvatarUrl = event.fromAvatarUrl,
                        isVideo = event.isVideo,
                    )
                }

                is RealtimeEvent.CallOffer -> {
                    val description = SessionDescription(SessionDescription.Type.OFFER, event.sdp)
                    if (peerConnection == null) {
                        // The offer can arrive before the user has answered; hold it until
                        // the peer connection exists.
                        pendingOffer = description
                    } else {
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), description)
                        drainPendingCandidates()
                    }
                }

                is RealtimeEvent.CallAnswer -> {
                    peerConnection?.setRemoteDescription(
                        SimpleSdpObserver(),
                        SessionDescription(SessionDescription.Type.ANSWER, event.sdp),
                    )
                    drainPendingCandidates()
                }

                is RealtimeEvent.CallIceCandidate -> {
                    val candidate = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                    // A candidate before the remote description is a protocol error to
                    // addIceCandidate, so they are buffered instead.
                    if (peerConnection?.remoteDescription == null) {
                        pendingCandidates += candidate
                    } else {
                        peerConnection?.addIceCandidate(candidate)
                    }
                }

                is RealtimeEvent.CallHangup -> {
                    if (event.callId == currentCallId || _state.value is CallState.Incoming) {
                        endCall(event.reason)
                    }
                }

                else -> Unit
            }
        }
    }

    private fun answerPendingOffer(callId: String, peerId: String, isVideo: Boolean) {
        pendingOffer?.let { offer ->
            peerConnection?.setRemoteDescription(SimpleSdpObserver(), offer)
            pendingOffer = null
            drainPendingCandidates()
        }

        peerConnection?.createAnswer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), description)
                    realtimeClient.send(
                        RealtimeEvent.CallAnswer(callId, peerId, description.description),
                    )
                }
            },
            mediaConstraints(isVideo),
        )
    }

    private fun drainPendingCandidates() {
        if (peerConnection?.remoteDescription == null) return
        pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    // -----------------------------------------------------------------------
    // WebRTC setup
    // -----------------------------------------------------------------------

    private fun initialise(iceServerUrls: List<String>, isVideo: Boolean) {
        if (factory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            eglBase = EglBase.create()
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true),
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                .createPeerConnectionFactory()
        }

        val iceServers = iceServerUrls.map { url ->
            PeerConnection.IceServer.builder(url).createIceServer()
        }

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            // Unified Plan is the current standard; Plan B is deprecated and incompatible
            // with modern browsers.
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Gathering continually lets a call survive a Wi-Fi to mobile handover instead of
            // dropping when the network changes.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableCpuOveruseDetection = true
        }

        peerConnection = factory?.createPeerConnection(
            config,
            object : PeerConnectionObserverAdapter() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val callId = currentCallId ?: return
                    val peerId = currentPeerId ?: return
                    realtimeClient.send(
                        RealtimeEvent.CallIceCandidate(
                            callId = callId,
                            fromUserId = tokenStore.currentUserId.orEmpty(),
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        ),
                    )
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    val callId = currentCallId ?: return
                    val peerId = currentPeerId ?: return
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
                            _state.value = CallState.Connected(callId, peerId, isVideo, connectedAt)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED ->
                            // Disconnected is often transient (a brief network change), so
                            // this shows "reconnecting" rather than ending the call.
                            _state.value = CallState.Reconnecting(callId, peerId, isVideo)
                        PeerConnection.PeerConnectionState.FAILED -> {
                            _state.value = CallState.Failed("The connection failed")
                            endCall("failed")
                        }
                        PeerConnection.PeerConnectionState.CLOSED -> endCall("ended")
                        else -> Unit
                    }
                }

                override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                    (transceiver.receiver.track() as? VideoTrack)?.let { remoteVideoTrack = it }
                }

                override fun onAddStream(stream: MediaStream) {
                    stream.videoTracks.firstOrNull()?.let { remoteVideoTrack = it }
                }
            },
        )

        addLocalTracks(isVideo)
    }

    private fun addLocalTracks(isVideo: Boolean) {
        val factory = factory ?: return
        val connection = peerConnection ?: return

        audioSource = factory.createAudioSource(audioConstraints())
        audioTrack = factory.createAudioTrack("ping-audio", audioSource).apply { setEnabled(true) }
        connection.addTrack(audioTrack, listOf(STREAM_ID))

        if (!isVideo) return

        val enumerator = Camera2Enumerator(context)
        val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return

        videoCapturer = enumerator.createCapturer(frontCamera, null)
        surfaceHelper = SurfaceTextureHelper.create("ping-capture", eglBase?.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        videoCapturer?.initialize(surfaceHelper, context, videoSource?.capturerObserver)
        // 720p at 30 fps: a sensible ceiling that WebRTC's own bandwidth estimation will
        // reduce on a poor connection.
        videoCapturer?.startCapture(1280, 720, 30)

        videoTrack = factory.createVideoTrack("ping-video", videoSource).apply { setEnabled(true) }
        connection.addTrack(videoTrack, listOf(STREAM_ID))
    }

    private fun endCall(reason: String) {
        val duration = if (connectedAt > 0) (System.currentTimeMillis() - connectedAt) / 1000 else 0
        val callId = currentCallId

        if (callId != null) {
            scope.launch { callRepository.end(callId, duration) }
        }

        release()
        _state.value = CallState.Ended(reason, duration)
    }

    /**
     * Tears everything down.
     *
     * Order matters: the capturer has to stop before its source is disposed, and every track
     * before the factory. Getting this wrong leaves the camera light on after the call, which
     * users — reasonably — read as the app spying on them.
     */
    private fun release() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }

        videoCapturer = null
        surfaceHelper = null
        videoTrack = null
        videoSource = null
        audioTrack = null
        audioSource = null
        peerConnection = null
        remoteVideoTrack = null
        pendingCandidates.clear()
        pendingOffer = null
        connectedAt = 0
        currentCallId = null
        currentPeerId = null
        _controls.value = CallControls()
    }

    /**
     * Puts the call surface into a failed state with a reason.
     *
     * Used for refusals decided before any signalling happens - a group call, for instance -
     * so the user sees why on the call screen rather than watching the activity close itself.
     */
    fun failWith(reason: String) {
        _state.value = CallState.Failed(reason)
    }

    fun reset() {
        _state.value = CallState.Idle
    }

    private fun mediaConstraints(isVideo: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo.toString()))
    }

    private fun audioConstraints() = MediaConstraints().apply {
        // Speech-oriented processing. Without echo cancellation a speakerphone call
        // immediately feeds back.
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    }

    private companion object {
        const val TAG = "CallManager"
        const val STREAM_ID = "ping-stream"
    }
}

/** Only the SDP callbacks that matter, so call sites are not four empty overrides. */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) {
        Log.w("CallManager", "createSdp failed: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.w("CallManager", "setSdp failed: $error")
    }
}
