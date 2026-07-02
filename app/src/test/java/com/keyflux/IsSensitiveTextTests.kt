package com.keyflux

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PreferencesManager.detectSensitiveText().
 * Tests OTP/PIN, credit card, and keyword detection.
 */
class IsSensitiveTextTests {

    private fun isSensitive(text: String): Boolean =
        PreferencesManager.detectSensitiveText(text)

    // --- OTP / PIN detection (4-8 digits) ---

    @Test fun `4-digit OTP is sensitive`() { assertTrue(isSensitive("1234")) }
    @Test fun `5-digit OTP is sensitive`() { assertTrue(isSensitive("12345")) }
    @Test fun `6-digit OTP is sensitive`() { assertTrue(isSensitive("654321")) }
    @Test fun `8-digit PIN is sensitive`() { assertTrue(isSensitive("12345678")) }
    @Test fun `3-digit number is NOT sensitive`() { assertFalse(isSensitive("123")) }
    @Test fun `9-digit number is NOT sensitive`() { assertFalse(isSensitive("123456789")) }
    @Test fun `OTP with whitespace is sensitive`() { assertTrue(isSensitive("  1234  ")) }
    @Test fun `mixed digits and letters is NOT sensitive`() { assertFalse(isSensitive("12ab34")) }

    // --- Credit card / bank account detection (12-19 digits) ---

    @Test fun `16-digit credit card number is sensitive`() { assertTrue(isSensitive("4111111111111111")) }
    @Test fun `credit card with spaces is sensitive`() { assertTrue(isSensitive("4111 1111 1111 1111")) }
    @Test fun `credit card with dashes is sensitive`() { assertTrue(isSensitive("4111-1111-1111-1111")) }
    @Test fun `19-digit number is sensitive`() { assertTrue(isSensitive("1234567890123456789")) }
    @Test fun `11-digit number is NOT sensitive`() { assertFalse(isSensitive("12345678901")) }
    @Test fun `20-digit number is NOT sensitive`() { assertFalse(isSensitive("12345678901234567890")) }

    // --- Keyword detection ---

    @Test fun `OTP keyword is sensitive`() { assertTrue(isSensitive("your OTP is 4567")) }
    @Test fun `password keyword is sensitive`() { assertTrue(isSensitive("Enter your password")) }
    @Test fun `verification keyword is sensitive`() { assertTrue(isSensitive("verification code")) }
    @Test fun `token keyword is sensitive`() { assertTrue(isSensitive("auth token")) }
    @Test fun `2fa keyword is sensitive`() { assertTrue(isSensitive("complete 2fa setup")) }
    @Test fun `mfa keyword is sensitive`() { assertTrue(isSensitive("mfa configuration")) }
    @Test fun `pin keyword is sensitive`() { assertTrue(isSensitive("enter pin")) }
    @Test fun `secret keyword is sensitive`() { assertTrue(isSensitive("api secret")) }
    @Test fun `passcode keyword is sensitive`() { assertTrue(isSensitive("enter passcode")) }

    // --- Arabic keyword detection ---

    @Test fun `Arabic password keyword is sensitive`() { assertTrue(isSensitive("أدخل كلمة المرور")) }
    @Test fun `Arabic OTP keyword is sensitive`() { assertTrue(isSensitive("رمز التحقق هو 1234")) }
    @Test fun `Arabic activation code keyword is sensitive`() { assertTrue(isSensitive("رمز تفعيل")) }
    @Test fun `Arabic secret number keyword is sensitive`() { assertTrue(isSensitive("أدخل رقم سري")) }
    @Test fun `Arabic login code keyword is sensitive`() { assertTrue(isSensitive("أدخل رمز الدخول")) }

    // --- Normal text (NOT sensitive) ---

    @Test fun `normal text is NOT sensitive`() { assertFalse(isSensitive("Hello, how are you?")) }
    @Test fun `empty string is NOT sensitive`() { assertFalse(isSensitive("")) }
    @Test fun `short number is NOT sensitive`() { assertFalse(isSensitive("12")) }
    @Test fun `URL is NOT sensitive`() { assertFalse(isSensitive("https://example.com")) }
    @Test fun `email is NOT sensitive`() { assertFalse(isSensitive("user@example.com")) }
    @Test fun `phone number with formatting is NOT sensitive`() { assertFalse(isSensitive("+1-234-567-8900")) }
    @Test fun `text with passcode keyword is sensitive`() { assertTrue(isSensitive("Use this passcode: 12345")) }
    @Test fun `case insensitive keyword detection`() { assertTrue(isSensitive("PASSWORD")); assertTrue(isSensitive("Password")); assertTrue(isSensitive("password")) }
    @Test fun `keyword within longer text is sensitive`() { assertTrue(isSensitive("Please enter your verification code now")) }
    @Test fun `whitespace-only string is NOT sensitive`() { assertFalse(isSensitive("   ")) }
}
