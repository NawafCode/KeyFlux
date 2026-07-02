package com.keyflux

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.core.content.edit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import kotlinx.coroutines.*
import java.lang.System.loadLibrary
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main Xposed entry point for KeyFlux.
 * Orchestrates initialization, context hooks, and parallel hooker loading.
 * Delegates to PreferencesManager, FlagsOverrideManager, and PreferenceUIHelper.
 */
class PluginEntry : IXposedHookLoadPackage {
    companion object {
        const val SP_FILE_NAME = "KeyFluxPrefs"
        const val TAG = "xposed-KeyFlux-hook-"
        const val PACKAGE_NAME = "com.google.android.inputmethod.latin"
        val isInitialized = AtomicBoolean(false)

        /** Fragment class names that indicate the main Gboard settings screen. */
        val SETTINGS_HEADER_KEYWORDS = listOf(
            "SettingsActivity", "PreferenceHeaderFragment", "Header"
        )

        /** Preference keys that indicate the main settings screen. */
        val MAIN_SETTINGS_MARKERS = listOf(
            "pref_key_languages", "pref_key_theme"
        )

        /** All preference keys that KeyFlux injects into Gboard settings. */
        val INJECTED_SWITCH_KEYS = listOf(
            "keyflux_enable_multilingual",
            "keyflux_metered_downloads", "keyflux_enable_floating",
            "keyflux_enable_access_point", "keyflux_enable_amoled",
            "keyflux_enable_grammar", "keyflux_enable_ai",
            "keyflux_secure_clipboard",
            "keyflux_enable_emoji_kitchen",
            "keyflux_force_incognito", "keyflux_enable_privacy",
            "keyflux_log_switch"
        )

        val INJECTED_INPUT_KEYS = listOf(
            "keyflux_clip_days", "keyflux_clip_size"
        )

        val INJECTED_EXPERIMENTAL_KEYS = listOf(
            "keyflux_enable_inline_suggestions",
            "keyflux_enable_proactive_emoji",
            "keyflux_enable_clipboard_chips",
            "keyflux_enable_tflite_engine",
            "keyflux_enable_fast_access"
        )

        /** Keys that appear at the very bottom after all sections. */
        val BOTTOM_KEYS = listOf(
            "keyflux_force_stop_btn"
        )

        /** Check whether a fragment should receive KeyFlux settings injection. */
        fun shouldInjectSettings(
            fragmentClassName: String,
            hasMainSettingsMarker: Boolean,
            alreadyInjected: Boolean
        ): Boolean {
            if (alreadyInjected) return false
            if (hasMainSettingsMarker) return true
            return SETTINGS_HEADER_KEYWORDS.any { fragmentClassName.contains(it) }
        }

        /** Check whether a fragment has already been injected with KeyFlux preferences. */
        fun isAlreadyInjected(prefsMap: Map<String, Any>): Boolean {
            return prefsMap.containsKey("keyflux_enable_multilingual")
        }
    }

    init {
        try {
            loadLibrary("dexkit")
        } catch (t: Throwable) {
            logAlways("failed to load dexkit library: ${t.message}")
        }
    }

    // --- Delegates ---
    internal val prefs = PreferencesManager(this)
    internal val flagsOverride = FlagsOverrideManager(this)
    internal val prefUI = PreferenceUIHelper(this)

    @Volatile
    internal var isCurrentFieldSecure: Boolean = false
    internal var preferenceHooksApplied = false
    val failedHooks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // --- Proxy accessors for hooker files (keep binary compatibility) ---

    internal var prefsMap: HashMap<String, Any>
        get() = prefs.prefsMap
        set(value) { prefs.prefsMap = value }

    internal val clipboardTextSize: Int get() = prefs.clipboardTextSize
    internal val clipboardTextTime: Long get() = prefs.clipboardTextTime
    internal val logSwitch: Boolean get() = prefs.logSwitch
    internal val enableAi: Boolean get() = prefs.enableAi
    internal val enableGrammar: Boolean get() = prefs.enableGrammar
    internal val enableMultilingual: Boolean get() = prefs.enableMultilingual
    internal val enableFloating: Boolean get() = prefs.enableFloating
    internal val enableEmojiKitchen: Boolean get() = prefs.enableEmojiKitchen
    internal val enableAccessPoint: Boolean get() = prefs.enableAccessPoint
    internal val meteredDownloads: Boolean get() = prefs.meteredDownloads
    internal val enableAmoled: Boolean get() = prefs.enableAmoled
    internal val forceIncognito: Boolean get() = prefs.forceIncognito
    internal val enablePrivacy: Boolean get() = prefs.enablePrivacy
    internal val secureClipboard: Boolean get() = prefs.secureClipboard
    internal val enableInlineSuggestions: Boolean get() = prefs.enableInlineSuggestions
    internal val enableProactiveEmoji: Boolean get() = prefs.enableProactiveEmoji
    internal val enableClipboardChips: Boolean get() = prefs.enableClipboardChips
    internal val enableTfliteEngine: Boolean get() = prefs.enableTfliteEngine
    internal val enableFastAccess: Boolean get() = prefs.enableFastAccess

    internal fun getSafeSharedPreferences(context: Context, name: String) =
        prefs.getSafeSharedPreferences(context, name)

    internal fun loadPreferences(context: Context) = prefs.loadPreferences(context)
    internal fun savePreferenceToProvider(context: Context, key: String, value: Any) =
        prefs.savePreferenceToProvider(context, key, value)

    internal fun isSensitiveText(text: String) = prefs.isSensitiveText(text)

    // --- Logging ---

    internal fun log(str: String) {
        if (logSwitch) {
            XposedBridge.log("$TAG$str")
        }
    }

    internal fun logAlways(str: String) {
        XposedBridge.log("$TAG$str")
    }

    // --- Initialization ---

    internal fun initializeKeyFlux(context: Context, classLoader: ClassLoader) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            logAlways("Gboard version: ${packageInfo.versionName} ($code)")
        } catch (t: Throwable) {
            logAlways("Failed to get Gboard version info")
        }

        try {
            loadPreferences(context)
            prefs.registerSettingsObserver(context)

            val sp = getSafeSharedPreferences(context, "keyflux_hook")
            val spKeyMethodReadConfig = "SP_KEY_METHOD_READ_CONFIG"
            val spKeyVersion = "SP_KEY_VERSION"
            val versionCode = try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            } catch (e: Exception) {
                log("Failed to get package info for version code: ${e.message}")
                -1L
            }
            val gboardVersion = sp.getLong(spKeyVersion, -1L)
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
                try {
                    FlagsManager.hook(this@PluginEntry, classLoader, dexMethodReadConfig)
                } catch (t: Throwable) {
                    log("Failed to hook cached FlagsManager: ${t.message}")
                    failedHooks.add("FlagsManager")
                }
            } else {
                CoroutineScope(Dispatchers.Default).launch {
                    log("Resolving ReadConfig method via DexKit (version changed or cache miss)...")
                    val dexBridge = try {
                        DexKitBridge.create(classLoader, true)
                    } catch (t: Throwable) {
                        log("Failed to initialize DexKitBridge: ${t.message}")
                        failedHooks.add("DexKitBridge")
                        null
                    }
                    val method = dexBridge?.let { flagsOverride.findReadConfigMethod(it) }
                    if (method != null) {
                        sp.edit {
                            putLong(spKeyVersion, versionCode)
                            putString(spKeyMethodReadConfig, method.serialize())
                        }
                        log("ReadConfig method resolved and cached: ${method.serialize()}")
                        try {
                            FlagsManager.hook(this@PluginEntry, classLoader, method)
                        } catch (t: Throwable) {
                            log("Failed to hook FlagsManager: ${t.message}")
                            failedHooks.add("FlagsManager")
                        }
                    } else {
                        log("Failed to resolve ReadConfig method via DexKit")
                        failedHooks.add("FlagsManager")
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

    // --- Xposed entry point ---

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PACKAGE_NAME || lpparam.processName != PACKAGE_NAME) {
            return
        }

        try {
            val packageName = lpparam.packageName
            val processName = lpparam.processName
            val classLoader = lpparam.classLoader

            GodModeHookers.initGboard(classLoader)

            logAlways("Plugin loaded: package=$packageName, process=$processName, moduleVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            logAlways("Device: ${CompatibilityManager.manufacturer} ${android.os.Build.MODEL}, Android ${CompatibilityManager.androidVersion} (API ${CompatibilityManager.sdkInt})")
            if (CompatibilityManager.hasAggressiveOem) logAlways("OEM detected: aggressive process management (${CompatibilityManager.manufacturer})")
            logAlways("Initial config: logSwitch=$logSwitch, enableAi=$enableAi, clipboardTextSize=$clipboardTextSize")

            // Primary: ContextWrapper#attachBaseContext
            try {
                XposedHelpers.findAndHookMethod(
                    android.content.ContextWrapper::class.java,
                    "attachBaseContext",
                    Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (isInitialized.compareAndSet(false, true)) {
                                    logAlways("Selected context hook path: ContextWrapper#attachBaseContext")
                                    val context = param.args[0] as? Context ?: return
                                    initializeKeyFlux(context, classLoader)
                                }
                            } catch (t: Throwable) {
                                logAlways("Error during ContextWrapper#attachBaseContext hook execution: ${t.message}")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                logAlways("Failed to hook ContextWrapper#attachBaseContext: ${t.message}")
            }

            // Fallback: Application#attach
            try {
                XposedHelpers.findAndHookMethod(
                    Application::class.java,
                    "attach",
                    Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (isInitialized.compareAndSet(false, true)) {
                                    logAlways("Selected context hook path: Application#attach")
                                    val context = param.args[0] as? Context ?: return
                                    initializeKeyFlux(context, classLoader)
                                }
                            } catch (t: Throwable) {
                                logAlways("Error during Application#attach hook execution: ${t.message}")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                logAlways("Failed to hook Application#attach: ${t.message}")
            }

            // Load hookers in parallel (WaEnhancer pattern)
            val executorService = java.util.concurrent.Executors.newFixedThreadPool(3)
            val hookers = listOf(
                Runnable {
                    try {
                        ClipboardHooker.hook(this, classLoader)
                        logAlways("Successfully hooked Clipboard")
                    } catch (t: Throwable) {
                        logAlways("CRASH PREVENTED: Error hooking Clipboard: ${t.message}")
                        failedHooks.add("ClipboardHooker")
                    }
                },
                Runnable {
                    try {
                        PreferenceHooker.hook(this, classLoader)
                        logAlways("Successfully hooked Preferences")
                    } catch (t: Throwable) {
                        logAlways("CRASH PREVENTED: Error hooking Preferences: ${t.message}")
                        failedHooks.add("PreferenceHooker")
                    }
                },
                Runnable {
                    try {
                        ThemeHooker.hook(this, classLoader)
                        logAlways("Successfully hooked Theme")
                    } catch (t: Throwable) {
                        logAlways("CRASH PREVENTED: Error hooking Theme: ${t.message}")
                        failedHooks.add("ThemeHooker")
                    }
                }
            )

            for (hooker in hookers) {
                executorService.submit(hooker)
            }
            executorService.shutdown()
            executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

        } catch (t: Throwable) {
            logAlways("CRASH PREVENTED in handleLoadPackage: ${t.stackTraceToString()}")
        }
    }

    // --- Color override (used by ThemeHooker) ---

    internal fun overrideColor(res: Resources, id: Int, originalColor: Int): Int? {
        try {
            val entryName = runCatching { res.getResourceEntryName(id) }.getOrNull() ?: return null
            val packageName = runCatching { res.getResourcePackageName(id) }.getOrNull() ?: ""

            if (logSwitch) {
                log("overrideColor check: pkg=$packageName name=$entryName color=0x${Integer.toHexString(originalColor)}")
            }

            if (packageName == "android" && entryName.startsWith("system_surface_container") && !entryName.contains("high")) {
                if (logSwitch) log("Overriding system color $entryName to black")
                return 0xFF000000.toInt()
            }

            if (packageName == "android" && (entryName == "colorBackground" || entryName == "background_dark")) {
                if (logSwitch) log("Overriding system background $entryName to black")
                return 0xFF000000.toInt()
            }

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

    // --- Utility ---

    internal fun tryHook(logStr: String, unit: ((name: String) -> Unit)) {
        try {
            unit(logStr)
        } catch (e: NoSuchMethodError) {
            log("NoSuchMethodError--$logStr")
            failedHooks.add(logStr)
        } catch (e: ClassNotFoundError) {
            log("ClassNotFoundError--$logStr")
            failedHooks.add(logStr)
        } catch (t: Throwable) {
            log("Unexpected error during hook setup: $logStr: ${t.message}")
            failedHooks.add(logStr)
        }
    }

    // --- Preference fragment hooking ---

    /**
     * Discover obfuscated preference-loading methods by signature instead of hardcoded names.
     * Gboard obfuscates method names like 'bb' and 'bc' that load preferences from XML resources.
     * We find them by matching their parameter signatures:
     *   - bb: (int) -> void — loads preferences from a resource ID
     *   - bc: (int, PreferenceGroup) -> void — loads preferences into a specific group
     */
    private fun findObfuscatedPreferenceMethod(
        clazz: Class<*>,
        preferenceGroupClass: Class<*>?
    ): List<java.lang.reflect.Method> {
        val result = ArrayList<java.lang.reflect.Method>()
        try {
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers) || Modifier.isPrivate(method.modifiers)) continue
                val params = method.parameterTypes
                when {
                    // Match obfuscated 'bb': single int param, void return
                    params.size == 1 &&
                        params[0] == Int::class.javaPrimitiveType &&
                        method.returnType == Void.TYPE &&
                        method.name != "addPreferencesFromResource" -> {
                        result.add(method)
                        log("Discovered obfuscated method '${method.name}' (int) -> void in ${clazz.name}")
                    }
                    // Match obfuscated 'bc': (int, PreferenceGroup) -> void
                    params.size == 2 &&
                        params[0] == Int::class.javaPrimitiveType &&
                        preferenceGroupClass != null &&
                        preferenceGroupClass.isAssignableFrom(params[1]) &&
                        method.returnType == Void.TYPE -> {
                        result.add(method)
                        log("Discovered obfuscated method '${method.name}' (int, PreferenceGroup) -> void in ${clazz.name}")
                    }
                }
            }
        } catch (t: Throwable) {
            log("Error discovering obfuscated methods: ${t.message}")
        }
        return result
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

                    // 1. Try standard method name first
                    try {
                        val m = XposedHelpers.findMethodExact(clazz, "addPreferencesFromResource", Int::class.javaPrimitiveType)
                        methodsToHook.add(m)
                        logAlways("Found addPreferencesFromResource in $className")
                    } catch (t: Throwable) {
                        log("addPreferencesFromResource method not found in $className")
                    }

                    // 2. Discover obfuscated methods by signature (replaces hardcoded 'bb' and 'bc')
                    val obfuscatedMethods = findObfuscatedPreferenceMethod(clazz, preferenceGroupClass)
                    for (m in obfuscatedMethods) {
                        methodsToHook.add(m)
                        logAlways("Found obfuscated method '${m.name}' in $className")
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
                                    prefUI.getPreferenceScreen(fragment, classLoader)
                                }

                                log("screen for $fragmentClassName: $screen")
                                val context = prefUI.getFragmentContext(fragment)
                                log("context for $fragmentClassName: $context")
                                if (screen == null || context == null) return

                                fun logPreferenceGroup(group: Any, indent: String) {
                                    try {
                                        val count = prefUI.getPreferenceCount(group)
                                        log("${indent}logPreferenceGroup count=$count")
                                        for (i in 0 until count) {
                                            val pref = prefUI.getPreference(group, i) ?: continue
                                            val key = prefUI.getPreferenceKey(pref)
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

                                val hasLanguages = prefUI.findPreference(screen, "pref_key_languages") != null
                                val hasTheme = prefUI.findPreference(screen, "pref_key_theme") != null
                                val isMainSettings = hasLanguages || hasTheme ||
                                    fragmentClassName.contains("SettingsActivity") ||
                                    fragmentClassName.contains("PreferenceHeaderFragment") ||
                                    fragmentClassName.contains("Header")

                                log("isMainSettings=$isMainSettings, hasLanguages=$hasLanguages, hasTheme=$hasTheme")
                                val alreadyInjected = prefUI.findPreference(screen, "keyflux_enable_multilingual") != null
                                log("alreadyInjected=$alreadyInjected")

                                if (isMainSettings && !alreadyInjected) {
                                    injectKeyFluxSettings(screen, context, classLoader, preferenceGroupClass)
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

    // --- Settings injection (extracted from hookPreferenceFragments) ---

    private fun injectKeyFluxSettings(
        screen: Any,
        context: Context,
        classLoader: ClassLoader,
        preferenceGroupClass: Class<*>?
    ) {
        val preferenceClass = XposedHelpers.findClass("androidx.preference.Preference", classLoader)
        val switchPrefClass = try {
            XposedHelpers.findClass("androidx.preference.SwitchPreferenceCompat", classLoader)
        } catch (e: Throwable) {
            XposedHelpers.findClass("androidx.preference.SwitchPreference", classLoader)
        }

        var listenerField: java.lang.reflect.Field? = null
        val listenerInterface = try {
            val interfaceFields = preferenceClass.declaredFields.filter { it.type.isInterface }
            val candidateFields = interfaceFields.filter { f ->
                val ifaceMethods = f.type.declaredMethods
                ifaceMethods.size == 1 && ifaceMethods[0].parameterTypes.size == 2 && ifaceMethods[0].returnType == Boolean::class.javaPrimitiveType
            }
            if (candidateFields.isNotEmpty()) {
                listenerField = candidateFields[0]
                listenerField.isAccessible = true
                candidateFields[0].type
            } else throw Exception("Listener field not found")
        } catch (e: Throwable) {
            log("Failed to dynamically find listenerInterface: ${e.message}")
            try {
                listenerField = preferenceClass.getDeclaredField("n")
                listenerField.isAccessible = true
                listenerField.type
            } catch (ex: Throwable) {
                try {
                    XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceChangeListener", classLoader)
                } catch (ex2: Throwable) {
                    XposedHelpers.findClass("defpackage.bzf", classLoader)
                }
            }
        }
        var clickListenerField: java.lang.reflect.Field? = null
        val clickListenerInterface = try {
            val interfaceFields = preferenceClass.declaredFields.filter { it.type.isInterface }
            val candidateFields = interfaceFields.filter { f ->
                val ifaceMethods = f.type.declaredMethods
                ifaceMethods.size == 1 && ifaceMethods[0].parameterTypes.size == 1 && ifaceMethods[0].returnType == Boolean::class.javaPrimitiveType
            }
            if (candidateFields.isNotEmpty()) {
                clickListenerField = candidateFields[0]
                clickListenerField.isAccessible = true
                candidateFields[0].type
            } else throw Exception("Click listener field not found")
        } catch (e: Throwable) {
            try {
                clickListenerField = preferenceClass.getDeclaredField("o")
                clickListenerField.isAccessible = true
                clickListenerField.type
            } catch (ex: Throwable) {
                try {
                    XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceClickListener", classLoader)
                } catch (ex2: Throwable) {
                    XposedHelpers.findClass("defpackage.bzg", classLoader)
                }
            }
        }

        fun checkCompatibilityAndDisable(pref: Any, key: String) {
            val isClipboardFeature = key == "keyflux_secure_clipboard" || 
                                     key == "keyflux_clip_days" || 
                                     key == "keyflux_clip_size" ||
                                     key == "keyflux_enable_clipboard_chips"
            val isThemeFeature = key == "keyflux_enable_amoled"
            
            val isFlagsFeature = key == "keyflux_enable_ai" ||
                                 key == "keyflux_enable_grammar" ||
                                 key == "keyflux_enable_multilingual" ||
                                 key == "keyflux_enable_floating" ||
                                 key == "keyflux_enable_access_point" ||
                                 key == "keyflux_metered_downloads" ||
                                 key == "keyflux_enable_inline_suggestions" ||
                                 key == "keyflux_enable_proactive_emoji" ||
                                 key == "keyflux_enable_tflite_engine" ||
                                 key == "keyflux_enable_fast_access"

            if (isClipboardFeature && failedHooks.contains("ClipboardHooker")) {
                prefUI.setPreferenceEnabled(pref, false)
                prefUI.setPreferenceSummary(pref, Localization.getString("keyflux_feature_unavailable_clipboard"))
            } else if (isThemeFeature && failedHooks.contains("ThemeHooker")) {
                prefUI.setPreferenceEnabled(pref, false)
                prefUI.setPreferenceSummary(pref, Localization.getString("keyflux_feature_unavailable_theme"))
            } else if (isFlagsFeature && (failedHooks.contains("FlagsManager") || failedHooks.contains("DexKitBridge"))) {
                prefUI.setPreferenceEnabled(pref, false)
                prefUI.setPreferenceSummary(pref, Localization.getString("keyflux_feature_unavailable_flags"))
            }
        }

        fun createSwitch(key: String, defaultValue: Boolean): Any {
            val switchPref = XposedHelpers.newInstance(switchPrefClass, context)
            val title = Localization.getString(key + "_title")
            val summary = Localization.getString(key + "_summary")
            prefUI.setPreferenceKey(switchPref, key)
            prefUI.setPreferenceTitle(switchPref, title)
            prefUI.setPreferenceSummary(switchPref, summary)
            prefUI.setPreferenceChecked(switchPref, prefsMap[key] as? Boolean ?: defaultValue)
            try {
                XposedHelpers.callMethod(switchPref, "setPersistent", false)
            } catch (e: Throwable) {
                log("Failed to setPersistent(false) on switchPref: ${e.message}")
            }

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
            if (listenerField != null) {
                try {
                    listenerField!!.set(switchPref, listenerProxy)
                    log("setOnPreferenceChangeListener set via field ${listenerField!!.name}")
                } catch (e: Throwable) {
                    prefUI.setOnPreferenceChangeListener(switchPref, listenerProxy)
                }
            } else {
                prefUI.setOnPreferenceChangeListener(switchPref, listenerProxy)
            }
            checkCompatibilityAndDisable(switchPref, key)
            return switchPref
        }

        fun createInputPref(key: String, defaultValue: String, isNumeric: Boolean): Any {
            val inputPref = XposedHelpers.newInstance(preferenceClass, context)
            val title = Localization.getString(key + "_title")
            val summaryTemplate = Localization.getString(key + "_summary")
            prefUI.setPreferenceKey(inputPref, key)
            prefUI.setPreferenceTitle(inputPref, title)
            try {
                XposedHelpers.callMethod(inputPref, "setPersistent", false)
            } catch (e: Throwable) {
                log("Failed to setPersistent(false) on inputPref: ${e.message}")
            }
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
            prefUI.setPreferenceSummary(inputPref, try { summaryTemplate.format(getDisplayVal()) } catch (e: Exception) { summaryTemplate })

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
                            prefUI.setPreferenceSummary(inputPref, try { summaryTemplate.format(getDisplayVal()) } catch (e: Exception) { summaryTemplate })
                        }.setNegativeButton(android.R.string.cancel, null).show()
                    true
                }
            }
            if (clickListenerField != null) {
                try {
                    clickListenerField!!.set(inputPref, clickListenerProxy)
                    log("setOnPreferenceClickListener set via field ${clickListenerField!!.name}")
                } catch (e: Throwable) {
                    prefUI.setOnPreferenceClickListener(inputPref, clickListenerProxy)
                }
            } else {
                prefUI.setOnPreferenceClickListener(inputPref, clickListenerProxy)
            }
            checkCompatibilityAndDisable(inputPref, key)
            return inputPref
        }

        val targetToNewPrefs = mutableMapOf<Any, MutableList<Any>>()
        val bottomPrefs = mutableListOf<Any>()

        fun insertPreferenceAfter(targetKey: String, newPref: Any): Boolean {
            try {
                val targetPref = prefUI.findPreference(screen, targetKey)
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

                    prefUI.addPreference(screen, newPref)
                    targetToNewPrefs.getOrPut(targetPref) { mutableListOf() }.add(newPref)
                    return true
                }
            } catch (t: Throwable) {
                log("Failed to insert preference after $targetKey: ${t.message}")
            }
            prefUI.addPreference(screen, newPref)
            bottomPrefs.add(newPref)
            return false
        }

        if (failedHooks.isNotEmpty()) {
            val warningPref = XposedHelpers.newInstance(preferenceClass, context)
            prefUI.setPreferenceKey(warningPref, "keyflux_failed_hooks_warning")
            prefUI.setPreferenceTitle(warningPref, Localization.getString("keyflux_warning_failed_hooks_title"))
            val failedListStr = failedHooks.joinToString(", ")
            val summaryTemplate = Localization.getString("keyflux_warning_failed_hooks_summary")
            val summaryText = try { summaryTemplate.format(failedListStr) } catch (e: Exception) { summaryTemplate }
            prefUI.setPreferenceSummary(warningPref, summaryText)
            prefUI.setPreferenceEnabled(warningPref, false)
            insertPreferenceAfter("settings_header_language", warningPref)
        }

        insertPreferenceAfter("settings_header_language", createSwitch("keyflux_enable_multilingual", false))
        insertPreferenceAfter("settings_header_preferences", createSwitch("keyflux_metered_downloads", false))
        insertPreferenceAfter("settings_header_preferences", createSwitch("keyflux_enable_floating", false))
        insertPreferenceAfter("settings_header_theme", createSwitch("keyflux_enable_access_point", false))
        insertPreferenceAfter("settings_header_theme", createSwitch("keyflux_enable_amoled", false))
        insertPreferenceAfter("settings_header_correction", createSwitch("keyflux_enable_grammar", false))
        insertPreferenceAfter("settings_header_correction", createSwitch("keyflux_enable_ai", false))
        insertPreferenceAfter("settings_header_clipboard", createInputPref("keyflux_clip_days", "3", true))
        insertPreferenceAfter("settings_header_clipboard", createInputPref("keyflux_clip_size", "10", true))
        insertPreferenceAfter("settings_header_clipboard", createSwitch("keyflux_secure_clipboard", false))
        insertPreferenceAfter("settings_header_expression", createSwitch("keyflux_enable_emoji_kitchen", false))
        insertPreferenceAfter("settings_header_privacy", createSwitch("keyflux_force_incognito", false))
        insertPreferenceAfter("settings_header_privacy", createSwitch("keyflux_enable_privacy", false))

        val forceStopPref = XposedHelpers.newInstance(preferenceClass, context)
        prefUI.setPreferenceKey(forceStopPref, "keyflux_force_stop_btn")
        prefUI.setPreferenceTitle(forceStopPref, Localization.getString("force_stop_title"))
        prefUI.setPreferenceSummary(forceStopPref, Localization.getString("force_stop_summary"))
        val forceStopClickProxy = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(clickListenerInterface)) { _, method, args ->
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "toString" -> "KeyFluxForceStopClickListenerProxy"
                    "hashCode" -> 99999
                    "equals" -> args[0] === this
                    else -> null
                }
            } else {
                try { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:${PluginEntry.PACKAGE_NAME}"))) } catch (e: Exception) { log("Failed to open app settings: ${e.message}") }
                true
            }
        }
        if (clickListenerField != null) {
            try {
                clickListenerField!!.set(forceStopPref, forceStopClickProxy)
            } catch (e: Throwable) {
                prefUI.setOnPreferenceClickListener(forceStopPref, forceStopClickProxy)
            }
        } else {
            prefUI.setOnPreferenceClickListener(forceStopPref, forceStopClickProxy)
        }

        insertPreferenceAfter("settings_header_help_and_feedback", forceStopPref)
        insertPreferenceAfter("settings_header_help_and_feedback", createSwitch("keyflux_log_switch", false))

        // Experimental Features
        try {
            val prefCategoryClass = try {
                XposedHelpers.findClass("androidx.preference.PreferenceCategory", classLoader)
            } catch (e: Throwable) {
                XposedHelpers.findClass("android.support.v7.preference.PreferenceCategory", classLoader)
            }
            val expCategory = XposedHelpers.newInstance(prefCategoryClass, context)
            prefUI.setPreferenceKey(expCategory, "keyflux_settings_header_experimental")
            prefUI.setPreferenceTitle(expCategory, Localization.getString("keyflux_settings_header_experimental_title"))
            bottomPrefs.add(expCategory)
            bottomPrefs.add(createSwitch("keyflux_enable_inline_suggestions", false))
            bottomPrefs.add(createSwitch("keyflux_enable_proactive_emoji", false))
            bottomPrefs.add(createSwitch("keyflux_enable_clipboard_chips", false))
            bottomPrefs.add(createSwitch("keyflux_enable_tflite_engine", false))
            bottomPrefs.add(createSwitch("keyflux_enable_fast_access", false))
        } catch (t: Throwable) {
            log("Failed to create experimental category: ${t.message}")
        }

        // Now reorder
        try {
            var currentList: MutableList<Any>? = null
            val count = prefUI.getPreferenceCount(screen)

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

                // Discover mOrder field dynamically
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
}
