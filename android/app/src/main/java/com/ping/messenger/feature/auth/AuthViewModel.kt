package com.ping.messenger.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class UsernameStatus { IDLE, CHECKING, AVAILABLE, TAKEN }

data class AuthFormState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val displayName: String = "",
    val username: String = "",
    val verificationCode: String = "",
    val twoStepPin: String = "",

    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmError: String? = null,
    val displayNameError: String? = null,
    val usernameError: String? = null,
    val codeError: String? = null,

    val usernameStatus: UsernameStatus = UsernameStatus.IDLE,
    val isSubmitting: Boolean = false,
    val requiresTwoStep: Boolean = false,
    val error: AppError? = null,
    val resendCooldownSeconds: Int = 0,
) {
    val canSubmitSignIn: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && !isSubmitting

    val canSubmitSignUp: Boolean
        get() = !isSubmitting &&
            AuthValidation.isValidEmail(email) &&
            AuthValidation.validatePassword(password) == null &&
            password == confirmPassword &&
            AuthValidation.isValidDisplayName(displayName) &&
            AuthValidation.isValidUsername(username) &&
            usernameStatus != UsernameStatus.TAKEN

    val canSubmitCode: Boolean
        get() = !isSubmitting && AuthValidation.isValidVerificationCode(verificationCode)
}

sealed interface AuthEvent {
    data object SignedIn : AuthEvent
    data class NeedsVerification(val email: String) : AuthEvent
    data class ResetLinkSent(val email: String) : AuthEvent
    data class Failed(val error: AppError) : AuthEvent
}

/**
 * Shared state holder for every authentication screen.
 *
 * One view-model rather than five because the screens share a single form: an email typed on
 * sign-in survives a bounce to sign-up, and the verification screen already knows the address.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(AuthFormState())
    val form: StateFlow<AuthFormState> = _form.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private var usernameCheckJob: Job? = null
    private var cooldownJob: Job? = null

    // ---- Field updates ----------------------------------------------------

    fun onEmailChange(value: String) = _form.update {
        it.copy(email = value, emailError = null, error = null)
    }

    fun onPasswordChange(value: String) = _form.update {
        it.copy(password = value, passwordError = null, confirmError = null, error = null)
    }

    fun onConfirmPasswordChange(value: String) = _form.update {
        it.copy(confirmPassword = value, confirmError = null)
    }

    fun onDisplayNameChange(value: String) = _form.update {
        it.copy(displayName = value.take(AuthValidation.MAX_DISPLAY_NAME_LENGTH), displayNameError = null)
    }

    fun onCodeChange(value: String) = _form.update {
        it.copy(verificationCode = value.filter(Char::isDigit).take(6), codeError = null)
    }

    fun onTwoStepPinChange(value: String) = _form.update {
        it.copy(twoStepPin = value.filter(Char::isDigit).take(6))
    }

    /**
     * Username availability is checked as the user types, debounced by 500 ms.
     *
     * Cancelling the previous job on each keystroke is what stops a stale response for
     * "ad" arriving after "ada" and wrongly marking the field taken.
     */
    fun onUsernameChange(value: String) {
        val normalised = AuthValidation.normaliseUsername(value).take(24)
        _form.update {
            it.copy(username = normalised, usernameError = null, usernameStatus = UsernameStatus.IDLE)
        }

        usernameCheckJob?.cancel()
        if (!AuthValidation.isValidUsername(normalised)) return

        usernameCheckJob = viewModelScope.launch {
            delay(500)
            _form.update { it.copy(usernameStatus = UsernameStatus.CHECKING) }
            when (val result = auth.isUsernameAvailable(normalised)) {
                is Outcome.Success -> _form.update {
                    // Guard against a response for a username the user has since edited away.
                    if (it.username != normalised) it
                    else it.copy(
                        usernameStatus = if (result.value) UsernameStatus.AVAILABLE else UsernameStatus.TAKEN,
                    )
                }
                is Outcome.Failure -> _form.update { it.copy(usernameStatus = UsernameStatus.IDLE) }
            }
        }
    }

    // ---- Submission -------------------------------------------------------

    fun signIn() {
        val state = _form.value
        if (!validateSignIn(state)) return

        submit {
            when (val result = auth.login(state.email, state.password, state.twoStepPin.takeIf { it.isNotBlank() })) {
                is Outcome.Success -> _events.emit(AuthEvent.SignedIn)
                is Outcome.Failure -> handleFailure(result.error)
            }
        }
    }

    fun signUp() {
        val state = _form.value
        if (!validateSignUp(state)) return

        submit {
            when (val result = auth.register(state.email, state.password, state.username, state.displayName)) {
                is Outcome.Success -> _events.emit(AuthEvent.NeedsVerification(state.email.trim().lowercase()))
                is Outcome.Failure -> handleFailure(result.error)
            }
        }
    }

    fun verifyEmail(email: String) {
        val state = _form.value
        if (!AuthValidation.isValidVerificationCode(state.verificationCode)) {
            _form.update { it.copy(codeError = "Enter the 6-digit code") }
            return
        }
        submit {
            when (val result = auth.verifyEmail(email, state.verificationCode)) {
                is Outcome.Success -> _events.emit(AuthEvent.SignedIn)
                is Outcome.Failure -> handleFailure(result.error)
            }
        }
    }

    fun resendCode(email: String) {
        if (_form.value.resendCooldownSeconds > 0) return
        submit {
            auth.resendVerificationCode(email)
            startResendCooldown()
        }
    }

    fun requestPasswordReset() {
        val state = _form.value
        if (!AuthValidation.isValidEmail(state.email)) {
            _form.update { it.copy(emailError = "Enter a valid email address") }
            return
        }
        submit {
            // The response is intentionally identical whether or not the address is registered,
            // so this screen cannot be used to enumerate accounts.
            auth.requestPasswordReset(state.email)
            _events.emit(AuthEvent.ResetLinkSent(state.email.trim()))
        }
    }

    fun dismissError() = _form.update { it.copy(error = null) }

    // ---- Internals --------------------------------------------------------

    private fun submit(block: suspend () -> Unit) {
        viewModelScope.launch {
            _form.update { it.copy(isSubmitting = true, error = null) }
            try {
                block()
            } finally {
                _form.update { it.copy(isSubmitting = false) }
            }
        }
    }

    private suspend fun handleFailure(error: AppError) {
        when {
            // The server signals "this account has two-step verification" with a 403 carrying
            // a known reason; the form grows a PIN field rather than pushing a new screen.
            error is AppError.Forbidden && error.reason?.contains("two-step", ignoreCase = true) == true ->
                _form.update { it.copy(requiresTwoStep = true) }

            error is AppError.Conflict ->
                _form.update {
                    it.copy(usernameError = error.detail ?: "Already taken", usernameStatus = UsernameStatus.TAKEN)
                }

            error is AppError.Validation && error.fieldErrors.isNotEmpty() ->
                _form.update { current ->
                    current.copy(
                        emailError = error.fieldErrors["email"] ?: current.emailError,
                        passwordError = error.fieldErrors["password"] ?: current.passwordError,
                        usernameError = error.fieldErrors["username"] ?: current.usernameError,
                        displayNameError = error.fieldErrors["displayName"] ?: current.displayNameError,
                    )
                }

            else -> _form.update { it.copy(error = error) }
        }
        _events.emit(AuthEvent.Failed(error))
    }

    private fun validateSignIn(state: AuthFormState): Boolean {
        val emailError = if (AuthValidation.isValidEmail(state.email)) null else "Enter a valid email address"
        val passwordError = if (state.password.isNotBlank()) null else "Enter your password"
        _form.update { it.copy(emailError = emailError, passwordError = passwordError) }
        return emailError == null && passwordError == null
    }

    private fun validateSignUp(state: AuthFormState): Boolean {
        val emailError = if (AuthValidation.isValidEmail(state.email)) null else "Enter a valid email address"
        val passwordError = when (AuthValidation.validatePassword(state.password)) {
            AuthValidation.PasswordProblem.TOO_SHORT ->
                "Use at least ${AuthValidation.MIN_PASSWORD_LENGTH} characters"
            AuthValidation.PasswordProblem.TOO_LONG -> "That password is too long"
            AuthValidation.PasswordProblem.TOO_COMMON -> "That password is too easy to guess"
            null -> null
        }
        val confirmError = if (state.password == state.confirmPassword) null else "Passwords do not match"
        val nameError = if (AuthValidation.isValidDisplayName(state.displayName)) null else "Tell people what to call you"
        val usernameError = if (AuthValidation.isValidUsername(state.username)) {
            null
        } else {
            "3–24 letters, numbers or underscores"
        }

        _form.update {
            it.copy(
                emailError = emailError,
                passwordError = passwordError,
                confirmError = confirmError,
                displayNameError = nameError,
                usernameError = usernameError,
            )
        }
        return listOf(emailError, passwordError, confirmError, nameError, usernameError).all { it == null }
    }

    private fun startResendCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var remaining = RESEND_COOLDOWN_SECONDS
            while (isActive && remaining > 0) {
                _form.update { it.copy(resendCooldownSeconds = remaining) }
                delay(1_000)
                remaining--
            }
            _form.update { it.copy(resendCooldownSeconds = 0) }
        }
    }

    private companion object {
        const val RESEND_COOLDOWN_SECONDS = 60
    }
}
