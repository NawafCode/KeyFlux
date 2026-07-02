package com.keyflux

import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests for DexKit flag resolution via FlagsOverrideManager companion methods.
 * Tests flag name extraction from mock obfuscated Gboard objects and flag override evaluation.
 */
class DexKitIntegrationTests {

    // --- Mock obfuscated Gboard flag objects ---

    /** Simulates Gboard's obfuscated flag with field "a" containing the flag name */
    private data class ObfuscatedFlag(val a: String)

    /** Simulates a flag where the name is in a non-standard field */
    private class FlagWithCustomField {
        val customName: String = "enable_smart_compose"
    }

    /** Simulates a deeply nested flag class */
    private open class BaseFlag
    private class NestedFlag : BaseFlag() {
        val flagName: String = "enable_grammar_checker"
    }

    /** Object with no String fields at all */
    private class EmptyFlag

    /** Object with multiple String fields - should return the first non-empty one */
    private class MultiStringFlag {
        val alpha: String = ""
        val beta: String = "enable_emojify"
        val gamma: String = "should_not_be_reached"
    }

    // --- extractFlagName tests ---

    @Test fun `extractFlagName from standard obfuscated object`() {
        val flag = ObfuscatedFlag("enable_ai_core_llm")
        assertEquals("enable_ai_core_llm", FlagsOverrideManager.extractFlagName(flag))
    }

    @Test fun `extractFlagName from object with custom field`() {
        val flag = FlagWithCustomField()
        assertEquals("enable_smart_compose", FlagsOverrideManager.extractFlagName(flag))
    }

    @Test fun `extractFlagName from deeply nested class`() {
        val flag = NestedFlag()
        assertEquals("enable_grammar_checker", FlagsOverrideManager.extractFlagName(flag))
    }

    @Test fun `extractFlagName returns null for empty string field`() {
        val flag = ObfuscatedFlag("")
        assertNull(FlagsOverrideManager.extractFlagName(flag))
    }

    @Test fun `extractFlagName returns null for object with no String fields`() {
        val flag = EmptyFlag()
        assertNull(FlagsOverrideManager.extractFlagName(flag))
    }

    @Test fun `extractFlagName skips empty fields and finds first non-empty`() {
        val flag = MultiStringFlag()
        assertEquals("enable_emojify", FlagsOverrideManager.extractFlagName(flag))
    }

    @Test fun `extractFlagName from data class with empty field then real name`() {
        data class Flag(val a: String, val b: String)
        val flag = Flag("", "enable_emoji_kitchen_browse")
        // After failing on "a" (empty), falls back to reflection finding first non-empty String
        assertEquals("enable_emoji_kitchen_browse", FlagsOverrideManager.extractFlagName(flag))
    }

    @Test fun `extractFlagName from simple String wrapper`() {
        data class SimpleFlag(val name: String)
        val flag = SimpleFlag("enable_split_keyboard")
        assertEquals("enable_split_keyboard", FlagsOverrideManager.extractFlagName(flag))
    }

    // --- evaluateFlagOverride comprehensive tests ---

    @Test fun `unknown flag returns null (no override needed)`() {
        val prefs = mapOf<String, Any>()
        assertNull(FlagsOverrideManager.evaluateFlagOverride("some_random_unknown_flag", prefs))
    }

    @Test fun `empty flag name returns null`() {
        assertNull(FlagsOverrideManager.evaluateFlagOverride("", emptyMap()))
    }

    // --- AI flags ---

    @Test fun `AI flags enabled returns true for all AI flags`() {
        val prefs = mapOf("keyflux_enable_ai" to true)
        val aiFlags = listOf(
            "enable_ai_core_llm", "enable_ai_core_smart_reply",
            "enable_emojify", "enable_emojify_settings_option",
            "enable_smart_reply", "enable_smart_compose",
            "enable_smart_compose_inline_suggestions", "enable_inline_suggestions",
            "enable_inline_suggestions_on_all_apps", "enable_custom_sticker_tab",
            "enable_custom_sticker_lol_fix", "enable_custom_sticker_naive_prompt_expander",
            "enable_sticker_predictions_while_typing", "enable_animated_emoji_content_suggestions",
            "show_animated_emoji_in_expression_moment"
        )
        for (flag in aiFlags) {
            assertEquals("Flag $flag should be overridden to true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    @Test fun `AI language tag flags return wildcard string when enabled`() {
        val prefs = mapOf("keyflux_enable_ai" to true)
        val langTagFlags = listOf(
            "enable_emojify_language_tags", "enable_emojify_model_language_tags",
            "enable_expression_moment_language_tags",
            "enable_expression_moment_proactive_emoji_kitchen_language_tags",
            "enable_dynamic_art_language_tags",
            "enable_tenor_trending_term_v2_for_language_tags"
        )
        for (flag in langTagFlags) {
            assertEquals("Flag $flag should be '*' when enabled", "*", FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    @Test fun `AI flags disabled returns null`() {
        val prefs = mapOf("keyflux_enable_ai" to false)
        assertNull(FlagsOverrideManager.evaluateFlagOverride("enable_ai_core_llm", prefs))
        assertNull(FlagsOverrideManager.evaluateFlagOverride("enable_smart_compose", prefs))
    }

    // --- Grammar flags ---

    @Test fun `Grammar flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_grammar" to true)
        val grammarFlags = listOf(
            "enable_grammar_checker", "enable_on_device_proofread",
            "enable_llm_based_grammar_checker", "enable_writing_tools_cooperative_mode",
            "enable_text_conversion", "enable_highlight_voice_reconversion_composing_text",
            "nga_enable_undo_delete", "enable_proofread"
        )
        for (flag in grammarFlags) {
            assertEquals("Flag $flag should be true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    @Test fun `Grammar locale flag returns wildcard instead of boolean`() {
        val prefs = mapOf("keyflux_enable_grammar" to true)
        assertEquals("*", FlagsOverrideManager.evaluateFlagOverride("enable_pk_auto_correction_locales", prefs))
    }

    // --- Privacy flags ---

    @Test fun `Privacy flags disable telemetry tracking`() {
        val prefs = mapOf("keyflux_enable_privacy" to true)
        val disabledFlags = listOf(
            "always_log_speed_stats", "enable_logging_for_emoji_search_query",
            "enable_internal_speech_enhancement_pii_logging",
            "enable_report_from_training_cache", "enable_chinese_training_cache",
            "enable_spell_checker_training_cache", "enable_training_cache_metrics_processors",
            "enable_conversation_id_in_training_cache", "enable_auto_correction_stats",
            "enable_metric_counts_stats", "enable_spatial_stats",
            "enable_spell_checker_stats", "enable_typo_stats",
            "voice_donation_promo_banner", "voice_donation_confirm_banner"
        )
        for (flag in disabledFlags) {
            assertEquals("Flag $flag should be false", false, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    @Test fun `Privacy flags enable disable_correction_storage`() {
        val prefs = mapOf("keyflux_enable_privacy" to true)
        val enabledFlags = listOf(
            "disable_correction_storage", "disable_content_capture_for_input_view",
            "deprecate_native_log_event"
        )
        for (flag in enabledFlags) {
            assertEquals("Flag $flag should be true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    // --- Clipboard chips ---

    @Test fun `Clipboard chips disabled returns false for entity extraction`() {
        val prefs = mapOf("keyflux_enable_clipboard_chips" to false)
        assertEquals(false, FlagsOverrideManager.evaluateFlagOverride("enable_clipboard_entity_extraction", prefs))
        assertEquals(false, FlagsOverrideManager.evaluateFlagOverride("enable_clipboard_query_refactoring", prefs))
    }

    @Test fun `Clipboard chips enabled returns true for action chips`() {
        val prefs = mapOf("keyflux_enable_clipboard_chips" to true)
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_clipboard_action_chips", prefs))
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_clipboard_entity_extraction", prefs))
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_copy_to_reply", prefs))
    }

    // --- Language tags special handling ---
    // The suffix-based wildcard/empty-string logic only applies when a flag also matches
    // a feature condition. These tests verify the behavior using real flags from feature lists.

    @Test fun `AI language_tags flag returns wildcard instead of boolean`() {
        val prefs = mapOf("keyflux_enable_ai" to true)
        // enable_emojify_language_tags ends with _language_tags and is in the AI list
        assertEquals("*", FlagsOverrideManager.evaluateFlagOverride("enable_emojify_language_tags", prefs))
    }

    @Test fun `AI locales flag returns wildcard instead of boolean`() {
        val prefs = mapOf("keyflux_enable_ai" to true)
        // enable_tenor_trending_term_v2_for_language_tags ends with _language_tags
        assertEquals("*", FlagsOverrideManager.evaluateFlagOverride("enable_tenor_trending_term_v2_for_language_tags", prefs))
    }

    @Test fun `non-matching flag with language_tags suffix returns null`() {
        // Flag not in any feature list, so suffix logic doesn't apply — returns null
        val prefs = mapOf("keyflux_enable_ai" to true)
        assertNull(FlagsOverrideManager.evaluateFlagOverride("unknown_flag_language_tags", prefs))
    }

    @Test fun `non-matching flag with locales suffix returns null`() {
        val prefs = mapOf("keyflux_enable_grammar" to true)
        assertNull(FlagsOverrideManager.evaluateFlagOverride("unknown_flag_locales", prefs))
    }

    @Test fun `non-matching flag with countries suffix returns null`() {
        val prefs = mapOf("keyflux_enable_grammar" to true)
        assertNull(FlagsOverrideManager.evaluateFlagOverride("unknown_flag_countries", prefs))
    }

    // --- Multilingual flags ---

    @Test fun `Multilingual flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_multilingual" to true)
        val mlFlags = listOf(
            "enable_multilingual_typing", "enable_crank_for_first_supported_locale_in_multilingual",
            "enable_crank_for_primary_locale_in_multilingual",
            "enable_more_candidates_view_for_multilingual",
            "enable_auto_multi_lang_on_all_pixel_devices",
            "enable_speech_enhancement_for_multilang_users"
        )
        for (flag in mlFlags) {
            assertEquals("Flag $flag should be true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    // --- Floating keyboard flags ---

    @Test fun `Floating flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_floating" to true)
        val floatFlags = listOf(
            "enable_auto_float_keyboard_in_landscape",
            "enable_auto_float_keyboard_in_multi_window",
            "enable_auto_float_keyboard_in_freeform",
            "enable_split_keyboard_on_tablet_large",
            "enable_dynamic_font_size_slider",
            "enable_split_keyboard", "enable_tablet_split_keyboard",
            "enable_enter_exit_animation"
        )
        for (flag in floatFlags) {
            assertEquals("Flag $flag should be true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    // --- Emoji Kitchen flags ---

    @Test fun `Emoji Kitchen flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_emoji_kitchen" to true)
        val emojiFlags = listOf(
            "enable_emoji_kitchen_browse", "enable_emoji_kitchen_browse_entry_point_v2",
            "enable_emoji_kitchen_for_zero_state_emojis", "enable_embedded_photo_picker",
            "enable_emoji_search_v2", "enable_emoji_recommendations",
            "enable_play_emoji_kitchen_mix_animation"
        )
        for (flag in emojiFlags) {
            assertEquals("Flag $flag should be true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    // --- Access point / theme flags ---

    @Test fun `Access point flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_access_point" to true)
        val apFlags = listOf(
            "enable_access_points_menu_redesign", "enable_access_point_keyboard",
            "use_silk_theme_by_default", "use_system_font", "enable_custom_themes",
            "enable_silk_theme", "enable_candidates_access_points_switching_animation",
            "keyboard_redesign_google_sans", "keyboard_redesign_forbid_key_shadows"
        )
        for (flag in apFlags) {
            assertEquals("Flag $flag should be true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    // --- Metered downloads ---

    @Test fun `Metered downloads flags enabled returns true`() {
        val prefs = mapOf("keyflux_metered_downloads" to true)
        val meteredFlags = listOf(
            "allow_language_pack_downloads_on_metered_connections",
            "allow_metered_network_to_download_langid_model",
            "allow_metered_small_speech_pack_downloads",
            "force_speech_language_pack_updates"
        )
        for (flag in meteredFlags) {
            assertEquals("Flag $flag should be true", true, FlagsOverrideManager.evaluateFlagOverride(flag, prefs))
        }
    }

    // --- Inline suggestions ---

    @Test fun `Inline suggestions flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_inline_suggestions" to true)
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_inline_suggestions_on_decoder_side", prefs))
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_multiword_predictions_as_inline_from_crank_cifg", prefs))
    }

    // --- Proactive emoji ---

    @Test fun `Proactive emoji flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_proactive_emoji" to true)
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_proactive_emoji_kitchen", prefs))
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_expression_moment", prefs))
    }

    // --- TFLite engine ---

    @Test fun `TFLite engine flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_tflite_engine" to true)
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_nwp_tflite_engine", prefs))
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_emoji_predictor_tflite_engine", prefs))
    }

    // --- Fast access ---

    @Test fun `Fast access flags enabled returns true`() {
        val prefs = mapOf("keyflux_enable_fast_access" to true)
        assertEquals(true, FlagsOverrideManager.evaluateFlagOverride("enable_fast_access_bar", prefs))
        // keyboard_redesign_google_sans is shared with access_point
        val result = FlagsOverrideManager.evaluateFlagOverride("keyboard_redesign_google_sans", prefs)
        assertNotNull("keyboard_redesign_google_sans should be overridden by fast access", result)
    }

    // --- Priority / precedence tests ---

    @Test fun `privacy telemetry disable takes precedence over AI flag`() {
        // When both privacy and AI are enabled, privacy telemetry flags should be disabled
        val prefs = mapOf("keyflux_enable_ai" to true, "keyflux_enable_privacy" to true)
        // enable_logging_for_emoji_search_query is in the privacy disabled list
        assertEquals(false, FlagsOverrideManager.evaluateFlagOverride("enable_logging_for_emoji_search_query", prefs))
    }

    @Test fun `flags outside all feature lists return null even when features enabled`() {
        val prefs = mapOf(
            "keyflux_enable_ai" to true,
            "keyflux_enable_grammar" to true,
            "keyflux_enable_privacy" to true
        )
        assertNull(FlagsOverrideManager.evaluateFlagOverride("completely_unknown_flag", prefs))
        assertNull(FlagsOverrideManager.evaluateFlagOverride("enable_unrelated_feature", prefs))
    }

    // --- Edge cases ---

    @Test fun `missing prefs default to false`() {
        val prefs = emptyMap<String, Any>()
        assertNull(FlagsOverrideManager.evaluateFlagOverride("enable_ai_core_llm", prefs))
        assertNull(FlagsOverrideManager.evaluateFlagOverride("enable_grammar_checker", prefs))
    }

    @Test fun `non-boolean pref value defaults to false`() {
        val prefs = mapOf("keyflux_enable_ai" to "not_a_boolean")
        assertNull(FlagsOverrideManager.evaluateFlagOverride("enable_ai_core_llm", prefs))
    }

    @Test fun `null pref value defaults to false`() {
        val prefs = mapOf<String, Any>("keyflux_enable_ai" to Any())
        assertNull(FlagsOverrideManager.evaluateFlagOverride("enable_ai_core_llm", prefs))
    }
}
