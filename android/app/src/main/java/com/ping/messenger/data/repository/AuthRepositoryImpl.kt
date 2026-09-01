package com.ping.messenger.data.repository

import android.os.Build
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.crypto.CryptoService
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.network.AuthTokens
import com.ping.messenger.core.network.SessionExpiryNotifier
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.AuthApi
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.AuthResponse
import com.ping.messenger.data.remote.dto.ChangePasswordRequest
import com.ping.messenger.data.remote.dto.ForgotPasswordRequest
import com.ping.messenger.data.remote.dto.LoginRequest
import com.ping.messenger.data.remote.dto.RegisterRequest
import com.ping.messenger.data.remote.dto.ResendCodeRequest
import com.ping.messenger.data.remote.dto.ResetPasswordRequest
import com.ping.messenger.data.remote.dto.TwoStepRequest
import com.ping.messenger.data.remote.dto.VerifyEmailRequest
import com.ping.messenger.domain.model.User
import com.ping.messenger.domain.repository.AuthRepository
import com.ping.messenger.domain.repository.AuthState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Session lifecycle.
 *
 * Two invariants this type is responsible for:
 *
 *  1. **The device's encryption identity exists before the account does.** The public key is
 *     generated locally and sent as part of registration, so there is never a window in which
 *     an account exists that peers cannot encrypt to.
 *  2. **Signing out leaves nothing behind.** Tokens, the private keyset, the message database
 *     and all preferences are cleared together. A shared device must not leak the previous
 *     user's history.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val pingApi: PingApi,
    private val tokenStore: TokenStore,
    private val userDao: UserDao,
    private val database: PingDatabase,
    private val crypto: CryptoService,
    private val preferences: AppPreferences,
    private val mapper: EntityMapper,
    private val sessionExpiry: SessionExpiryNotifier,
) : AuthRepository {

    /** Set after registration when the server says the address still needs confirming. */
    private val pendingVerificationEmail = MutableStateFlow<String?>(null)

    override val authState: Flow<AuthState> =
        combine(
            tokenStore.signedInUserId,
            pendingVerificationEmail,
        ) { userId, pendingEmail -> userId to pendingEmail }
            .flatMapLatest { (userId, pendingEmail) ->
                when {
                    pendingEmail != null -> flowOf(AuthState.NeedsEmailVerification(pendingEmail))
                    userId == null -> flowOf(AuthState.SignedOut)
                    else -> userDao.observeById(userId).map { entity ->
                        with(mapper) {
                            entity?.let { AuthState.SignedIn(it.toDomain()) } ?: AuthState.Loading
                        }
                    }
                }
            }

    override val currentUserId: String? get() = tokenStore.currentUserId

    override suspend fun register(
        email: String,
        password: String,
        username: String,
        displayName: String,
    ): Outcome<Unit> = runCatchingAppSuspend {
        val publicKey = crypto.ensureIdentity()
        val response = authApi.register(
            RegisterRequest(
                email = email.trim().lowercase(),
                password = password,
                username = username.trim().lowercase(),
                displayName = displayName.trim(),
                deviceName = deviceName(),
                publicKey = publicKey,
            ),
        )
        if (!response.emailVerified) {
            pendingVerificationEmail.value = email.trim().lowercase()
        }
        persistSession(response)
    }

    override suspend fun login(
        email: String,
        password: String,
        twoStepPin: String?,
    ): Outcome<Unit> = runCatchingAppSuspend {
        val publicKey = crypto.ensureIdentity()
        val response = authApi.login(
            LoginRequest(
                email = email.trim().lowercase(),
                password = password,
                deviceName = deviceName(),
                publicKey = publicKey,
                twoStepPin = twoStepPin,
            ),
        )
        if (!response.emailVerified) {
            pendingVerificationEmail.value = email.trim().lowercase()
        }
        persistSession(response)
    }

    override suspend fun verifyEmail(email: String, code: String): Outcome<Unit> =
        runCatchingAppSuspend {
            val response = authApi.verifyEmail(VerifyEmailRequest(email.trim().lowercase(), code.trim()))
            pendingVerificationEmail.value = null
            persistSession(response)
        }

    override suspend fun resendVerificationCode(email: String): Outcome<Unit> =
        runCatchingAppSuspend {
            authApi.resendCode(ResendCodeRequest(email.trim().lowercase()))
            Unit
        }

    override suspend fun requestPasswordReset(email: String): Outcome<Unit> =
        runCatchingAppSuspend {
            authApi.forgotPassword(ForgotPasswordRequest(email.trim().lowercase()))
            Unit
        }

    override suspend fun resetPassword(token: String, newPassword: String): Outcome<Unit> =
        runCatchingAppSuspend {
            authApi.resetPassword(ResetPasswordRequest(token, newPassword))
            Unit
        }

    override suspend fun changePassword(current: String, new: String): Outcome<Unit> =
        runCatchingAppSuspend {
            pingApi.changePassword(ChangePasswordRequest(current, new))
            Unit
        }

    override suspend fun setTwoStepPin(pin: String?, currentPassword: String): Outcome<Unit> =
        runCatchingAppSuspend {
            pingApi.setTwoStep(TwoStepRequest(pin, currentPassword))
            preferences.setTwoStep(pin != null)
        }

    override suspend fun isUsernameAvailable(username: String): Outcome<Boolean> =
        runCatchingAppSuspend {
            authApi.usernameAvailable(username.trim().lowercase()).available
        }

    override suspend fun signOut() {
        // Best effort: the server should hear about it, but a network failure must not leave
        // the user stuck signed in on the device.
        runCatching { pingApi.logout() }
        wipeLocalState()
    }

    override suspend fun deleteAccount(): Outcome<Unit> = runCatchingAppSuspend {
        pingApi.deleteAccount()
        wipeLocalState()
    }

    override suspend fun refreshCurrentUser(): Outcome<User> = runCatchingAppSuspend {
        val dto = pingApi.me()
        with(mapper) {
            val entity = dto.toEntity(userDao.findById(dto.id))
            userDao.upsert(entity)
            entity.toDomain()
        }
    }

    private suspend fun persistSession(response: AuthResponse) {
        tokenStore.save(
            tokens = AuthTokens(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                expiresAtMillis = System.currentTimeMillis() + response.expiresIn * 1000,
            ),
            userId = response.user.id,
            deviceId = response.deviceId,
        )
        sessionExpiry.reset()
        with(mapper) {
            userDao.upsert(response.user.toEntity(userDao.findById(response.user.id)))
        }
    }

    private suspend fun wipeLocalState() {
        tokenStore.clear()
        crypto.wipe()
        database.clearAllTables()
        preferences.clearAll()
        pendingVerificationEmail.value = null
        sessionExpiry.reset()
    }

    /** e.g. "Pixel 8 (Android 15)". Shown in the linked-devices list. */
    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val label = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        return "$label (Android ${Build.VERSION.RELEASE})"
    }
}
