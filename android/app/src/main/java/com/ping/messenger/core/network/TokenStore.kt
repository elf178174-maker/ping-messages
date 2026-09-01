package com.ping.messenger.core.network

import com.ping.messenger.core.datastore.SecureStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
) {
    /**
     * Treats a token as expired 30 seconds early. Refreshing slightly too often is cheap;
     * letting a request go out with a token that expires in flight is a spurious 401.
     */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAtMillis - 30_000
}

/**
 * The single source of truth for session credentials.
 *
 * Tokens live in [SecureStore] (Keystore-backed) and are mirrored into an in-memory
 * [StateFlow] so the OkHttp interceptor can read them without a disk hit on every request.
 */
@Singleton
class TokenStore @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val _tokens = MutableStateFlow(load())
    val tokens: StateFlow<AuthTokens?> = _tokens.asStateFlow()

    private val _signedInUserId = MutableStateFlow(secureStore.getString(SecureStore.KEY_USER_ID))
    val signedInUserId: StateFlow<String?> = _signedInUserId.asStateFlow()

    val isSignedIn: Boolean get() = _tokens.value != null

    val currentUserId: String? get() = _signedInUserId.value

    val deviceId: String?
        get() = secureStore.getString(SecureStore.KEY_DEVICE_ID)

    fun save(tokens: AuthTokens, userId: String? = null, deviceId: String? = null) {
        secureStore.putString(SecureStore.KEY_ACCESS_TOKEN, tokens.accessToken)
        secureStore.putString(SecureStore.KEY_REFRESH_TOKEN, tokens.refreshToken)
        secureStore.putLong(SecureStore.KEY_ACCESS_EXPIRES_AT, tokens.expiresAtMillis)
        userId?.let {
            secureStore.putString(SecureStore.KEY_USER_ID, it)
            _signedInUserId.value = it
        }
        deviceId?.let { secureStore.putString(SecureStore.KEY_DEVICE_ID, it) }
        _tokens.value = tokens
    }

    fun clear() {
        secureStore.remove(SecureStore.KEY_ACCESS_TOKEN)
        secureStore.remove(SecureStore.KEY_REFRESH_TOKEN)
        secureStore.remove(SecureStore.KEY_ACCESS_EXPIRES_AT)
        secureStore.remove(SecureStore.KEY_USER_ID)
        _tokens.value = null
        _signedInUserId.value = null
    }

    private fun load(): AuthTokens? {
        val access = secureStore.getString(SecureStore.KEY_ACCESS_TOKEN) ?: return null
        val refresh = secureStore.getString(SecureStore.KEY_REFRESH_TOKEN) ?: return null
        return AuthTokens(access, refresh, secureStore.getLong(SecureStore.KEY_ACCESS_EXPIRES_AT))
    }
}
