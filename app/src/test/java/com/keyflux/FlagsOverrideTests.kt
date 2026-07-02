package com.keyflux

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FlagsOverrideManager.evaluateFlagOverride() via companion object.
 */
class FlagsOverrideTests {

    private fun evaluate(name: String, prefs: Map<String, Any> = emptyMap()): Any? =
        FlagsOverrideManager.evaluateFlagOverride(name, prefs)

    @Test fun `unknown flag returns null`() { assertNull(evaluate("some_random_flag")) }

    @Test fun `AI flag returns null when AI disabled`() { assertNull(evaluate("enable_ai_core_llm")) }

    @Test fun `language_tags suffix returns star for true override`() {
        assertEquals("*", evaluate("enable_emojify_language_tags", mapOf("keyflux_enable_ai" to true)))
    }

    @Test fun `privacy telemetry flag returns false when privacy enabled`() {
        assertEquals(false, evaluate("enable_auto_correction_stats", mapOf("keyflux_enable_privacy" to true)))
    }

    @Test fun `locales suffix returns star for true override`() {
        assertEquals("*", evaluate("enable_emojify_model_language_tags", mapOf("keyflux_enable_ai" to true)))
    }

    @Test fun `AI flags return true when AI enabled`() {
        val p = mapOf("keyflux_enable_ai" to true)
        assertEquals(true, evaluate("enable_ai_core_llm", p))
        assertEquals(true, evaluate("enable_smart_compose", p))
        assertEquals(true, evaluate("enable_smart_reply", p))
        assertEquals(true, evaluate("enable_emojify", p))
        assertEquals(true, evaluate("enable_inline_suggestions", p))
    }

    @Test fun `Grammar flags return true when grammar enabled`() {
        val p = mapOf("keyflux_enable_grammar" to true)
        assertEquals(true, evaluate("enable_grammar_checker", p))
        assertEquals(true, evaluate("enable_on_device_proofread", p))
        assertEquals(true, evaluate("enable_proofread", p))
    }

    @Test fun `Multilingual flags return true when enabled`() {
        val p = mapOf("keyflux_enable_multilingual" to true)
        assertEquals(true, evaluate("enable_multilingual_typing", p))
        assertEquals(true, evaluate("enable_auto_multi_lang_on_all_pixel_devices", p))
    }

    @Test fun `Floating flags return true when enabled`() {
        val p = mapOf("keyflux_enable_floating" to true)
        assertEquals(true, evaluate("enable_auto_float_keyboard_in_landscape", p))
        assertEquals(true, evaluate("enable_split_keyboard", p))
    }

    @Test fun `Emoji Kitchen flags return true when enabled`() {
        val p = mapOf("keyflux_enable_emoji_kitchen" to true)
        assertEquals(true, evaluate("enable_emoji_kitchen_browse", p))
        assertEquals(true, evaluate("enable_emoji_search_v2", p))
    }

    @Test fun `Access Point flags return true when enabled`() {
        val p = mapOf("keyflux_enable_access_point" to true)
        assertEquals(true, evaluate("enable_access_points_menu_redesign", p))
        assertEquals(true, evaluate("use_silk_theme_by_default", p))
        assertEquals(true, evaluate("enable_custom_themes", p))
    }

    @Test fun `Metered downloads flags return true when enabled`() {
        val p = mapOf("keyflux_metered_downloads" to true)
        assertEquals(true, evaluate("allow_language_pack_downloads_on_metered_connections", p))
        assertEquals(true, evaluate("allow_metered_small_speech_pack_downloads", p))
    }

    @Test fun `Privacy override true flags`() {
        val p = mapOf("keyflux_enable_privacy" to true)
        assertEquals(true, evaluate("disable_correction_storage", p))
        assertEquals(true, evaluate("disable_content_capture_for_input_view", p))
        assertEquals(true, evaluate("deprecate_native_log_event", p))
    }

    @Test fun `Privacy override false flags`() {
        val p = mapOf("keyflux_enable_privacy" to true)
        assertEquals(false, evaluate("always_log_speed_stats", p))
        assertEquals(false, evaluate("enable_logging_for_emoji_search_query", p))
        assertEquals(false, evaluate("enable_auto_correction_stats", p))
    }

    @Test fun `Clipboard chips disabled disables entity extraction`() {
        assertEquals(false, evaluate("enable_clipboard_entity_extraction"))
    }

    @Test fun `Clipboard chips enabled enables action chips`() {
        val p = mapOf("keyflux_enable_clipboard_chips" to true)
        assertEquals(true, evaluate("enable_clipboard_action_chips", p))
        assertEquals(true, evaluate("enable_copy_to_reply", p))
    }

    @Test fun `TFLite engine flags return true when enabled`() {
        val p = mapOf("keyflux_enable_tflite_engine" to true)
        assertEquals(true, evaluate("enable_nwp_tflite_engine", p))
        assertEquals(true, evaluate("enable_emoji_predictor_tflite_engine", p))
    }

    @Test fun `Fast access flags return true when enabled`() {
        val p = mapOf("keyflux_enable_fast_access" to true)
        assertEquals(true, evaluate("enable_fast_access_bar", p))
    }

    @Test fun `Inline suggestions flags return true when enabled`() {
        val p = mapOf("keyflux_enable_inline_suggestions" to true)
        assertEquals(true, evaluate("enable_inline_suggestions_on_decoder_side", p))
    }

    @Test fun `Proactive emoji flags return true when enabled`() {
        val p = mapOf("keyflux_enable_proactive_emoji" to true)
        assertEquals(true, evaluate("enable_proactive_emoji_kitchen", p))
        assertEquals(true, evaluate("enable_expression_moment", p))
    }

    @Test fun `non-matching flag returns null even when AI enabled`() {
        val p = mapOf("keyflux_enable_ai" to true)
        assertNull(evaluate("enable_grammar_checker", p))
        assertNull(evaluate("enable_multilingual_typing", p))
    }
}
