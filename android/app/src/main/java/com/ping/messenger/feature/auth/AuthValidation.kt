package com.ping.messenger.feature.auth

/**
 * Client-side validation.
 *
 * This exists to give immediate, specific feedback while typing — nothing more. **Every rule
 * here is enforced again on the server** (`backend/src/lib/validation.ts`), because client-side
 * validation is a usability feature, not a security control: a modified client can send
 * anything it likes.
 *
 * Pure functions with no Android dependencies, which is what makes them directly unit-testable.
 */
object AuthValidation {

    /**
     * Deliberately permissive. RFC 5322 allows far stranger addresses than most regexes accept,
     * and rejecting a valid address is a worse failure than accepting an invalid one that the
     * verification email will bounce off anyway.
     */
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$")

    private val USERNAME = Regex("^[a-z0-9_]{3,24}$")

    const val MIN_PASSWORD_LENGTH = 10
    const val MAX_DISPLAY_NAME_LENGTH = 40
    const val MAX_ABOUT_LENGTH = 140

    fun isValidEmail(email: String): Boolean =
        email.trim().let { it.length in 5..254 && EMAIL.matches(it) }

    /**
     * Length over composition rules.
     *
     * NIST SP 800-63B is explicit that mandatory character-class rules push people toward
     * `Password1!` while a longer passphrase is stronger and easier to remember. So the only
     * hard requirements are a sensible length and not being one of the obvious choices.
     */
    fun validatePassword(password: String): PasswordProblem? = when {
        password.length < MIN_PASSWORD_LENGTH -> PasswordProblem.TOO_SHORT
        password.length > 200 -> PasswordProblem.TOO_LONG
        password.lowercase() in COMMON_PASSWORDS -> PasswordProblem.TOO_COMMON
        password.all { it == password.first() } -> PasswordProblem.TOO_COMMON
        else -> null
    }

    fun isValidUsername(username: String): Boolean =
        USERNAME.matches(username.trim().lowercase())

    fun isValidDisplayName(name: String): Boolean =
        name.trim().length in 1..MAX_DISPLAY_NAME_LENGTH

    fun isValidVerificationCode(code: String): Boolean =
        code.trim().length == 6 && code.trim().all { it.isDigit() }

    fun isValidTwoStepPin(pin: String): Boolean =
        pin.length == 6 && pin.all { it.isDigit() } && pin.toSet().size > 1

    /** Normalises a username to the canonical form stored server-side. */
    fun normaliseUsername(username: String): String =
        username.trim().removePrefix("@").lowercase()

    enum class PasswordProblem { TOO_SHORT, TOO_LONG, TOO_COMMON }

    /**
     * A deliberately tiny list: the handful of passwords that appear at the top of every
     * breach corpus. A real blocklist belongs on the server, where it can be updated without
     * shipping an app release — see `backend/src/lib/passwords.ts`.
     */
    private val COMMON_PASSWORDS = setOf(
        "password", "password1", "password123", "passw0rd", "12345678", "123456789",
        "1234567890", "qwertyuiop", "letmein123", "iloveyou1", "adminadmin", "welcome123",
        "changeme123", "qwerty12345", "abc12345678",
    )
}
