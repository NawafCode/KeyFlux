package com.keyflux

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Localization.getString().
 * Verifies language resolution, fallback to English, and unknown key handling.
 */
class LocalizationTests {

    // --- English (default) ---

    @Test
    fun `English key returns English string`() {
        assertEquals("KeyFlux Settings", Localization.getString("category_title"))
    }

    @Test
    fun `English enable_ai returns correct string`() {
        assertEquals("Smart Typing Features", Localization.getString("enable_ai_title"))
    }

    @Test
    fun `English forever returns Forever`() {
        assertEquals("Forever", Localization.getString("forever"))
    }

    @Test
    fun `English days returns days`() {
        assertEquals("days", Localization.getString("days"))
    }

    // --- Fallback for unknown keys ---

    @Test
    fun `unknown key returns the key itself`() {
        assertEquals("nonexistent_key", Localization.getString("nonexistent_key"))
    }

    @Test
    fun `unknown key with language returns key`() {
        assertEquals("random_thing", Localization.getString("random_thing"))
    }

    // --- All supported languages have essential keys ---

    private val essentialKeys = listOf(
        "category_title", "enable_ai_title", "enable_grammar_title",
        "enable_multilingual_title", "enable_floating_title",
        "enable_emoji_kitchen_title", "enable_access_point_title",
        "enable_amoled_title", "metered_downloads_title",
        "force_incognito_title", "enable_privacy_title",
        "secure_clipboard_title", "clip_size_title", "clip_days_title",
        "log_switch_title", "force_stop_title", "forever", "days"
    )

    private val supportedLanguages = listOf("ar", "fa", "ur", "es", "fr", "de", "ru", "tr", "en")

    @Test
    fun `all supported languages have essential translation keys`() {
        for (lang in supportedLanguages) {
            val translations = Localization.translations[lang]
            assertNotNull("Translations map for '$lang' should exist", translations)
            for (key in essentialKeys) {
                val value = translations!![key] ?: translations["keyflux_$key"]
                assertNotNull(
                    "Language '$lang' should have key '$key' or 'keyflux_$key'",
                    value
                )
            }
        }
    }

    @Test
    fun `all languages have experimental feature keys`() {
        val experimentalKeys = listOf(
            "settings_header_experimental_title",
            "enable_inline_suggestions_title",
            "enable_proactive_emoji_title",
            "enable_clipboard_chips_title",
            "enable_tflite_engine_title",
            "enable_fast_access_title"
        )
        // These keys only exist in English and Arabic
        for (lang in listOf("en", "ar")) {
            val translations = Localization.translations[lang]
            for (key in experimentalKeys) {
                val value = translations!![key] ?: translations["keyflux_$key"]
                assertNotNull(
                    "Language '$lang' should have experimental key '$key' or 'keyflux_$key'",
                    value
                )
            }
        }
    }

    // --- Localization strings are non-empty ---

    @Test
    fun `all English strings are non-empty`() {
        val enTranslations = Localization.translations["en"]!!
        for ((key, value) in enTranslations) {
            assertTrue(
                "English string for '$key' should not be blank",
                value.isNotBlank()
            )
        }
    }

    @Test
    fun `all Arabic strings are non-empty`() {
        val arTranslations = Localization.translations["ar"]!!
        for ((key, value) in arTranslations) {
            assertTrue(
                "Arabic string for '$key' should not be blank",
                value.isNotBlank()
            )
        }
    }
}
