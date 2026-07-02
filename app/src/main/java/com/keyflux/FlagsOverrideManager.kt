package com.keyflux

import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.*
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod

/**
 * Manages flag override logic: resolving the ReadConfig method via DexKit
 * and applying per-feature flag overrides when Gboard reads its configuration.
 */
internal class FlagsOverrideManager(private val plugin: PluginEntry) {

    companion object {
        /**
         * Extract the flag name from an obfuscated Gboard flag object.
         * Tries the default field "a" first, then searches the class hierarchy.
         */
        fun extractFlagName(obj: Any): String? {
            try {
                val nameField = try {
                    de.robv.android.xposed.XposedHelpers.getObjectField(obj, "a")
                } catch (t: Throwable) {
                    null
                }
                if (nameField is String) {
                    return nameField
                }

                // Fallback: Dynamically search for the first String field in the class hierarchy
                var clazz: Class<*>? = obj.javaClass
                while (clazz != null && clazz != Any::class.java) {
                    for (field in clazz.declaredFields) {
                        if (field.type == String::class.java) {
                            field.isAccessible = true
                            val value = field.get(obj) as? String
                            if (!value.isNullOrEmpty()) {
                                return value
                            }
                        }
                    }
                    clazz = clazz.superclass
                }
            } catch (t: Throwable) {
                // silently fail
            }
            return null
        }

        /**
         * Determine whether a flag should be overridden, and what value to use.
         * Returns the override value if the flag should be overridden, or null to leave it as-is.
         */
        fun evaluateFlagOverride(name: String, prefs: Map<String, Any>): Any? {
            val enableAi = prefs["keyflux_enable_ai"] as? Boolean ?: false
            val enableGrammar = prefs["keyflux_enable_grammar"] as? Boolean ?: false
            val enableMultilingual = prefs["keyflux_enable_multilingual"] as? Boolean ?: false
            val enableFloating = prefs["keyflux_enable_floating"] as? Boolean ?: false
            val enableEmojiKitchen = prefs["keyflux_enable_emoji_kitchen"] as? Boolean ?: false
            val enableAccessPoint = prefs["keyflux_enable_access_point"] as? Boolean ?: false
            val meteredDownloads = prefs["keyflux_metered_downloads"] as? Boolean ?: false
            val enablePrivacy = prefs["keyflux_enable_privacy"] as? Boolean ?: false
            val secureClipboard = prefs["keyflux_secure_clipboard"] as? Boolean ?: false
            val enableInlineSuggestions = prefs["keyflux_enable_inline_suggestions"] as? Boolean ?: false
            val enableProactiveEmoji = prefs["keyflux_enable_proactive_emoji"] as? Boolean ?: false
            val enableClipboardChips = prefs["keyflux_enable_clipboard_chips"] as? Boolean ?: false
            val enableTfliteEngine = prefs["keyflux_enable_tflite_engine"] as? Boolean ?: false
            val enableFastAccess = prefs["keyflux_enable_fast_access"] as? Boolean ?: false

            return evaluateFlagOverrideImpl(name, enableAi, enableGrammar, enableMultilingual, enableFloating, enableEmojiKitchen, enableAccessPoint, meteredDownloads, enablePrivacy, enableInlineSuggestions, enableProactiveEmoji, enableClipboardChips, enableTfliteEngine, enableFastAccess)
        }

        private fun evaluateFlagOverrideImpl(
            name: String, enableAi: Boolean, enableGrammar: Boolean, enableMultilingual: Boolean,
            enableFloating: Boolean, enableEmojiKitchen: Boolean, enableAccessPoint: Boolean,
            meteredDownloads: Boolean, enablePrivacy: Boolean, enableInlineSuggestions: Boolean,
            enableProactiveEmoji: Boolean, enableClipboardChips: Boolean, enableTfliteEngine: Boolean,
            enableFastAccess: Boolean
        ): Any? {
            val overrideTrueVal = if (name.endsWith("_language_tags") || name.endsWith("_locales") || name.endsWith("_countries")) "*" else true
            val overrideFalseVal = if (name.endsWith("_language_tags") || name.endsWith("_locales") || name.endsWith("_countries")) "" else false

            if (!enableClipboardChips && (name == "enable_clipboard_entity_extraction" || name == "enable_clipboard_query_refactoring")) return overrideFalseVal
            if (enablePrivacy) {
                if (name == "disable_correction_storage" || name == "disable_content_capture_for_input_view" || name == "deprecate_native_log_event") return overrideTrueVal
                if (name == "always_log_speed_stats" || name == "enable_logging_for_emoji_search_query" || name == "enable_internal_speech_enhancement_pii_logging" || name == "enable_report_from_training_cache" || name == "enable_chinese_training_cache" || name == "enable_spell_checker_training_cache" || name == "enable_training_cache_metrics_processors" || name == "enable_conversation_id_in_training_cache" || name == "enable_auto_correction_stats" || name == "enable_metric_counts_stats" || name == "enable_spatial_stats" || name == "enable_spell_checker_stats" || name == "enable_typo_stats" || name == "voice_donation_promo_banner" || name == "voice_donation_confirm_banner") return overrideFalseVal
            }
            if (enableAi && (name == "enable_ai_core_llm" || name == "enable_ai_core_smart_reply" || name == "enable_emojify" || name == "enable_emojify_settings_option" || name == "enable_smart_reply" || name == "enable_smart_compose" || name == "enable_smart_compose_inline_suggestions" || name == "enable_inline_suggestions" || name == "enable_inline_suggestions_on_all_apps" || name == "enable_custom_sticker_tab" || name == "enable_custom_sticker_lol_fix" || name == "enable_custom_sticker_naive_prompt_expander" || name == "enable_sticker_predictions_while_typing" || name == "enable_animated_emoji_content_suggestions" || name == "show_animated_emoji_in_expression_moment" || name == "enable_emojify_language_tags" || name == "enable_emojify_model_language_tags" || name == "enable_expression_moment_language_tags" || name == "enable_expression_moment_proactive_emoji_kitchen_language_tags" || name == "enable_dynamic_art_language_tags" || name == "enable_tenor_trending_term_v2_for_language_tags")) return overrideTrueVal
            if (enableGrammar && (name == "enable_grammar_checker" || name == "enable_on_device_proofread" || name == "enable_llm_based_grammar_checker" || name == "enable_writing_tools_cooperative_mode" || name == "enable_text_conversion" || name == "enable_highlight_voice_reconversion_composing_text" || name == "nga_enable_undo_delete" || name == "enable_proofread" || name == "enable_pk_auto_correction_locales")) return overrideTrueVal
            if (enableMultilingual && (name == "enable_multilingual_typing" || name == "enable_crank_for_first_supported_locale_in_multilingual" || name == "enable_crank_for_primary_locale_in_multilingual" || name == "enable_more_candidates_view_for_multilingual" || name == "enable_auto_multi_lang_on_all_pixel_devices" || name == "enable_speech_enhancement_for_multilang_users")) return overrideTrueVal
            if (enableFloating && (name == "enable_auto_float_keyboard_in_landscape" || name == "enable_auto_float_keyboard_in_multi_window" || name == "enable_auto_float_keyboard_in_freeform" || name == "enable_split_keyboard_on_tablet_large" || name == "enable_dynamic_font_size_slider" || name == "enable_split_keyboard" || name == "enable_tablet_split_keyboard" || name == "enable_enter_exit_animation")) return overrideTrueVal
            if (enableEmojiKitchen && (name == "enable_emoji_kitchen_browse" || name == "enable_emoji_kitchen_browse_entry_point_v2" || name == "enable_emoji_kitchen_for_zero_state_emojis" || name == "enable_embedded_photo_picker" || name == "enable_emoji_search_v2" || name == "enable_emoji_recommendations" || name == "enable_play_emoji_kitchen_mix_animation")) return overrideTrueVal
            if (enableAccessPoint && (name == "enable_access_points_menu_redesign" || name == "enable_access_point_keyboard" || name == "use_silk_theme_by_default" || name == "use_system_font" || name == "enable_custom_themes" || name == "enable_silk_theme" || name == "enable_candidates_access_points_switching_animation" || name == "keyboard_redesign_google_sans" || name == "keyboard_redesign_forbid_key_shadows")) return overrideTrueVal
            if (meteredDownloads && (name == "allow_language_pack_downloads_on_metered_connections" || name == "allow_metered_network_to_download_langid_model" || name == "allow_metered_small_speech_pack_downloads" || name == "force_speech_language_pack_updates")) return overrideTrueVal
            if (enableInlineSuggestions && (name == "enable_inline_suggestions_on_decoder_side" || name == "enable_multiword_predictions_as_inline_from_crank_cifg")) return overrideTrueVal
            if (enableProactiveEmoji && (name == "enable_proactive_emoji_kitchen" || name == "enable_expression_moment")) return overrideTrueVal
            if (enableClipboardChips && (name == "enable_clipboard_action_chips" || name == "enable_clipboard_entity_extraction" || name == "enable_copy_to_reply")) return overrideTrueVal
            if (enableTfliteEngine && (name == "enable_nwp_tflite_engine" || name == "enable_emoji_predictor_tflite_engine")) return overrideTrueVal
            if (enableFastAccess && (name == "enable_fast_access_bar" || name == "keyboard_redesign_google_sans")) return overrideTrueVal

            return null
        }
    }

    private fun log(msg: String) = plugin.log(msg)
    private fun logAlways(msg: String) = plugin.logAlways(msg)

    /**
     * Resolve Gboard's ReadConfig method using DexKit with multiple fallback queries.
     * Runs queries in parallel for speed.
     */
    internal suspend fun findReadConfigMethod(bridge: DexKitBridge): DexMethod? = coroutineScope {
        val queries = listOf(
            // Primary Query: "Invalid flag: "
            {
                bridge.findMethod {
                    matcher {
                        usingStrings("Invalid flag: ")
                        returnType("java.lang.Object")
                    }
                }
            },
            // Fallback 1: "Invalid flag" (no space, regex or substring)
            {
                bridge.findMethod {
                    matcher {
                        usingStrings("Invalid flag:")
                        returnType("java.lang.Object")
                    }
                }
            },
            // Fallback 2: "HermeticFileOverrides" (Phenotype loader indicator)
            {
                bridge.findMethod {
                    matcher {
                        usingStrings("HermeticFileOverrides")
                        returnType("java.lang.Object")
                    }
                }
            },
            // Fallback 3: "PhenotypeFlag" reference
            {
                bridge.findMethod {
                    matcher {
                        usingStrings("PhenotypeFlag")
                        returnType("java.lang.Object")
                    }
                }
            }
        )

        val deferredResults = queries.mapIndexed { index, query ->
            async(Dispatchers.Default) {
                try {
                    val methods = query()
                    if (methods.isNotEmpty()) {
                        log("ReadConfig found via query index $index. Matches count: ${methods.size}")
                        if (methods.size > 1) {
                            log("Multiple ReadConfig matches found: ${methods.joinToString { it.toDexMethod().serialize() }}. Selecting first match.")
                        }
                        methods.first().toDexMethod()
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    log("Query index $index threw an exception: ${t.message}")
                    null
                }
            }
        }

        val results = deferredResults.awaitAll()
        for (res in results) {
            if (res != null) return@coroutineScope res
        }

        log("All DexKit ReadConfig resolver queries exhausted. Could not resolve flag reader method.")
        null
    }

    /**
     * Extract the flag name from an obfuscated Gboard flag object.
     * Tries the default field "a" first, then searches the class hierarchy.
     */
    internal fun getFlagName(obj: Any): String? {
        try {
            val nameField = try {
                XposedHelpers.getObjectField(obj, "a")
            } catch (t: Throwable) {
                log("Failed to execute getFlagName: ${t.message}")
                null
            }
            if (nameField is String) {
                return nameField
            }

            // Fallback: Dynamically search for the first String field in the class hierarchy
            var clazz: Class<*>? = obj.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    if (field.type == String::class.java) {
                        field.isAccessible = true
                        val value = field.get(obj) as? String
                        if (!value.isNullOrEmpty()) {
                            return value
                        }
                    }
                }
                clazz = clazz.superclass
            }
        } catch (t: Throwable) {
            log("Error dynamically resolving flag name field: ${t.message}")
        }
        return null
    }

    /**
     * Determine whether a flag should be overridden, and what value to use.
     * Returns the override value if the flag should be overridden, or null to leave it as-is.
     */
    internal fun evaluateFlagOverride(name: String): Any? {
        val overrideTrueVal = if (name.endsWith("_language_tags") || name.endsWith("_locales") || name.endsWith("_countries")) "*" else true
        val overrideFalseVal = if (name.endsWith("_language_tags") || name.endsWith("_locales") || name.endsWith("_countries")) "" else false

        // Default clipboard entity extraction overrides
        if (!plugin.enableClipboardChips && (
            name == "enable_clipboard_entity_extraction" ||
            name == "enable_clipboard_query_refactoring"
        )) {
            return overrideFalseVal
        }

        if (plugin.enablePrivacy) {
            if (name == "disable_correction_storage" ||
                name == "disable_content_capture_for_input_view" ||
                name == "deprecate_native_log_event"
            ) {
                return overrideTrueVal
            }
            if (name == "always_log_speed_stats" ||
                name == "enable_logging_for_emoji_search_query" ||
                name == "enable_internal_speech_enhancement_pii_logging" ||
                name == "enable_report_from_training_cache" ||
                name == "enable_chinese_training_cache" ||
                name == "enable_spell_checker_training_cache" ||
                name == "enable_training_cache_metrics_processors" ||
                name == "enable_conversation_id_in_training_cache" ||
                name == "enable_auto_correction_stats" ||
                name == "enable_metric_counts_stats" ||
                name == "enable_spatial_stats" ||
                name == "enable_spell_checker_stats" ||
                name == "enable_typo_stats" ||
                name == "voice_donation_promo_banner" ||
                name == "voice_donation_confirm_banner"
            ) {
                return overrideFalseVal
            }
        }

        if (plugin.enableAi && (name == "enable_ai_core_llm" || name == "enable_ai_core_smart_reply" || name == "enable_emojify" || name == "enable_emojify_settings_option" || name == "enable_smart_reply" || name == "enable_smart_compose" || name == "enable_smart_compose_inline_suggestions" || name == "enable_inline_suggestions" || name == "enable_inline_suggestions_on_all_apps" || name == "enable_custom_sticker_tab" || name == "enable_custom_sticker_lol_fix" || name == "enable_custom_sticker_naive_prompt_expander" || name == "enable_sticker_predictions_while_typing" || name == "enable_animated_emoji_content_suggestions" || name == "show_animated_emoji_in_expression_moment" || name == "enable_emojify_language_tags" || name == "enable_emojify_model_language_tags" || name == "enable_expression_moment_language_tags" || name == "enable_expression_moment_proactive_emoji_kitchen_language_tags" || name == "enable_dynamic_art_language_tags" || name == "enable_tenor_trending_term_v2_for_language_tags")) {
            return overrideTrueVal
        }

        if (plugin.enableGrammar && (name == "enable_grammar_checker" || name == "enable_on_device_proofread" || name == "enable_llm_based_grammar_checker" || name == "enable_writing_tools_cooperative_mode" || name == "enable_text_conversion" || name == "enable_highlight_voice_reconversion_composing_text" || name == "nga_enable_undo_delete" || name == "enable_proofread" || name == "enable_pk_auto_correction_locales")) {
            return overrideTrueVal
        }

        if (plugin.enableMultilingual && (name == "enable_multilingual_typing" || name == "enable_crank_for_first_supported_locale_in_multilingual" || name == "enable_crank_for_primary_locale_in_multilingual" || name == "enable_more_candidates_view_for_multilingual" || name == "enable_auto_multi_lang_on_all_pixel_devices" || name == "enable_speech_enhancement_for_multilang_users")) {
            return overrideTrueVal
        }

        if (plugin.enableFloating && (name == "enable_auto_float_keyboard_in_landscape" || name == "enable_auto_float_keyboard_in_multi_window" || name == "enable_auto_float_keyboard_in_freeform" || name == "enable_split_keyboard_on_tablet_large" || name == "enable_dynamic_font_size_slider" || name == "enable_split_keyboard" || name == "enable_tablet_split_keyboard" || name == "enable_enter_exit_animation")) {
            return overrideTrueVal
        }

        if (plugin.enableEmojiKitchen && (name == "enable_emoji_kitchen_browse" || name == "enable_emoji_kitchen_browse_entry_point_v2" || name == "enable_emoji_kitchen_for_zero_state_emojis" || name == "enable_embedded_photo_picker" || name == "enable_emoji_search_v2" || name == "enable_emoji_recommendations" || name == "enable_play_emoji_kitchen_mix_animation")) {
            return overrideTrueVal
        }

        if (plugin.enableAccessPoint && (name == "enable_access_points_menu_redesign" || name == "enable_access_point_keyboard" || name == "use_silk_theme_by_default" || name == "use_system_font" || name == "enable_custom_themes" || name == "enable_silk_theme" || name == "enable_candidates_access_points_switching_animation" || name == "keyboard_redesign_google_sans" || name == "keyboard_redesign_forbid_key_shadows")) {
            return overrideTrueVal
        }

        if (plugin.meteredDownloads && (name == "allow_language_pack_downloads_on_metered_connections" || name == "allow_metered_network_to_download_langid_model" || name == "allow_metered_small_speech_pack_downloads" || name == "force_speech_language_pack_updates")) {
            return overrideTrueVal
        }

        if (plugin.enableInlineSuggestions && (name == "enable_inline_suggestions_on_decoder_side" || name == "enable_multiword_predictions_as_inline_from_crank_cifg")) {
            return overrideTrueVal
        }

        if (plugin.enableProactiveEmoji && (name == "enable_proactive_emoji_kitchen" || name == "enable_expression_moment")) {
            return overrideTrueVal
        }

        if (plugin.enableClipboardChips && (name == "enable_clipboard_action_chips" || name == "enable_clipboard_entity_extraction" || name == "enable_copy_to_reply")) {
            return overrideTrueVal
        }

        if (plugin.enableTfliteEngine && (name == "enable_nwp_tflite_engine" || name == "enable_emoji_predictor_tflite_engine")) {
            return overrideTrueVal
        }

        if (plugin.enableFastAccess && (name == "enable_fast_access_bar" || name == "keyboard_redesign_google_sans")) {
            return overrideTrueVal
        }

        return null // No override needed
    }
}
