package com.keyflux

import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests for PluginEntry injection logic.
 * Tests shouldInjectSettings, isAlreadyInjected, and injected preference key lists.
 */
class InjectionLogicTests {

    // --- shouldInjectSettings tests ---

    @Test fun `should inject into SettingsActivity fragment`() {
        assertTrue(PluginEntry.shouldInjectSettings(
            "com.google.android.inputmethod.latin.SettingsActivity",
            hasMainSettingsMarker = false,
            alreadyInjected = false
        ))
    }

    @Test fun `should inject into PreferenceHeaderFragment`() {
        assertTrue(PluginEntry.shouldInjectSettings(
            "com.google.android.inputmethod.latin.PreferenceHeaderFragment",
            hasMainSettingsMarker = false,
            alreadyInjected = false
        ))
    }

    @Test fun `should inject into Header fragment`() {
        assertTrue(PluginEntry.shouldInjectSettings(
            "com.google.android.libraries.inputmethod.preferencewidgets.Header",
            hasMainSettingsMarker = false,
            alreadyInjected = false
        ))
    }

    @Test fun `should inject when has main settings marker (languages key found)`() {
        assertTrue(PluginEntry.shouldInjectSettings(
            "SomeUnknownFragment",
            hasMainSettingsMarker = true,
            alreadyInjected = false
        ))
    }

    @Test fun `should NOT inject when already injected`() {
        assertFalse(PluginEntry.shouldInjectSettings(
            "com.google.android.inputmethod.latin.SettingsActivity",
            hasMainSettingsMarker = false,
            alreadyInjected = true
        ))
    }

    @Test fun `should NOT inject into unrelated fragment`() {
        assertFalse(PluginEntry.shouldInjectSettings(
            "com.google.android.inputmethod.latin.AboutFragment",
            hasMainSettingsMarker = false,
            alreadyInjected = false
        ))
    }

    @Test fun `should NOT inject into empty class name`() {
        assertFalse(PluginEntry.shouldInjectSettings(
            "",
            hasMainSettingsMarker = false,
            alreadyInjected = false
        ))
    }

    @Test fun `should NOT inject when both marker and already injected`() {
        assertFalse(PluginEntry.shouldInjectSettings(
            "com.google.android.inputmethod.latin.SettingsActivity",
            hasMainSettingsMarker = true,
            alreadyInjected = true
        ))
    }

    @Test fun `should inject into fragment with Header in class name even if not main`() {
        assertTrue(PluginEntry.shouldInjectSettings(
            "com.example.SomeHeaderFragment",
            hasMainSettingsMarker = false,
            alreadyInjected = false
        ))
    }

    // --- isAlreadyInjected tests ---

    @Test fun `isAlreadyInjected returns true when keyflux_enable_multilingual exists`() {
        val prefs = mapOf("keyflux_enable_multilingual" to true)
        assertTrue(PluginEntry.isAlreadyInjected(prefs))
    }

    @Test fun `isAlreadyInjected returns false for empty prefs`() {
        assertFalse(PluginEntry.isAlreadyInjected(emptyMap()))
    }

    @Test fun `isAlreadyInjected returns false when only other keyflux keys exist`() {
        val prefs = mapOf("keyflux_enable_ai" to true, "keyflux_clip_size" to 10)
        assertFalse(PluginEntry.isAlreadyInjected(prefs))
    }

    @Test fun `isAlreadyInjected returns false for old non-prefixed key`() {
        val prefs = mapOf("enable_multilingual" to true)
        assertFalse(PluginEntry.isAlreadyInjected(prefs))
    }

    // --- INJECTED_SWITCH_KEYS completeness ---

    @Test fun `all switch keys have keyflux prefix`() {
        for (key in PluginEntry.INJECTED_SWITCH_KEYS) {
            assertTrue("Switch key '$key' should start with 'keyflux_'", key.startsWith("keyflux_"))
        }
    }

    @Test fun `all input keys have keyflux prefix`() {
        for (key in PluginEntry.INJECTED_INPUT_KEYS) {
            assertTrue("Input key '$key' should start with 'keyflux_'", key.startsWith("keyflux_"))
        }
    }

    @Test fun `all experimental keys have keyflux prefix`() {
        for (key in PluginEntry.INJECTED_EXPERIMENTAL_KEYS) {
            assertTrue("Experimental key '$key' should start with 'keyflux_'", key.startsWith("keyflux_"))
        }
    }

    @Test fun `multilingual key is the first switch key (used as injection marker)`() {
        assertEquals("keyflux_enable_multilingual", PluginEntry.INJECTED_SWITCH_KEYS.first())
    }

    @Test fun `no duplicate keys across all injected lists`() {
        val allKeys = PluginEntry.INJECTED_SWITCH_KEYS +
                       PluginEntry.INJECTED_INPUT_KEYS +
                       PluginEntry.INJECTED_EXPERIMENTAL_KEYS +
                       PluginEntry.BOTTOM_KEYS
        assertEquals("Should have no duplicate keys across all injected lists", allKeys.size, allKeys.toSet().size)
    }

    // --- Combined scenario tests ---

    @Test fun `fresh SettingsActivity should be injected`() {
        val fragmentName = "com.google.android.inputmethod.latin.SettingsActivity"
        val prefs = mapOf<String, Any>() // empty prefs = fresh install
        val alreadyInjected = PluginEntry.isAlreadyInjected(prefs)
        val shouldInject = PluginEntry.shouldInjectSettings(fragmentName, false, alreadyInjected)
        assertTrue("Fresh SettingsActivity should be injected", shouldInject)
    }

    @Test fun `SettingsActivity with multilingual key already set should NOT be re-injected`() {
        val fragmentName = "com.google.android.inputmethod.latin.SettingsActivity"
        val prefs = mapOf("keyflux_enable_multilingual" to true)
        val alreadyInjected = PluginEntry.isAlreadyInjected(prefs)
        val shouldInject = PluginEntry.shouldInjectSettings(fragmentName, false, alreadyInjected)
        assertFalse("Already injected SettingsActivity should not be re-injected", shouldInject)
    }

    @Test fun `About fragment without markers should NOT be injected`() {
        val fragmentName = "com.google.android.inputmethod.latin.AboutFragment"
        val prefs = mapOf<String, Any>()
        val alreadyInjected = PluginEntry.isAlreadyInjected(prefs)
        val shouldInject = PluginEntry.shouldInjectSettings(fragmentName, false, alreadyInjected)
        assertFalse("About fragment should not be injected", shouldInject)
    }

    @Test fun `unknown fragment with language marker should be injected`() {
        val fragmentName = "com.example.UnknownFragment"
        val prefs = mapOf<String, Any>()
        val alreadyInjected = PluginEntry.isAlreadyInjected(prefs)
        val shouldInject = PluginEntry.shouldInjectSettings(fragmentName, true, alreadyInjected)
        assertTrue("Unknown fragment with language marker should be injected", shouldInject)
    }

    // --- Preference key completeness ---

    @Test fun `all 12 switch preferences are covered`() {
        assertEquals("Should have exactly 12 switch preferences", 12, PluginEntry.INJECTED_SWITCH_KEYS.size)
    }

    @Test fun `all 2 input preferences are covered`() {
        assertEquals("Should have exactly 2 input preferences", 2, PluginEntry.INJECTED_INPUT_KEYS.size)
    }

    @Test fun `all 5 experimental preferences are covered`() {
        assertEquals("Should have exactly 5 experimental preferences", 5, PluginEntry.INJECTED_EXPERIMENTAL_KEYS.size)
    }

    @Test fun `force stop button is in bottom keys`() {
        assertTrue(PluginEntry.BOTTOM_KEYS.contains("keyflux_force_stop_btn"))
    }

    @Test fun `log switch is in switch keys`() {
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_log_switch"))
    }

    @Test fun `clipboard input keys match preferences manager keys`() {
        // These keys must match what PreferencesManager.resolveClipboardTextSize/Time reads
        assertTrue(PluginEntry.INJECTED_INPUT_KEYS.contains("keyflux_clip_size"))
        assertTrue(PluginEntry.INJECTED_INPUT_KEYS.contains("keyflux_clip_days"))
    }

    @Test fun `injected switch keys contain all privacy-related preferences`() {
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_enable_privacy"))
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_secure_clipboard"))
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_force_incognito"))
    }

    @Test fun `injected switch keys contain all AI-related preferences`() {
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_enable_ai"))
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_enable_grammar"))
    }

    @Test fun `injected switch keys contain all theme-related preferences`() {
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_enable_access_point"))
        assertTrue(PluginEntry.INJECTED_SWITCH_KEYS.contains("keyflux_enable_amoled"))
    }

    @Test fun `experimental keys match flags override manager feature toggles`() {
        // These experimental keys should correspond to feature flags in FlagsOverrideManager
        val expectedExperimental = listOf(
            "keyflux_enable_inline_suggestions",
            "keyflux_enable_proactive_emoji",
            "keyflux_enable_clipboard_chips",
            "keyflux_enable_tflite_engine",
            "keyflux_enable_fast_access"
        )
        assertEquals(expectedExperimental, PluginEntry.INJECTED_EXPERIMENTAL_KEYS)
    }
}
