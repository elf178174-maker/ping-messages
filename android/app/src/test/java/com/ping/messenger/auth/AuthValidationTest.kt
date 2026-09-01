package com.ping.messenger.auth

import com.google.common.truth.Truth.assertThat
import com.ping.messenger.feature.auth.AuthValidation
import com.ping.messenger.ui.components.passwordStrength
import org.junit.Test

/**
 * Validation is the app's first line of feedback and is mirrored server-side, so these tests
 * pin the exact boundaries rather than just the happy path — an off-by-one here shows up as a
 * form the user cannot submit for no visible reason.
 */
class AuthValidationTest {

    @Test
    fun `accepts ordinary email addresses`() {
        listOf(
            "ada@example.com",
            "ada.lovelace@sub.example.co.uk",
            "ada+ping@example.com",
            "a@b.co",
        ).forEach {
            assertThat(AuthValidation.isValidEmail(it)).isTrue()
        }
    }

    @Test
    fun `rejects malformed email addresses`() {
        listOf(
            "",
            "ada",
            "ada@",
            "@example.com",
            "ada@example",
            "ada @example.com",
            "ada@@example.com",
        ).forEach {
            assertThat(AuthValidation.isValidEmail(it)).isFalse()
        }
    }

    @Test
    fun `password must reach the minimum length`() {
        val nineChars = "abcdefghi"
        assertThat(nineChars).hasLength(9)
        assertThat(AuthValidation.validatePassword(nineChars))
            .isEqualTo(AuthValidation.PasswordProblem.TOO_SHORT)

        val tenChars = "abcdefghij"
        assertThat(tenChars).hasLength(AuthValidation.MIN_PASSWORD_LENGTH)
        assertThat(AuthValidation.validatePassword(tenChars)).isNull()
    }

    @Test
    fun `password rejects the obvious choices`() {
        listOf("password123", "PASSWORD123", "1234567890", "aaaaaaaaaa").forEach {
            assertThat(AuthValidation.validatePassword(it))
                .isEqualTo(AuthValidation.PasswordProblem.TOO_COMMON)
        }
    }

    @Test
    fun `a long passphrase is accepted without symbols`() {
        // NIST SP 800-63B explicitly discourages composition rules; a long, memorable
        // passphrase should pass even though it has no digits or punctuation.
        assertThat(AuthValidation.validatePassword("correct horse battery staple")).isNull()
    }

    @Test
    fun `username rules`() {
        listOf("ada", "ada_lovelace", "user123", "a_1").forEach {
            assertThat(AuthValidation.isValidUsername(it)).isTrue()
        }
        listOf("", "ad", "ada lovelace", "Ada-Lovelace", "a".repeat(25), "ada!").forEach {
            assertThat(AuthValidation.isValidUsername(it)).isFalse()
        }
    }

    @Test
    fun `username normalisation strips the at sign and lowercases`() {
        assertThat(AuthValidation.normaliseUsername("  @AdaLovelace ")).isEqualTo("adalovelace")
    }

    @Test
    fun `verification code must be six digits`() {
        assertThat(AuthValidation.isValidVerificationCode("123456")).isTrue()
        assertThat(AuthValidation.isValidVerificationCode("12345")).isFalse()
        assertThat(AuthValidation.isValidVerificationCode("1234567")).isFalse()
        assertThat(AuthValidation.isValidVerificationCode("12345a")).isFalse()
    }

    @Test
    fun `two-step pin rejects a repeated digit`() {
        assertThat(AuthValidation.isValidTwoStepPin("135790")).isTrue()
        assertThat(AuthValidation.isValidTwoStepPin("111111")).isFalse()
        assertThat(AuthValidation.isValidTwoStepPin("1234")).isFalse()
    }

    @Test
    fun `password strength increases with length and variety`() {
        assertThat(passwordStrength("short")).isEqualTo(0)
        assertThat(passwordStrength("alllowercase")).isAtLeast(1)
        assertThat(passwordStrength("Str0ng-Passphrase-With-Length!")).isEqualTo(3)
    }

    @Test
    fun `display name must not be blank or overlong`() {
        assertThat(AuthValidation.isValidDisplayName("Ada")).isTrue()
        assertThat(AuthValidation.isValidDisplayName("   ")).isFalse()
        assertThat(AuthValidation.isValidDisplayName("x".repeat(41))).isFalse()
    }
}
