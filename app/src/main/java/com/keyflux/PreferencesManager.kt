package com.keyflux

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Centralizes all preference management: loading from SharedPreferences,
 * reading typed values, and saving changes.
 */
internal class PreferencesManager(private val plugin: PluginEntry) {

    companion object {
        const val DEFAULT_NUM = 10
        const val DAY: Long = 1000 * 60 * 60 * 24

        // --- Static testable logic ---

        fun resolveClipboardTextSize(prefsMap: Map<String, Any>): Int =
            prefsMap["keyflux_clip_size"] as? Int ?: DEFAULT_NUM

        fun resolveClipboardTextTime(prefsMap: Map<String, Any>): Long {
            val days = prefsMap["keyflux_clip_days"] as? Int ?: 3
            if (days <= 0) return -1L
            return days.toLong() * 1000 * 60 * 60 * 24
        }

        fun detectSensitiveText(text: String): Boolean {
            val trimmed = text.trim()

            // 4 to 8 digits (OTP or PIN)
            if (trimmed.length in 4..8 && trimmed.all { it.isDigit() }) {
                return true
            }

            // Potential Credit Card or Bank Account (12 to 19 digits, ignoring spaces/dashes)
            val digitsOnly = trimmed.replace(Regex("[\\s-]"), "")
            if (digitsOnly.length in 12..19 && digitsOnly.all { it.isDigit() }) {
                return true
            }

            val lower = trimmed.lowercase(Locale.ROOT)
            val sensitiveKeywords = listOf(
                "otp", "verification", "رمز التحقق", "رمز تفعيل",
                "password", "كلمة المرور", "كلمة السر",
                "pin", "token", "secret", "auth", "2fa", "mfa", "passcode",
                "رقم سري", "رمز الدخول", "توثيق", "كود"
            )
            if (sensitiveKeywords.any { lower.contains(it) }) {
                return true
            }

            return false
        }
    }

    internal var prefsMap = HashMap<String, Any>()

    // --- SharedPreferences helpers ---

    internal fun getSafeSharedPreferences(context: Context, name: String): SharedPreferences {
        val safeContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val de = context.createDeviceProtectedStorageContext()
            try {
                de.moveSharedPreferencesFrom(context, name)
            } catch (e: Exception) {
                plugin.log("Failed to migrate old preferences: ${e.message}")
            }
            de
        } else {
            context
        }
        return safeContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    internal fun loadPreferences(context: Context) {
        try {
            val sp = getSafeSharedPreferences(context, "keyflux_prefs")
            val newMap = HashMap<String, Any>()
            for ((key, value) in sp.all) {
                if (key.endsWith("_type")) continue
                if (value != null) {
                    val typedVal: Any = when (value) {
                        is String -> {
                            val decrypted = CryptoHelper.decrypt(value)
                            val type = sp.getString(key + "_type", null) ?: when {
                                key == "keyflux_clip_days" || key == "keyflux_clip_size" -> "int"
                                else -> "boolean"
                            }
                            when (type) {
                                "boolean" -> decrypted.toBoolean()
                                "int" -> decrypted.toIntOrNull() ?: 0
                                "long" -> decrypted.toLongOrNull() ?: 0L
                                "float" -> decrypted.toFloatOrNull() ?: 0f
                                else -> decrypted
                            }
                        }
                        is Boolean -> value
                        is Int -> value
                        is Long -> value
                        is Float -> value
                        else -> value
                    }
                    newMap[key] = typedVal
                }
            }
            try {
                val uri = Uri.parse("content://com.keyflux.provider/settings")
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val keyIndex = cursor.getColumnIndex("key")
                    val valueIndex = cursor.getColumnIndex("value")
                    val typeIndex = cursor.getColumnIndex("type")
                    if (keyIndex != -1 && valueIndex != -1 && typeIndex != -1) {
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(keyIndex) ?: continue
                            val valStr = cursor.getString(valueIndex) ?: ""
                            val type = cursor.getString(typeIndex) ?: "string"
                            val typedVal: Any = when (type) {
                                "boolean" -> valStr.toBoolean()
                                "int" -> valStr.toIntOrNull() ?: 0
                                "long" -> valStr.toLongOrNull() ?: 0L
                                "float" -> valStr.toFloatOrNull() ?: 0f
                                else -> valStr
                            }
                            newMap[key] = typedVal
                            val encryptedVal = CryptoHelper.encrypt(valStr)
                            sp.edit().apply {
                                putString(key, encryptedVal)
                                putString(key + "_type", type)
                                apply()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                plugin.log("ContentProvider settings query skipped: ${e.message}")
            }
            prefsMap = newMap
            plugin.log("Preferences loaded successfully: ${newMap.size} items")
        } catch (t: Throwable) {
            plugin.logAlways("Failed to load preferences: ${t.message}")
        }
    }

    private var contentObserver: android.database.ContentObserver? = null

    internal fun registerSettingsObserver(context: Context) {
        if (contentObserver != null) return
        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            contentObserver = object : android.database.ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    plugin.log("Settings changed in provider, reloading preferences...")
                    CoroutineScope(Dispatchers.Default).launch {
                        loadPreferences(context)
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                Uri.parse("content://com.keyflux.provider/settings"),
                true,
                contentObserver!!
            )
            plugin.log("ContentObserver registered successfully")
        } catch (e: Exception) {
            plugin.log("Failed to register ContentObserver: ${e.message}")
        }
    }

    internal fun savePreferenceToProvider(context: Context, key: String, value: Any) {
        try {
            val sp = getSafeSharedPreferences(context, "keyflux_prefs")
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                else -> "string"
            }
            val encryptedVal = CryptoHelper.encrypt(value.toString())
            sp.edit().apply {
                putString(key, encryptedVal)
                putString(key + "_type", type)
                apply()
            }
            try {
                val uri = Uri.parse("content://com.keyflux.provider/settings")
                val cv = ContentValues().apply {
                    put("key", key)
                    put("value", value.toString())
                    put("type", type)
                }
                context.contentResolver.insert(uri, cv)
            } catch (e: Exception) {
                plugin.log("ContentProvider settings insert skipped: ${e.message}")
            }
            plugin.log("Saved preference: $key = $value")
        } catch (t: Throwable) {
            plugin.logAlways("Failed to save preference: ${t.message}")
        }
    }

    // --- Typed preference getters (delegate to companion) ---

    internal val clipboardTextSize: Int
        get() = resolveClipboardTextSize(prefsMap)

    internal val clipboardTextTime: Long
        get() = resolveClipboardTextTime(prefsMap)

    internal val logSwitch: Boolean
        get() = true

    internal val enableAi: Boolean
        get() = prefsMap["keyflux_enable_ai"] as? Boolean ?: false

    internal val enableGrammar: Boolean
        get() = prefsMap["keyflux_enable_grammar"] as? Boolean ?: false

    internal val enableMultilingual: Boolean
        get() = prefsMap["keyflux_enable_multilingual"] as? Boolean ?: false

    internal val enableFloating: Boolean
        get() = prefsMap["keyflux_enable_floating"] as? Boolean ?: false

    internal val enableEmojiKitchen: Boolean
        get() = prefsMap["keyflux_enable_emoji_kitchen"] as? Boolean ?: false

    internal val enableAccessPoint: Boolean
        get() = prefsMap["keyflux_enable_access_point"] as? Boolean ?: false

    internal val meteredDownloads: Boolean
        get() = prefsMap["keyflux_metered_downloads"] as? Boolean ?: false

    internal val enableAmoled: Boolean
        get() = prefsMap["keyflux_enable_amoled"] as? Boolean ?: false

    internal val forceIncognito: Boolean
        get() = prefsMap["keyflux_force_incognito"] as? Boolean ?: false

    internal val enablePrivacy: Boolean
        get() = prefsMap["keyflux_enable_privacy"] as? Boolean ?: false

    internal val secureClipboard: Boolean
        get() = prefsMap["keyflux_secure_clipboard"] as? Boolean ?: false

    internal val enableInlineSuggestions: Boolean
        get() = prefsMap["keyflux_enable_inline_suggestions"] as? Boolean ?: false

    internal val enableProactiveEmoji: Boolean
        get() = prefsMap["keyflux_enable_proactive_emoji"] as? Boolean ?: false

    internal val enableClipboardChips: Boolean
        get() = prefsMap["keyflux_enable_clipboard_chips"] as? Boolean ?: false

    internal val enableTfliteEngine: Boolean
        get() = prefsMap["keyflux_enable_tflite_engine"] as? Boolean ?: false

    internal val enableFastAccess: Boolean
        get() = prefsMap["keyflux_enable_fast_access"] as? Boolean ?: false

    // --- Sensitive text detection (delegate to companion) ---

    internal fun isSensitiveText(text: String): Boolean = detectSensitiveText(text)
}
