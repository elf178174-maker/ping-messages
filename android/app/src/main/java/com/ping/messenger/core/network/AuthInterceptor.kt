package com.ping.messenger.core.network

import com.ping.messenger.data.remote.api.AuthApi
import com.ping.messenger.data.remote.dto.RefreshRequest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Attaches the bearer token, and transparently refreshes it when the server says it is stale.
 *
 * Two details are what make this correct under concurrency:
 *
 *  1. A [Mutex] serialises refreshes, so ten requests racing a 401 produce one refresh call
 *     rather than ten — which would otherwise rotate the refresh token out from under itself.
 *  2. After acquiring the lock, the token is re-read. If another coroutine already refreshed
 *     it, this one simply retries with the new token instead of refreshing again.
 *
 * [runBlocking] is used deliberately: OkHttp interceptors are synchronous by contract and this
 * runs on OkHttp's own dispatcher thread, never the main thread.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
    private val authApi: Provider<AuthApi>,
    private val sessionExpiryNotifier: SessionExpiryNotifier,
) : Interceptor {

    private val refreshMutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Endpoints that must never carry (or refresh) a token.
        if (original.header(HEADER_NO_AUTH) != null) {
            return chain.proceed(original.newBuilder().removeHeader(HEADER_NO_AUTH).build())
        }

        var tokens = tokenStore.tokens.value
            ?: return chain.proceed(original)

        // Proactive refresh: cheaper than letting the request fail and retrying.
        if (tokens.isExpired()) {
            tokens = runBlocking { refreshIfNeeded(tokens) } ?: return chain.proceed(original)
        }

        val response = chain.proceed(original.withBearer(tokens.accessToken))
        if (response.code != 401) return response

        val refreshed = runBlocking { refreshIfNeeded(tokens) }
        if (refreshed == null) {
            sessionExpiryNotifier.notifyExpired()
            return response
        }

        response.close()
        return chain.proceed(original.withBearer(refreshed.accessToken))
    }

    /**
     * Refreshes unless another caller already did. Returns null when the refresh token itself
     * is rejected, which is the only true "you are signed out" signal.
     */
    private suspend fun refreshIfNeeded(seen: AuthTokens): AuthTokens? = refreshMutex.withLock {
        val current = tokenStore.tokens.value ?: return null
        if (current.accessToken != seen.accessToken) return current

        return try {
            val result = authApi.get().refresh(RefreshRequest(current.refreshToken))
            AuthTokens(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
                expiresAtMillis = System.currentTimeMillis() + result.expiresIn * 1000,
            ).also { tokenStore.save(it) }
        } catch (e: Exception) {
            tokenStore.clear()
            null
        }
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    companion object {
        /** Set on a request to opt it out of authentication entirely. */
        const val HEADER_NO_AUTH = "X-Ping-No-Auth"
    }
}

/**
 * Broadcasts "the session is gone" so the UI can route back to sign-in exactly once, no matter
 * how many concurrent requests discovered it.
 */
@Singleton
class SessionExpiryNotifier @Inject constructor() {
    private val expired = AtomicBoolean(false)
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val events = _events

    fun notifyExpired() {
        if (expired.compareAndSet(false, true)) _events.tryEmit(Unit)
    }

    fun reset() = expired.set(false)
}
