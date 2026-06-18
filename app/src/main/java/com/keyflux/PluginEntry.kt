package com.keyflux

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.content.res.Resources
import android.content.res.TypedArray
import androidx.core.content.edit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import kotlinx.coroutines.*
import java.lang.System.loadLibrary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PluginEntry : IXposedHookLoadPackage {
    companion object {
        const val SP_FILE_NAME = "KeyFluxPrefs"
        const val TAG = "xposed-KeyFlux-hook-"
        const val PACKAGE_NAME = "com.google.android.inputmethod.latin"
        const val DAY: Long = 1000 * 60 * 60 * 24
        const val DEFAULT_NUM = 10
        const val DEFAULT_TIME = DAY * 3
    }

    init {
        try {
            loadLibrary("dexkit")
        } catch (t: Throwable) {
            logAlways("failed to load dexkit library: ${t.message}")
        }
    }

    // Dynamic preference map queried via local SharedPreferences
    internal var prefsMap = HashMap<String, Any>()

    @Volatile
    internal var isCurrentFieldSecure: Boolean = false

    internal fun getSafeSharedPreferences(context: Context, name: String): SharedPreferences {
        val safeContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val de = context.createDeviceProtectedStorageContext()
            try {
                de.moveSharedPreferencesFrom(context, name)
            } catch (e: Exception) {
                log("Failed to migrate old preferences: ${e.message}")
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
                if (value != null) {
                    newMap[key] = value
                }
            }
            prefsMap = newMap
            log("Preferences loaded successfully from local prefs: ${newMap.size} items")
        } catch (t: Throwable) {
            logAlways("Failed to load preferences from local prefs: ${t.message}")
        }
    }

    internal val clipboardTextSize: Int
        get() = prefsMap["clip_size"] as? Int ?: DEFAULT_NUM

    internal val clipboardTextTime: Long
        get() {
            val days = prefsMap["clip_days"] as? Int ?: 3
            if (days <= 0) return -1L
            return days.toLong() * 1000 * 60 * 60 * 24
        }

    internal val logSwitch: Boolean
        get() = true

    internal val enableAi: Boolean
        get() = prefsMap["enable_ai"] as? Boolean ?: false

    internal val enableGrammar: Boolean
        get() = prefsMap["enable_grammar"] as? Boolean ?: false

    internal val enableMultilingual: Boolean
        get() = prefsMap["enable_multilingual"] as? Boolean ?: false

    internal val enableFloating: Boolean
        get() = prefsMap["enable_floating"] as? Boolean ?: false

    internal val enableEmojiKitchen: Boolean
        get() = prefsMap["enable_emoji_kitchen"] as? Boolean ?: false

    internal val enableAccessPoint: Boolean
        get() = prefsMap["enable_access_point"] as? Boolean ?: false

    internal val meteredDownloads: Boolean
        get() = prefsMap["measured_downloads"] as? Boolean ?: prefsMap["metered_downloads"] as? Boolean ?: false

    internal val enableAmoled: Boolean
        get() = prefsMap["enable_amoled"] as? Boolean ?: false

    internal val forceIncognito: Boolean
        get() = prefsMap["force_incognito"] as? Boolean ?: false

    internal val enablePrivacy: Boolean
        get() = prefsMap["enable_privacy"] as? Boolean ?: false

    internal val secureClipboard: Boolean
        get() = prefsMap["secure_clipboard"] as? Boolean ?: false

    internal val enableInlineSuggestions: Boolean
        get() = prefsMap["enable_inline_suggestions"] as? Boolean ?: false

    internal val enableProactiveEmoji: Boolean
        get() = prefsMap["enable_proactive_emoji"] as? Boolean ?: false

    internal val enableClipboardChips: Boolean
        get() = prefsMap["enable_clipboard_chips"] as? Boolean ?: false

    internal val enableTfliteEngine: Boolean
        get() = prefsMap["enable_tflite_engine"] as? Boolean ?: false

    internal val enableFastAccess: Boolean
        get() = prefsMap["enable_fast_access"] as? Boolean ?: false

    internal fun log(str: String) {
        if (logSwitch) {
            XposedBridge.log("$TAG$str")
        }
    }

    internal fun logAlways(str: String) {
        XposedBridge.log("$TAG$str")
    }

    internal var isInitialized = false

    internal fun initializeKeyFlux(context: Context, classLoader: ClassLoader) {
        if (isInitialized) return
        isInitialized = true
        try {
            // Query KeyFlux ContentProvider to populate prefsMap
            loadPreferences(context)

            val sp = getSafeSharedPreferences(context, "keyflux_hook")
            val spKeyMethodReadConfig = "SP_KEY_METHOD_READ_CONFIG"
            val spKeyVersion = "SP_KEY_VERSION"
            val versionCode = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            } catch (e: Exception) {
                log("Failed to get package info for version code: ${e.message}")
                -1
            }
            val gboardVersion = sp.getInt(spKeyVersion, -1)
            val isSameVersion = versionCode == gboardVersion

            val methodReadConfigStr = sp.getString(spKeyMethodReadConfig, null)
            val dexMethodReadConfig: DexMethod? = methodReadConfigStr?.let {
                try {
                    DexMethod(it)
                } catch (e: Exception) {
                    log("Deserializing cached DexMethod failed: $it")
                    null
                }
            }

            if (isSameVersion && dexMethodReadConfig != null) {
                log("Using cached ReadConfig method: ${dexMethodReadConfig.serialize()}")
                FlagsManager.hook(this@PluginEntry, classLoader, dexMethodReadConfig)
            } else {
                CoroutineScope(Dispatchers.Default).launch {
                    log("Resolving ReadConfig method via DexKit (version changed or cache miss)...")
                    val dexBridge = try {
                        DexKitBridge.create(classLoader, true)
                    } catch (t: Throwable) {
                        log("Failed to initialize DexKitBridge: ${t.message}")
                        null
                    }
                    val method = dexBridge?.let { findReadConfigMethod(it) }
                    if (method != null) {
                        sp.edit {
                            putInt(spKeyVersion, versionCode)
                            putString(spKeyMethodReadConfig, method.serialize())
                        }
                        log("ReadConfig method resolved and cached: ${method.serialize()}")
                        FlagsManager.hook(this@PluginEntry, classLoader, method)
                    }
                    try {
                        dexBridge?.close()
                    } catch (t: Throwable) {
                        log("Failed to close DexKitBridge: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            logAlways("Error during initializeKeyFlux: ${t.message}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val classLoader = lpparam.classLoader

        if (packageName != PACKAGE_NAME) {
            return
        }

        // Diagnostics / Load Info
        logAlways("Plugin loaded: package=$packageName, moduleVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        logAlways("Initial config state: logSwitch=$logSwitch, enableAi=$enableAi, clipboardTextSize=$clipboardTextSize")

        // Based on user feedback, ContextWrapper#attachBaseContext (the first solution) actually worked
        // successfully for injection, but failed later at getPreferenceScreen. We will use it as the primary hook.
        try {
            XposedHelpers.findAndHookMethod(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.args[0] as? Context ?: return
                            initializeKeyFlux(context, classLoader)
                        } catch (t: Throwable) {
                            logAlways("Error during ContextWrapper#attachBaseContext hook execution: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logAlways("Failed to hook ContextWrapper#attachBaseContext: ${t.message}")
        }

        ClipboardHooker.hook(this, classLoader)
        PreferenceHooker.hook(this, classLoader)
        ThemeHooker.hook(this, classLoader)
    }

    internal fun overrideColor(res: Resources, id: Int, originalColor: Int): Int? {
        try {
            val entryName = runCatching { res.getResourceEntryName(id) }.getOrNull() ?: return null
            val packageName = runCatching { res.getResourcePackageName(id) }.getOrNull() ?: ""

            if (logSwitch) {
                log("overrideColor check: pkg=$packageName name=$entryName color=0x${Integer.toHexString(originalColor)}")
            }

            // Case 1: System surface container colors (M3 dynamic coloring surfaces)
            if (packageName == "android" && entryName.startsWith("system_surface_container") && !entryName.contains("high")) {
                if (logSwitch) log("Overriding system color $entryName to black")
                return 0xFF000000.toInt()
            }

            // Case 2: Standard Android system background colors
            if (packageName == "android" && (entryName == "colorBackground" || entryName == "background_dark")) {
                if (logSwitch) log("Overriding system background $entryName to black")
                return 0xFF000000.toInt()
            }

            // Case 3: Gboard obfuscated background colors
            if (packageName == PACKAGE_NAME && entryName == "0_resource_name_obfuscated") {
                val hexColor = String.format("#%06X", 0xFFFFFF and originalColor)
                if (hexColor == "#202124" || hexColor == "#131314" || hexColor == "#1F1F1F" || 
                    hexColor == "#1C1B1F" || hexColor == "#171717" || hexColor == "#2C2C2C" || 
                    hexColor == "#303030" || hexColor == "#18191A" || hexColor == "#282A2D") {
                    if (logSwitch) log("Overriding Gboard obfuscated background color (original=$hexColor) to black")
                    return 0xFF000000.toInt()
                }
            }
        } catch (t: Throwable) {
            log("Failed to extract color: ${t.message}")
        }
        return null
    }

    internal fun tryHook(logStr: String, unit: ((name: String) -> Unit)) {
        try {
            unit(logStr)
        } catch (e: NoSuchMethodError) {
            log("NoSuchMethodError--$logStr")
        } catch (e: ClassNotFoundError) {
            log("ClassNotFoundError--$logStr")
        } catch (t: Throwable) {
            log("Unexpected error during hook setup: $logStr: ${t.message}")
        }
    }

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

    internal fun getFlagName(obj: Any): String? {
        try {
            // First try "a" as the default/most common obfuscated name
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

    internal fun isSensitiveText(text: String): Boolean {
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

    internal fun savePreferenceToProvider(context: Context, key: String, value: Any) {
        try {
            val sp = getSafeSharedPreferences(context, "keyflux_prefs")
            sp.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                }
                apply()
            }
            log("Saved preference locally: $key = $value")
        } catch (t: Throwable) {
            logAlways("Failed to save preference locally: ${t.message}")
        }
    }

    internal var preferenceHooksApplied = false

    // Reflection helper wrappers for handling Gboard preference library obfuscation
    internal fun getPreferenceScreen(fragment: Any, classLoader: ClassLoader): Any? {
        val preferenceGroupClass = try {
            XposedHelpers.findClass("androidx.preference.PreferenceGroup", classLoader)
        } catch (t: Throwable) { null }

        try {
            return XposedHelpers.callMethod(fragment, "getPreferenceScreen")
        } catch (t: Throwable) {}
        
        try {
            var clazz: Class<*>? = fragment.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.isEmpty() && !method.returnType.isPrimitive && method.returnType != String::class.java && method.returnType != Context::class.java) {
                        try {
                            method.isAccessible = true
                            val result = method.invoke(fragment)
                            if (result != null) {
                                if (preferenceGroupClass != null && preferenceGroupClass.isInstance(result)) {
                                    logAlways("Found PreferenceScreen dynamically: ${method.name}")
                                    return result
                                }
                                val resClassName = result.javaClass.name.lowercase()
                                if (resClassName.contains("preferencescreen")) {
                                    logAlways("Found PreferenceScreen dynamically (name): ${method.name}")
                                    return result
                                }
                            }
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}
        
        log("getPreferenceScreen completely failed")
        return null
    }

    internal fun getFragmentContext(fragment: Any): Context? {
        try {
            return XposedHelpers.callMethod(fragment, "getContext") as? Context
        } catch (t: Throwable) {}

        try {
            var clazz: Class<*>? = fragment.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.isEmpty() && method.returnType == Context::class.java) {
                        try {
                            method.isAccessible = true
                            val result = method.invoke(fragment) as? Context
                            if (result != null) {
                                logAlways("Found getContext method dynamically: ${method.name}")
                                return result
                            }
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        // Fallback: Check if it's an android.app.Fragment
        if (fragment is android.app.Fragment) return fragment.context
        
        log("getContext completely failed")
        return null
    }

    internal fun findPreference(group: Any, key: String): Any? {
        return try {
            XposedHelpers.callMethod(group, "findPreference", key)
        } catch (t: Throwable) {
            log("findPreference unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated t")
            try {
                XposedHelpers.callMethod(group, "t", key)
            } catch (e: Throwable) {
                try {
                    XposedHelpers.callMethod(group, "n", key)
                } catch (ex: Throwable) {
                    try {
                        XposedHelpers.callMethod(group, "l", key)
                    } catch (ex2: Throwable) {
                        log("findPreference failed for $key: ${ex2.message}")
                        null
                    }
                }
            }
        }
    }

    internal fun addPreference(group: Any, preference: Any): Boolean {
        return try {
            XposedHelpers.callMethod(group, "addPreference", preference)
            true
        } catch (t: Throwable) {
            log("addPreference unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated am")
            try {
                XposedHelpers.callMethod(group, "am", preference)
                true
            } catch (e: Throwable) {
                log("addPreference failed: ${e.message}")
                false
            }
        }
    }

    internal fun getPreferenceCount(group: Any): Int {
        return try {
            XposedHelpers.callMethod(group, "getPreferenceCount") as Int
        } catch (t: Throwable) {
            log("getPreferenceCount unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated k")
            try {
                XposedHelpers.callMethod(group, "k") as Int
            } catch (e: Throwable) {
                log("getPreferenceCount failed: ${e.message}")
                0
            }
        }
    }

    internal fun getPreference(group: Any, index: Int): Any? {
        return try {
            XposedHelpers.callMethod(group, "getPreference", index)
        } catch (t: Throwable) {
            log("getPreference unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated o")
            try {
                XposedHelpers.callMethod(group, "o", index)
            } catch (e: Throwable) {
                log("getPreference failed for index $index: ${e.message}")
                null
            }
        }
    }

    internal fun getPreferenceKey(pref: Any): String? {
        return try {
            XposedHelpers.callMethod(pref, "getKey") as? String
        } catch (t: Throwable) {
            log("getKey unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated r")
            try {
                XposedHelpers.getObjectField(pref, "r") as? String
            } catch (e: Throwable) {
                log("Failed to execute getPreferenceKey: ${e.message}")
                null
            }
        }
    }

    internal fun setPreferenceKey(pref: Any, key: String) {
        try {
            XposedHelpers.callMethod(pref, "setKey", key)
        } catch (t: Throwable) {
            log("setKey unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated O")
            try {
                XposedHelpers.callMethod(pref, "O", key)
            } catch (e: Throwable) {
                try {
                    XposedHelpers.setObjectField(pref, "r", key)
                } catch (ex: Throwable) {
                    log("Failed to set key: ${ex.message}")
                }
            }
        }
    }

    internal fun setPreferenceTitle(pref: Any, title: CharSequence) {
        try {
            XposedHelpers.callMethod(pref, "setTitle", title)
        } catch (t: Throwable) {
            log("setTitle unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated U")
            try {
                XposedHelpers.callMethod(pref, "U", title)
            } catch (e: Throwable) {
                log("Failed to set title: ${e.message}")
            }
        }
    }

    internal fun setPreferenceSummary(pref: Any, summary: CharSequence) {
        try {
            XposedHelpers.callMethod(pref, "setSummary", summary)
        } catch (t: Throwable) {
            log("setSummary unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated n")
            try {
                XposedHelpers.callMethod(pref, "n", summary)
            } catch (e: Throwable) {
                log("Failed to set summary: ${e.message}")
            }
        }
    }

    internal fun setPreferenceIntent(pref: Any, intent: Intent) {
        try {
            XposedHelpers.callMethod(pref, "setIntent", intent)
        } catch (t: Throwable) {
            log("setIntent unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated s")
            try {
                XposedHelpers.setObjectField(pref, "s", intent)
            } catch (e: Throwable) {
                log("Failed to set intent: ${e.message}")
            }
        }
    }

    internal fun setPreferenceChecked(pref: Any, checked: Boolean) {
        try {
            XposedHelpers.callMethod(pref, "setChecked", checked)
        } catch (t: Throwable) {
            log("setChecked unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated k")
            try {
                XposedHelpers.callMethod(pref, "k", checked)
            } catch (e: Throwable) {
                log("Failed to set checked: ${e.message}")
            }
        }
    }

    internal fun setOnPreferenceChangeListener(pref: Any, listener: Any) {
        try {
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", listener)
        } catch (t: Throwable) {
            log("setOnPreferenceChangeListener unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated n")
            try {
                XposedHelpers.setObjectField(pref, "n", listener)
            } catch (e: Throwable) {
                log("Failed to set onPreferenceChangeListener: ${e.message}")
            }
        }
    }

    internal fun setOnPreferenceClickListener(pref: Any, listener: Any) {
        try {
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", listener)
        } catch (t: Throwable) {
            log("setOnPreferenceClickListener unobfuscated failed: ${t.javaClass.simpleName}, trying obfuscated o")
            try {
                XposedHelpers.setObjectField(pref, "o", listener)
            } catch (e: Throwable) {
                log("Failed to set onPreferenceClickListener: ${e.message}")
            }
        }
    }

    internal fun hookPreferenceFragments(classLoader: ClassLoader) {
        if (preferenceHooksApplied) return
        try {
            val prefFragmentClasses = listOf(
                "com.google.android.libraries.inputmethod.preferencewidgets.CommonPreferenceFragment",
                "androidx.preference.PreferenceFragmentCompat",
                "android.support.v7.preference.PreferenceFragmentCompat"
            )
            
            val preferenceGroupClass = try {
                XposedHelpers.findClass("androidx.preference.PreferenceGroup", classLoader)
            } catch (t: Throwable) {
                log("Could not find androidx.preference.PreferenceGroup: ${t.message}")
                null
            }

            for (className in prefFragmentClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)
                    logAlways("Found PreferenceFragment class: $className")
                    val methodsToHook = ArrayList<java.lang.reflect.Method>()
                    
                    try {
                        val m = XposedHelpers.findMethodExact(clazz, "addPreferencesFromResource", Int::class.javaPrimitiveType)
                        methodsToHook.add(m)
                        logAlways("Found addPreferencesFromResource in $className")
                    } catch (t: Throwable) {
                        log("addPreferencesFromResource method not found in $className")
                    }

                    try {
                        val m = XposedHelpers.findMethodExact(clazz, "bb", Int::class.javaPrimitiveType)
                        methodsToHook.add(m)
                        logAlways("Found bb in $className")
                    } catch (t: Throwable) {
                        log("bb method not found in $className")
                    }

                    if (preferenceGroupClass != null) {
                        try {
                            val m = XposedHelpers.findMethodExact(clazz, "bc", Int::class.javaPrimitiveType, preferenceGroupClass)
                            methodsToHook.add(m)
                            logAlways("Found bc in $className")
                        } catch (t: Throwable) {
                            log("bc method not found in $className")
                        }
                    }

                    if (methodsToHook.isEmpty()) {
                        logAlways("No methods found to hook in $className")
                        continue
                    }

                    val methodHook = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val fragment = param.thisObject
                                val fragmentClassName = fragment.javaClass.name
                                log("afterHookedMethod called for $fragmentClassName")
                                
                                val screen = if (param.args.size >= 2 && preferenceGroupClass?.isInstance(param.args[1]) == true) {
                                    log("Using param.args[1] as PreferenceScreen")
                                    param.args[1]
                                } else {
                                    getPreferenceScreen(fragment, classLoader)
                                }
                                
                                log("screen for $fragmentClassName: $screen")
                                val context = getFragmentContext(fragment)
                                log("context for $fragmentClassName: $context")
                                if (screen == null || context == null) return
                                
                                fun logPreferenceGroup(group: Any, indent: String) {
                                    try {
                                        val count = getPreferenceCount(group)
                                        log("${indent}logPreferenceGroup count=$count")
                                        for (i in 0 until count) {
                                            val pref = getPreference(group, i) ?: continue
                                            val key = getPreferenceKey(pref)
                                            val title = try {
                                                XposedHelpers.callMethod(pref, "getTitle")
                                            } catch (t: Throwable) {
                                                try {
                                                    XposedHelpers.callMethod(pref, "v")
                                                } catch (e: Throwable) {
                                                    log("getTitle and fallback 'v' both failed: ${e.message}")
                                                    null
                                                }
                                            }?.toString()
                                            log("${indent}Pref: key=$key, title=$title")
                                            
                                            if (preferenceGroupClass?.isInstance(pref) == true) {
                                                logPreferenceGroup(pref, "$indent  ")
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        log("Error in logPreferenceGroup: ${e.message}")
                                    }
                                }
                                logPreferenceGroup(screen, "  ")
                                
                                val hasLanguages = findPreference(screen, "pref_key_languages") != null
                                val hasTheme = findPreference(screen, "pref_key_theme") != null
                                val isMainSettings = hasLanguages || hasTheme || 
                                                      fragmentClassName.contains("SettingsActivity") || 
                                                      fragmentClassName.contains("PreferenceHeaderFragment") || 
                                                      fragmentClassName.contains("Header")
                                
                                log("isMainSettings=$isMainSettings, hasLanguages=$hasLanguages, hasTheme=$hasTheme")
                                val alreadyInjected = findPreference(screen, "enable_multilingual") != null
                                log("alreadyInjected=$alreadyInjected")
                                
                                if (isMainSettings && !alreadyInjected) {
                                    val preferenceClass = XposedHelpers.findClass("androidx.preference.Preference", classLoader)
                                    val switchPrefClass = try {
                                        XposedHelpers.findClass("androidx.preference.SwitchPreferenceCompat", classLoader)
                                    } catch (e: Throwable) {
                                        XposedHelpers.findClass("androidx.preference.SwitchPreference", classLoader)
                                    }
                                    
                                    val listenerInterface = try {
                                        preferenceClass.getDeclaredField("n").type
                                    } catch (e: Throwable) {
                                        try {
                                            XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceChangeListener", classLoader)
                                        } catch (ex: Throwable) {
                                            XposedHelpers.findClass("defpackage.bzf", classLoader)
                                        }
                                    }
                                    val clickListenerInterface = try {
                                        preferenceClass.getDeclaredField("o").type
                                    } catch (e: Throwable) {
                                        try {
                                            XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceClickListener", classLoader)
                                        } catch (ex: Throwable) {
                                            XposedHelpers.findClass("defpackage.bzg", classLoader)
                                        }
                                    }
                                    
                                    fun createSwitch(key: String, defaultValue: Boolean): Any {
                                        val switchPref = XposedHelpers.newInstance(switchPrefClass, context)
                                        val title = Localization.getString(key + "_title")
                                        val summary = Localization.getString(key + "_summary")
                                        setPreferenceKey(switchPref, key)
                                        setPreferenceTitle(switchPref, title)
                                        setPreferenceSummary(switchPref, summary)
                                        setPreferenceChecked(switchPref, prefsMap[key] as? Boolean ?: defaultValue)
                                        
                                        val listenerProxy = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(listenerInterface)) { _, method, args ->
                                            if (method.declaringClass == Any::class.java) {
                                                when (method.name) {
                                                    "toString" -> "KeyFluxOnPreferenceChangeListenerProxy"
                                                    "hashCode" -> 12345
                                                    "equals" -> args[0] === this
                                                    else -> null
                                                }
                                            } else {
                                                val newValue = if (args.size == 1) args[0] else args[1]
                                                val isChecked = newValue as Boolean
                                                savePreferenceToProvider(context, key, isChecked)
                                                prefsMap[key] = isChecked
                                                true
                                            }
                                        }
                                        setOnPreferenceChangeListener(switchPref, listenerProxy)
                                        return switchPref
                                    }
                                    
                                    fun createInputPref(key: String, defaultValue: String, isNumeric: Boolean): Any {
                                         val inputPref = XposedHelpers.newInstance(preferenceClass, context)
                                         val title = Localization.getString(key + "_title")
                                         val summaryTemplate = Localization.getString(key + "_summary")
                                         setPreferenceKey(inputPref, key)
                                         setPreferenceTitle(inputPref, title)
                                         val getVal = { (prefsMap[key] as? Number)?.toString() ?: (prefsMap[key] as? String) ?: defaultValue }
                                         val getDisplayVal = {
                                             val raw = prefsMap[key] ?: defaultValue
                                             if (key == "clip_days") {
                                                 val daysInt = (raw as? Number)?.toInt() ?: raw.toString().toIntOrNull() ?: 3
                                                 if (daysInt <= 0) {
                                                     Localization.getString("forever")
                                                 } else {
                                                     "$daysInt " + Localization.getString("days")
                                                 }
                                             } else {
                                                 raw.toString()
                                             }
                                         }
                                         setPreferenceSummary(inputPref, try { summaryTemplate.format(getDisplayVal()) } catch (e: Exception) { summaryTemplate })
                                         
                                         val clickListenerProxy = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(clickListenerInterface)) { _, method, args ->
                                             if (method.declaringClass == Any::class.java) {
                                                 when (method.name) {
                                                     "toString" -> "KeyFluxOnPreferenceClickListenerProxy"
                                                     "hashCode" -> 54321
                                                     "equals" -> args[0] === this
                                                     else -> null
                                                 }
                                             } else {
                                                 val activity = context as? android.app.Activity ?: return@newProxyInstance true
                                                 val editText = android.widget.EditText(activity).apply { setText(getVal()); if (isNumeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                                                 android.app.AlertDialog.Builder(activity).setTitle(title).setView(editText)
                                                     .setPositiveButton(android.R.string.ok) { _, _ ->
                                                         val text = editText.text.toString()
                                                         val v: Any = if (isNumeric) text.toIntOrNull() ?: defaultValue.toInt() else text
                                                         savePreferenceToProvider(context, key, v); prefsMap[key] = v
                                                         setPreferenceSummary(inputPref, try { summaryTemplate.format(getDisplayVal()) } catch (e: Exception) { summaryTemplate })
                                                     }.setNegativeButton(android.R.string.cancel, null).show()
                                                 true
                                             }
                                         }
                                         setOnPreferenceClickListener(inputPref, clickListenerProxy)
                                         return inputPref
                                     }

                                     val targetToNewPrefs = mutableMapOf<Any, MutableList<Any>>()
                                     val bottomPrefs = mutableListOf<Any>()



                                     fun insertPreferenceAfter(targetKey: String, newPref: Any): Boolean {
                                         try {
                                             val targetPref = findPreference(screen, targetKey)
                                             if (targetPref != null) {
                                                 try {
                                                     val getIconMethod = targetPref.javaClass.methods.firstOrNull { 
                                                         it.returnType == android.graphics.drawable.Drawable::class.java && it.parameterTypes.isEmpty() 
                                                     }
                                                     if (getIconMethod != null) {
                                                         val icon = getIconMethod.invoke(targetPref) as? android.graphics.drawable.Drawable
                                                         if (icon != null) {
                                                             val setIconMethod = newPref.javaClass.methods.firstOrNull { 
                                                                 it.parameterTypes.size == 1 && it.parameterTypes[0] == android.graphics.drawable.Drawable::class.java 
                                                             }
                                                             setIconMethod?.invoke(newPref, icon)
                                                         }
                                                     }
                                                 } catch (e: Throwable) {
                                                     log("Failed to copy icon: ${e.message}")
                                                 }
                                                 
                                                 addPreference(screen, newPref)
                                                 targetToNewPrefs.getOrPut(targetPref) { mutableListOf() }.add(newPref)
                                                 return true
                                             }
                                         } catch (t: Throwable) {
                                             log("Failed to insert preference after $targetKey: ${t.message}")
                                         }
                                         addPreference(screen, newPref)
                                         bottomPrefs.add(newPref)
                                         return false
                                     }
                                     
                                     insertPreferenceAfter("settings_header_language", createSwitch("enable_multilingual", false))
                                     insertPreferenceAfter("settings_header_preferences", createSwitch("metered_downloads", false))
                                     insertPreferenceAfter("settings_header_preferences", createSwitch("enable_floating", false))
                                     insertPreferenceAfter("settings_header_theme", createSwitch("enable_access_point", false))
                                     insertPreferenceAfter("settings_header_theme", createSwitch("enable_amoled", false))
                                     insertPreferenceAfter("settings_header_correction", createSwitch("enable_grammar", false))
                                     insertPreferenceAfter("settings_header_correction", createSwitch("enable_ai", false))
                                     insertPreferenceAfter("settings_header_clipboard", createInputPref("clip_days", "3", true))
                                     insertPreferenceAfter("settings_header_clipboard", createInputPref("clip_size", "10", true))
                                     insertPreferenceAfter("settings_header_clipboard", createSwitch("secure_clipboard", false))
                                     insertPreferenceAfter("settings_header_expression", createSwitch("enable_emoji_kitchen", false))
                                     insertPreferenceAfter("settings_header_privacy", createSwitch("force_incognito", false))
                                     insertPreferenceAfter("settings_header_privacy", createSwitch("enable_privacy", false))
                                     
                                     val forceStopPref = XposedHelpers.newInstance(preferenceClass, context)
                                     setPreferenceKey(forceStopPref, "keyflux_force_stop_btn")
                                     setPreferenceTitle(forceStopPref, Localization.getString("force_stop_title"))
                                     setPreferenceSummary(forceStopPref, Localization.getString("force_stop_summary"))
                                     val forceStopClickProxy = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(clickListenerInterface)) { _, method, args ->
                                         if (method.declaringClass == Any::class.java) {
                                             when (method.name) {
                                                 "toString" -> "KeyFluxForceStopClickListenerProxy"
                                                 "hashCode" -> 99999
                                                 "equals" -> args[0] === this
                                                 else -> null
                                             }
                                         } else {
                                             try { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${PluginEntry.PACKAGE_NAME}"))) } catch (e: Exception) { log("Failed to open app settings: ${e.message}") }
                                             true
                                         }
                                     }
                                     setOnPreferenceClickListener(forceStopPref, forceStopClickProxy)
                                     
                                     insertPreferenceAfter("settings_header_help_and_feedback", forceStopPref)
                                     insertPreferenceAfter("settings_header_help_and_feedback", createSwitch("log_switch", false))
                                     
                                     // Experimental Features
                                     try {
                                         val prefCategoryClass = try {
                                             XposedHelpers.findClass("androidx.preference.PreferenceCategory", classLoader)
                                         } catch(e: Throwable) {
                                             XposedHelpers.findClass("android.support.v7.preference.PreferenceCategory", classLoader)
                                         }
                                         val expCategory = XposedHelpers.newInstance(prefCategoryClass, context)
                                         setPreferenceKey(expCategory, "settings_header_experimental")
                                         setPreferenceTitle(expCategory, Localization.getString("settings_header_experimental_title"))
                                         insertPreferenceAfter("settings_header_help_and_feedback", expCategory)
                                         insertPreferenceAfter("settings_header_help_and_feedback", createSwitch("enable_inline_suggestions", false))
                                         insertPreferenceAfter("settings_header_help_and_feedback", createSwitch("enable_proactive_emoji", false))
                                         insertPreferenceAfter("settings_header_help_and_feedback", createSwitch("enable_clipboard_chips", false))
                                         insertPreferenceAfter("settings_header_help_and_feedback", createSwitch("enable_tflite_engine", false))
                                         insertPreferenceAfter("settings_header_help_and_feedback", createSwitch("enable_fast_access", false))
                                     } catch (t: Throwable) {
                                         log("Failed to create experimental category: ${t.message}")
                                     }

                                     // Now reorder
                                     try {
                                         var currentList: MutableList<Any>? = null
                                         val count = getPreferenceCount(screen)
                                         
                                         var currentClass: Class<*>? = screen.javaClass
                                         while (currentClass != null) {
                                             for (field in currentClass.declaredFields) {
                                                 if (java.util.List::class.java.isAssignableFrom(field.type)) {
                                                     field.isAccessible = true
                                                     val list = field.get(screen) as? MutableList<Any>
                                                     if (list != null && list.size == count && count > 0) {
                                                         currentList = list
                                                         break
                                                     }
                                                 }
                                             }
                                             if (currentList != null) break
                                             currentClass = currentClass.superclass
                                         }
                                         
                                         if (currentList != null) {
                                             val newPrefsSet = targetToNewPrefs.values.flatten().toSet() + bottomPrefs.toSet()
                                             val originalPrefs = currentList.filter { it !in newPrefsSet }
                                             
                                             val orderedList = mutableListOf<Any>()
                                             for (pref in originalPrefs) {
                                                 orderedList.add(pref)
                                                 targetToNewPrefs[pref]?.let { orderedList.addAll(it) }
                                             }
                                             orderedList.addAll(bottomPrefs)
                                             
                                             currentList.clear()
                                             currentList.addAll(orderedList)
                                             
                                             // Discover mOrder field dynamically by comparing two native preferences
                                             var mOrderField: java.lang.reflect.Field? = null
                                             try {
                                                 val pref0 = originalPrefs.getOrNull(0)
                                                 val pref1 = originalPrefs.getOrNull(1)
                                                 if (pref0 != null && pref1 != null) {
                                                     var clazz: Class<*>? = pref0.javaClass
                                                     while (clazz != null) {
                                                         for (field in clazz.declaredFields) {
                                                             if (field.type == Int::class.javaPrimitiveType) {
                                                                 field.isAccessible = true
                                                                 val val0 = field.getInt(pref0)
                                                                 val val1 = field.getInt(pref1)
                                                                 // mOrder typically increments. Layout IDs are huge.
                                                                 if (val0 < 1000 && val1 < 1000 && val1 > val0) {
                                                                     mOrderField = field
                                                                     break
                                                                 }
                                                             }
                                                         }
                                                         if (mOrderField != null) break
                                                         clazz = clazz.superclass
                                                     }
                                                 }
                                             } catch (e: Throwable) {
                                                 log("Failed to discover mOrder field: ${e.message}")
                                             }
                                             
                                             // Update mOrder to ensure adapter respects the visual order
                                             for ((index, pref) in orderedList.withIndex()) {
                                                 try {
                                                     XposedHelpers.callMethod(pref, "setOrder", index)
                                                 } catch (e: Throwable) {
                                                     mOrderField?.setInt(pref, index)
                                                 }
                                             }
                                         }
                                     } catch (e: Throwable) {
                                         log("Reorder failed: ${e.message}")
                                     }
                                 }
                            } catch (t: Throwable) {
                                log("Error injecting KeyFlux settings entry: ${t.message}")
                            }
                        }
                    }

                    for (method in methodsToHook) {
                        try {
                            XposedBridge.hookMethod(method, methodHook)
                            log("Hooked method ${method.name} in $className")
                            preferenceHooksApplied = true
                        } catch (t: Throwable) {
                            log("Failed to hook method ${method.name} in $className: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    log("Failed to hook $className: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            log("Failed to hook PreferenceFragmentCompat: ${t.message}")
        }
    }

}

object Localization {
    internal val translations = mapOf(
        "ar" to mapOf(
            "category_title" to "إعدادات KeyFlux",
            "enable_ai_title" to "ميزات الكتابة الذكية",
            "enable_ai_summary" to "تفعيل الكتابة الذكية والاقتراحات التلقائية",
            "enable_grammar_title" to "التدقيق اللغوي",
            "enable_grammar_summary" to "تحديد وتصحيح الأخطاء اللغوية والإملائية",
            "enable_multilingual_title" to "الكتابة متعددة اللغات",
            "enable_multilingual_summary" to "الكتابة بلغات متعددة تلقائياً في نفس الوقت",
            "enable_floating_title" to "تخطيط الكيبورد العائم",
            "enable_floating_summary" to "تفعيل وضع الكيبورد العائم تلقائياً في الوضع الأفقي أو النوافذ المتعددة",
            "enable_emoji_kitchen_title" to "مطبخ الإيموجي",
            "enable_emoji_kitchen_summary" to "تصفح ودمج ملصقات الإيموجي للحصول على ملصقات جديدة",
            "enable_access_point_title" to "إعادة تصميم قائمة الميزات",
            "enable_access_point_summary" to "تفعيل المظهر الحريري، خط النظام، والسمات المخصصة",
            "enable_amoled_title" to "مظهر AMOLED الأسود",
            "enable_amoled_summary" to "تفعيل الخلفية السوداء الداكنة بالكامل لتوفير الطاقة",
            "metered_downloads_title" to "التنزيل عبر شبكة الهاتف",
            "metered_downloads_summary" to "السماح بتنزيل حزم اللغات والصوت عبر بيانات الهاتف",
            "force_incognito_title" to "التصفح المتخفي الإجباري",
            "force_incognito_summary" to "إجبار الكيبورد على وضع التصفح المتخفي لمنع حفظ ما تكتبه",
            "enable_privacy_title" to "حظر التتبع والتحليلات",
            "enable_privacy_summary" to "تعطيل إرسال إحصائيات الكتابة والسرعة والتدريب المحلي لـ Google",
            "secure_clipboard_title" to "حماية نصوص الحافظة",
            "secure_clipboard_summary" to "منع حفظ كلمات المرور ورموز التحقق (OTP) في سجل الحافظة",
            "clip_size_title" to "عدد نصوص الحافظة",
            "clip_size_summary" to "الحالي: %s",
            "clip_days_title" to "مدة الاحتفاظ بنصوص الحافظة",
            "clip_days_summary" to "أيام الاحتفاظ بنصوص الحافظة. أدخل 0 للابد (الحالي: %s)",
            "log_switch_title" to "تسجيل السجلات",
            "log_switch_summary" to "كتابة سجلات التصحيح إلى Xposed",
            "force_stop_title" to "تطبيق التغييرات (إيقاف إجباري)",
            "force_stop_summary" to "إعادة تشغيل الكيبورد لتطبيق الإعدادات الجديدة",
            "forever" to "للأبد",
            "days" to "أيام",
            "settings_header_experimental_title" to "الميزات التجريبية والذكاء الاصطناعي",
            "enable_inline_suggestions_title" to "اقتراحات النصوص الذكية المدمجة",
            "enable_inline_suggestions_summary" to "اقتراح جمل كاملة أو كلمات متعددة وتكملتها بسحبة واحدة",
            "enable_proactive_emoji_title" to "مطبخ الإيموجي الاستباقي",
            "enable_proactive_emoji_summary" to "اقتراحات تفاعلية ومتحركة لدمج الإيموجيات أثناء الكتابة",
            "enable_clipboard_chips_title" to "أزرار الحافظة السريعة",
            "enable_clipboard_chips_summary" to "إظهار أزرار ذكية ولصق سريع واستخراج بيانات عند نسخ أي نص",
            "enable_tflite_engine_title" to "محرك الذكاء الاصطناعي TFLite",
            "enable_tflite_engine_summary" to "تشغيل محرك الشبكات العصبية محلياً لتوقع كلمات وإيموجي بدقة فائقة",
            "enable_fast_access_title" to "شريط الوصول السريع الجديد",
            "enable_fast_access_summary" to "تفعيل شريط الوصول السريع وخيارات التصميم الحديثة (Google Sans)"
        ),
        "fa" to mapOf(
            "category_title" to "تنظیمات KeyFlux",
            "enable_ai_title" to "ویژگی‌های تایپ هوشمند",
            "enable_ai_summary" to "فعال‌سازی تکمیل خودکار هوشمند و پیشنهادات خطی",
            "enable_grammar_title" to "بررسی دستور زبان",
            "enable_grammar_summary" to "برجسته‌سازی و اصلاح خطاهای دستوری",
            "enable_multilingual_title" to "تایپ چندزبانه",
            "enable_multilingual_summary" to "تایپ همزمان به چندین زبان",
            "enable_floating_title" to "طرح‌بندی صفحه‌کلید شناور",
            "enable_floating_summary" to "شناور شدن خودکار صفحه‌کلید در حالت افقی یا چندپنجره‌ای",
            "enable_emoji_kitchen_title" to "آشپزخانه ایموجی",
            "enable_emoji_kitchen_summary" to "مرور و ترکیب استیکرها و ایموجی‌ها",
            "enable_access_point_title" to "طراحی مجدد منوی دسترسی سریع",
            "enable_access_point_summary" to "تم ابریشمی، فونت سیستم، تم‌های سفارشی",
            "enable_amoled_title" to "تم مشکی AMOLED",
            "enable_amoled_summary" to "خلف‌زمینه مشکی خالص",
            "metered_downloads_title" to "دانلود با اینترنت محدود",
            "metered_downloads_summary" to "اجازه دانلود بسته‌های زبان و گفتار با داده تلفن همراه",
            "force_incognito_title" to "اجبار حالت ناشناس",
            "force_incognito_summary" to "اجبار صفحه‌کلید به حالت ناشناس برای جلوگیری از ذخیره تاریخچه تایپ",
            "enable_privacy_title" to "مسدود کردن تله‌متری و آنالیزها",
            "enable_privacy_summary" to "غیرفعال‌سازی آمار تایپ، گزارش‌های سرعت و به‌روزرسانی‌های یادگیری محلی",
            "secure_clipboard_title" to "محافظت از حافظه موقت",
            "secure_clipboard_summary" to "جلوگیری از ذخیره رمزهای عبور و رمزهای یک‌بار مصرف (OTP) در تاریخچه حافظه موقت",
            "clip_size_title" to "اندازه تاریخچه حافظه موقت",
            "clip_size_summary" to "فعلی: %s",
            "clip_days_title" to "زمان انقضای حافظه موقت",
            "clip_days_summary" to "تعداد روزهای نگهداری. برای همیشه 0 را وارد کنید (فعلی: %s)",
            "log_switch_title" to "ثبت گزارش‌ها",
            "log_switch_summary" to "نوشتن گزارش‌های عیب‌یابی در Xposed",
            "force_stop_title" to "اعمال تغییرات (توقف اجباری)",
            "force_stop_summary" to "راه‌اندازی مجدد صفحه‌کلید برای اعمال تنظیمات",
            "forever" to "برای همیشه",
            "days" to "روز"
        ),
        "ur" to mapOf(
            "category_title" to "کی فلکس ترتیبات",
            "enable_ai_title" to "سمارٹ ٹائپنگ کی خصوصیات",
            "enable_ai_summary" to "سمارٹ کمپوز اور ان لائن تجاویز فعال کریں",
            "enable_grammar_title" to "گرامر کی جانچ",
            "enable_grammar_summary" to "گرامر کی غلطیوں کو نشان زد اور درست کریں",
            "enable_multilingual_title" to "کثیر لسانی ٹائپنگ",
            "enable_multilingual_summary" to "ایک ہی وقت میں متعدد زبانوں میں ٹائپ کریں",
            "enable_floating_title" to "فلوٹنگ کی بورڈ لے آؤٹ",
            "enable_floating_summary" to "لینڈ اسکیپ یا ملٹی ونڈو موڈ میں خودکار فلوٹنگ کی بورڈ",
            "enable_emoji_kitchen_title" to "ایموجی کچن",
            "enable_emoji_kitchen_summary" to "اسٹیکرز اور ایموجیز کو براؤز اور یکجا کریں",
            "enable_access_point_title" to "رسائی پوائنٹ مینو کی دوبارہ ترتیب",
            "enable_access_point_summary" to "سلک تھیم، سسٹم فونٹ، اور اپنی مرضی کے تھیمز",
            "enable_amoled_title" to "AMOLED بلیک تھیم",
            "enable_amoled_summary" to "خالص سیاہ پس منظر والی تھیم",
            "metered_downloads_title" to "محدود نیٹ ورک ڈاؤن لوڈز",
            "metered_downloads_summary" to "موبائل ڈیٹا پر زبان اور اسپیچ پیک ڈاؤن لوڈ کی اجازت دیں",
            "force_incognito_title" to "لازمی انکوگنیٹو موڈ",
            "force_incognito_summary" to "ٹائپنگ ہسٹری کو محفوظ ہونے سے روکنے کے لیے کی بورڈ کو انکوگنیٹو موڈ پر مجبور کریں",
            "enable_privacy_title" to "ٹیلی میٹری اور تجزیات بلاک کریں",
            "enable_privacy_summary" to "ٹائپنگ کے اعداد و شمار اور گوگل کو رپورٹ بھیجنا غیر فعال کریں",
            "secure_clipboard_title" to "کلپ بورڈ تحفظ",
            "secure_clipboard_summary" to "کلپ بورڈ ہسٹری میں پاس ورڈز اور یک وقتی پاس ورڈ (OTP) کو محفوظ ہونے سے روکیں",
            "clip_size_title" to "کلپ بورڈ ہسٹری کا سائز",
            "clip_size_summary" to "موجودہ: %s",
            "clip_days_title" to "کلپ بورڈ کی میعاد",
            "clip_days_summary" to "کلپ بورڈ آئٹمز رکھنے کے دن۔ ہمیشہ کے لیے 0 درج کریں (موجودہ: %s)",
            "log_switch_title" to "لاگ سوئچ",
            "log_switch_summary" to "Xposed پر ڈیبگ لاگز لکھیں",
            "force_stop_title" to "تبدیلیاں لاگو کریں (زبردستی روکیں)",
            "force_stop_summary" to "ترتیبات لاگو کرنے کے لیے کی بورڈ کو دوبارہ شروع کریں",
            "forever" to "ہمیشہ کے لیے",
            "days" to "دن"
        ),
        "es" to mapOf(
            "category_title" to "Ajustes de KeyFlux",
            "enable_ai_title" to "Funciones de escritura inteligente",
            "enable_ai_summary" to "Activar redacción inteligente y sugerencias integradas",
            "enable_grammar_title" to "Corrector gramatical",
            "enable_grammar_summary" to "Resaltar y corregir errores gramaticales",
            "enable_multilingual_title" to "Escritura multilingüe",
            "enable_multilingual_summary" to "Escribir en varios idiomas simultáneamente",
            "enable_floating_title" to "Teclado flotante",
            "enable_floating_summary" to "Teclado flotante automático en modo horizontal o multipantalla",
            "enable_emoji_kitchen_title" to "Cocina de Emojis",
            "enable_emoji_kitchen_summary" to "Explorar y combinar pegatinas y emojis",
            "enable_access_point_title" to "Rediseñar menú de puntos de acceso",
            "enable_access_point_summary" to "Tema de seda, fuente del sistema, temas personalizados",
            "enable_amoled_title" to "Tema negro AMOLED",
            "enable_amoled_summary" to "Fondo negro puro",
            "metered_downloads_title" to "Descargas en redes medidas",
            "metered_downloads_summary" to "Permitir descargas de paquetes de idiomas y voz mediante datos móviles",
            "force_incognito_title" to "Forzar modo incógnito",
            "force_incognito_summary" to "Forzar a Gboard en modo incógnito para evitar guardar el historial de escritura",
            "enable_privacy_title" to "Bloquear telemetría y análisis",
            "enable_privacy_summary" to "Desactivar estadísticas de escritura, registros de velocidad y entrenamiento local",
            "secure_clipboard_title" to "Protección del portapapeles",
            "secure_clipboard_summary" to "Evitar guardar contraseñas y códigos de verificación (OTP) en el historial",
            "clip_size_title" to "Tamaño del historial",
            "clip_size_summary" to "Actual: %s",
            "clip_days_title" to "Expiración del portapapeles",
            "clip_days_summary" to "Días para guardar los elementos. Escribe 0 para siempre (Actual: %s)",
            "log_switch_title" to "Registro de depuración",
            "log_switch_summary" to "Escribir registros de depuración en Xposed",
            "force_stop_title" to "Aplicar cambios (Forzar detención)",
            "force_stop_summary" to "Reiniciar el teclado para aplicar la configuración",
            "forever" to "Para siempre",
            "days" to "días"
        ),
        "fr" to mapOf(
            "category_title" to "Paramètres KeyFlux",
            "enable_ai_title" to "Saisie intelligente",
            "enable_ai_summary" to "Activer la rédaction intelligente et les suggestions en ligne",
            "enable_grammar_title" to "Correcteur grammatical",
            "enable_grammar_summary" to "Surligner et corriger les erreurs de grammaire",
            "enable_multilingual_title" to "Saisie multilingue",
            "enable_multilingual_summary" to "Saisir dans plusieurs langues simultanément",
            "enable_floating_title" to "Clavier flottant",
            "enable_floating_summary" to "Clavier flottant automatique en mode paysage ou multi-fenêtres",
            "enable_emoji_kitchen_title" to "Emoji Kitchen",
            "enable_emoji_kitchen_summary" to "Parcourir et combiner des autocollants et des emojis",
            "enable_access_point_title" to "Redessiner le menu",
            "enable_access_point_summary" to "Thème en soie, police système, thèmes personnalisés",
            "enable_amoled_title" to "Thème noir AMOLED",
            "enable_amoled_summary" to "Arrière-plan noir pur",
            "metered_downloads_title" to "Téléchargements limités",
            "metered_downloads_summary" to "Autoriser le téléchargement sur données mobiles",
            "force_incognito_title" to "Forcer le mode navigation privée",
            "force_incognito_summary" to "Forcer Gboard en navigation privée pour éviter d'enregistrer l'historique",
            "enable_privacy_title" to "Bloquer la télémétrie et analyses",
            "enable_privacy_summary" to "Désactiver les statistiques de saisie, les rapports de vitesse et l'apprentissage local",
            "secure_clipboard_title" to "Protection du presse-papiers",
            "secure_clipboard_summary" to "Empêcher l'enregistrement des mots de passe et codes de vérification (OTP) dans l'historique",
            "clip_size_title" to "Taille de l'historique",
            "clip_size_summary" to "Actuel : %s",
            "clip_days_title" to "Expiration du presse-papiers",
            "clip_days_summary" to "Jours de conservation. Entrez 0 pour toujours (Actuel : %s)",
            "log_switch_title" to "Journaux de débogage",
            "log_switch_summary" to "Écrire les journaux de débogage dans Xposed",
            "force_stop_title" to "Appliquer (Forcer l'arrêt)",
            "force_stop_summary" to "Redémarrer le clavier pour appliquer les paramètres",
            "forever" to "Pour toujours",
            "days" to "jours"
        ),
        "de" to mapOf(
            "category_title" to "KeyFlux-Einstellungen",
            "enable_ai_title" to "Intelligentes Tippen",
            "enable_ai_summary" to "Intelligente Texterstellung und Inline-Vorschläge aktivieren",
            "enable_grammar_title" to "Grammatikprüfung",
            "enable_grammar_summary" to "Grammatikfehler hervorheben und korrigieren",
            "enable_multilingual_title" to "Mehrsprachiges Tippen",
            "enable_multilingual_summary" to "Gleichzeitig in mehreren Sprachen tippen",
            "enable_floating_title" to "Schwebende Tastatur",
            "enable_floating_summary" to "Tastatur automatisch schweben lassen",
            "enable_emoji_kitchen_title" to "Emoji-Küche",
            "enable_emoji_kitchen_summary" to "Sticker und Emojis kombinieren",
            "enable_access_point_title" to "Schnellzugriffsmenü ändern",
            "enable_access_point_summary" to "Seiden-Design, Systemschriftart, benutzerdefinierte Designs",
            "enable_amoled_title" to "AMOLED-Schwarz-Design",
            "enable_amoled_summary" to "Tiefschwarzer Hintergrund",
            "metered_downloads_title" to "Getaktete Downloads",
            "metered_downloads_summary" to "Sprachpaket-Downloads über mobile Daten erlauben",
            "force_incognito_title" to "Inkognito-Modus erzwingen",
            "force_incognito_summary" to "Gboard im Inkognito-Modus betreiben, um den Tippverlauf nicht zu speichern",
            "enable_privacy_title" to "Telemetrie & Analyse blockieren",
            "enable_privacy_summary" to "Tippstatistiken, Geschwindigkeitsberichte und lokales Training deaktivieren",
            "secure_clipboard_title" to "Zwischenablage-Schutz",
            "secure_clipboard_summary" to "Speichern von Passwörtern und Einmalpasswörtern (OTP) im Verlauf verhindern",
            "clip_size_title" to "Zwischenablage-Größe",
            "clip_size_summary" to "Aktuell: %s",
            "clip_days_title" to "Ablaufzeit",
            "clip_days_summary" to "Tage zur Aufbewahrung. 0 eingeben für immer (Aktuell: %s)",
            "log_switch_title" to "Debug-Protokolle",
            "log_switch_summary" to "Debug-Protokolle in Xposed schreiben",
            "force_stop_title" to "Übernehmen (Stopp erzwingen)",
            "force_stop_summary" to "Tastatur neu starten, um die Einstellungen zu übernehmen",
            "forever" to "Für immer",
            "days" to "Tage"
        ),
        "ru" to mapOf(
            "category_title" to "Настройки KeyFlux",
            "enable_ai_title" to "Умный ввод",
            "enable_ai_summary" to "Включить автозаполнение и встроенные подсказки",
            "enable_grammar_title" to "Проверка грамматики",
            "enable_grammar_summary" to "Выделение и исправление грамматических ошибок",
            "enable_multilingual_title" to "Многоязычный ввод",
            "enable_multilingual_summary" to "Ввод на нескольких языках одновременно",
            "enable_floating_title" to "Плавающая клавиатура",
            "enable_floating_summary" to "Плавающий режим в ландшафтном или многооконном режиме",
            "enable_emoji_kitchen_title" to "Кухня эмодзи",
            "enable_emoji_kitchen_summary" to "Просмотр и комбинирование стикеров и эмодзи",
            "enable_access_point_title" to "Новое меню быстрого доступа",
            "enable_access_point_summary" to "Шёлковая тема, системный шрифт, пользовательские темы",
            "enable_amoled_title" to "Тёмная тема AMOLED",
            "enable_amoled_summary" to "Чисто черный фон",
            "metered_downloads_title" to "Мобильные загрузки",
            "metered_downloads_summary" to "Разрешить загрузку языковых пакетов через мобильную сеть",
            "force_incognito_title" to "Принудительный режим инкогнито",
            "force_incognito_summary" to "Принудительно запускать Gboard в режиме инкогнито, чтобы не сохранять историю ввода",
            "enable_privacy_title" to "Блокировка телеметрии и аналитики",
            "enable_privacy_summary" to "Отключить статистику ввода, отчеты о скорости и локальное обучение",
            "secure_clipboard_title" to "Защита буфера обмена",
            "secure_clipboard_summary" to "Запретить сохранение паролей и одноразовых кодов (OTP) в истории буфера",
            "clip_size_title" to "Размер буфера обмена",
            "clip_size_summary" to "Текущий размер: %s",
            "clip_days_title" to "Время хранения",
            "clip_days_summary" to "Срок хранения в днях. Введите 0 для вечного хранения (Текущее: %s)",
            "log_switch_title" to "Журнал отладки",
            "log_switch_summary" to "Записывать отчеты об ошибках в Xposed",
            "force_stop_title" to "Применить (Принудительно)",
            "force_stop_summary" to "Перезапустите клавиатуру для применения настроек",
            "forever" to "Навсегда",
            "days" to "дн."
        ),
        "tr" to mapOf(
            "category_title" to "KeyFlux Ayarları",
            "enable_ai_title" to "Akıllı Yazma Özellikleri",
            "enable_ai_summary" to "Akıllı Yazma ve satır içi önerileri etkinleştir",
            "enable_grammar_title" to "Dilbilgisi Denetleyicisi",
            "enable_grammar_summary" to "Dilbilgisi hatalarını vurgula ve düzelt",
            "enable_multilingual_title" to "Çok Dilli Yazma",
            "enable_multilingual_summary" to "Aynı onda birden fazla dilde yazın",
            "enable_floating_title" to "Yüzen Klavye Düzeni",
            "enable_floating_summary" to "Yatay veya çoklu pencere modunda klavyeyi otomatik yüzdür",
            "enable_emoji_kitchen_title" to "Emoji Mutfağı",
            "enable_emoji_kitchen_summary" to "Çıkartmaları ve emojileri gözden geçir ve birleştir",
            "enable_access_point_title" to "Erişim Noktası Menüsünü Yeniden Tasarla",
            "enable_access_point_summary" to "İpek teması, sistem yazı tipi, özel temalar",
            "enable_amoled_title" to "AMOLED Siyah Teması",
            "enable_amoled_summary" to "Saf siyah arka plan teması",
            "metered_downloads_title" to "Tarifeli Ağ İndirmeleri",
            "metered_downloads_summary" to "Mobil veri üzerinden indirmelere izin ver",
            "force_incognito_title" to "Gizli Modu Zorla",
            "force_incognito_summary" to "Yazma geçmişini kaydetmemek için Gboard'u gizli modda çalışmaya zorla",
            "enable_privacy_title" to "Telemetri ve Analizleri Engelle",
            "enable_privacy_summary" to "Yazma istatistiklerini, hız günlüklerini ve yerel eğitimi devre dışı bırak",
            "secure_clipboard_title" to "Pano Koruması",
            "secure_clipboard_summary" to "Şifrelerin ve tek kullanımlık şifrelerin (OTP) panoda saklanmasını engelle",
            "clip_size_title" to "Pano Geçmişi Boyutu",
            "clip_size_summary" to "Mevcut: %s",
            "clip_days_title" to "Pano Geçmişi Süresi",
            "clip_days_summary" to "Pano öğelerini saklama gün sayısı. Sonsuz için 0 girin (Mevcut: %s)",
            "log_switch_title" to "Hata Günlüğü Anahtarı",
            "log_switch_summary" to "Hata ayıklama günlüklerini Xposed'a yaz",
            "force_stop_title" to "Değişiklikleri Uygula (Zorla Durdur)",
            "force_stop_summary" to "Ayarları uygulamak için klavyeyi yeniden başlatın",
            "forever" to "Sonsuza kadar",
            "days" to "gün"
        ),
        "en" to mapOf(
            "category_title" to "KeyFlux Settings",
            "enable_ai_title" to "Smart Typing Features",
            "enable_ai_summary" to "Enable Smart Compose, inline suggestions, etc.",
            "enable_grammar_title" to "Grammar Checker",
            "enable_grammar_summary" to "Highlight and correct grammatical errors",
            "enable_multilingual_title" to "Multilingual Typing",
            "enable_multilingual_summary" to "Type in multiple languages simultaneously",
            "enable_floating_title" to "Floating Keyboard Layout",
            "enable_floating_summary" to "Auto-float keyboard in landscape or multi-window mode",
            "enable_emoji_kitchen_title" to "Emoji Kitchen",
            "enable_emoji_kitchen_summary" to "Browse and combine stickers and emojis",
            "enable_access_point_title" to "Redesign Access Point Menu",
            "enable_access_point_summary" to "Silk theme, system font, custom themes",
            "enable_amoled_title" to "AMOLED Black Theme",
            "enable_amoled_summary" to "Pure black background theme",
            "metered_downloads_title" to "Metered Network Downloads",
            "metered_downloads_summary" to "Allow language & speech pack downloads over mobile data",
            "force_incognito_title" to "Force Incognito Mode",
            "force_incognito_summary" to "Force Gboard into incognito mode to prevent saving typing history",
            "enable_privacy_title" to "Block Telemetry & Analytics",
            "enable_privacy_summary" to "Disable typing statistics, speed logs, and local training updates",
            "secure_clipboard_title" to "Clipboard Protection",
            "secure_clipboard_summary" to "Prevent saving passwords and one-time passwords (OTP) in clipboard history",
            "clip_size_title" to "Clipboard History Size",
            "clip_size_summary" to "Current: %s",
            "clip_days_title" to "Clipboard Expire Time",
            "clip_days_summary" to "Days to keep clipboard items. Enter 0 for forever (Current: %s)",
            "log_switch_title" to "Log Switch",
            "log_switch_summary" to "Write debug logs to Xposed",
            "force_stop_title" to "Apply Changes (Force Stop)",
            "force_stop_summary" to "Restart Gboard to apply settings",
            "forever" to "Forever",
            "days" to "days",
            "settings_header_experimental_title" to "AI & Experimental Features",
            "enable_inline_suggestions_title" to "Inline Multiword Suggestions",
            "enable_inline_suggestions_summary" to "Smart Compose-like suggestions for entire sentences inline",
            "enable_proactive_emoji_title" to "Proactive Emoji Kitchen",
            "enable_proactive_emoji_summary" to "Interactive and animated emoji mashup suggestions while typing",
            "enable_clipboard_chips_title" to "Clipboard Action Chips",
            "enable_clipboard_chips_summary" to "Smart action buttons for quick paste and data extraction on copy",
            "enable_tflite_engine_title" to "TFLite AI Engine",
            "enable_tflite_engine_summary" to "Run local neural network for highly accurate next-word and emoji prediction",
            "enable_fast_access_title" to "New Fast Access Bar",
            "enable_fast_access_summary" to "Enable the new fast access toolbar and Google Sans redesign"
        )
    )

    fun getString(key: String): String {
        val lang = Locale.getDefault().language
        val map = translations[lang] ?: translations["en"]!!
        return map[key] ?: translations["en"]!![key] ?: key
    }
}
