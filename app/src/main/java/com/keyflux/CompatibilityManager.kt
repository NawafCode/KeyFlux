package com.keyflux

import android.content.Context
import android.os.Build

/**
 * Detects Android version, OEM manufacturer, and Gboard version
 * to enable version-aware behavior throughout the module.
 */
internal object CompatibilityManager {

    // --- Android version checks ---

    val sdkInt: Int get() = Build.VERSION.SDK_INT

    val androidVersion: String get() = android.os.Build.VERSION.RELEASE

    val isAndroid10Plus: Boolean get() = sdkInt >= Build.VERSION_CODES.Q          // 29
    val isAndroid11Plus: Boolean get() = sdkInt >= Build.VERSION_CODES.R          // 30
    val isAndroid12Plus: Boolean get() = sdkInt >= Build.VERSION_CODES.S          // 31
    val isAndroid13Plus: Boolean get() = sdkInt >= Build.VERSION_CODES.TIRAMISU   // 33
    val isAndroid14Plus: Boolean get() = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE // 34
    val isAndroid15Plus: Boolean get() = sdkInt >= 35

    // --- OEM detection ---

    val manufacturer: String get() = Build.MANUFACTURER.lowercase()

    val isSamsung: Boolean get() = manufacturer.contains("samsung")
    val isXiaomi: Boolean get() = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")
    val isHuawei: Boolean get() = manufacturer.contains("huawei") || manufacturer.contains("honor")
    val isOnePlus: Boolean get() = manufacturer.contains("oneplus")
    val isOppo: Boolean get() = manufacturer.contains("oppo") || manufacturer.contains("realme")
    val isVivo: Boolean get() = manufacturer.contains("vivo") || manufacturer.contains("iqoo")
    val isPixel: Boolean get() = manufacturer.contains("google")

    /**
     * Returns true if the device is known to have aggressive process killing
     * or modified Android framework that may affect Xposed hooks.
     */
    val hasAggressiveOem: Boolean get() = isSamsung || isXiaomi || isHuawei || isOppo || isVivo

    /**
     * Samsung Knox can interfere with Xposed module operations.
     * Some Samsung devices require special handling for system hooks.
     */
    val hasKnox: Boolean get() = isSamsung

    /**
     * Xiaomi/MIUI/HyperOS may block background hooks or modify
     * the preference framework.
     */
    val hasMiuiOrHyperOS: Boolean get() = isXiaomi

    // --- Gboard version detection ---

    /**
     * Detect the installed Gboard version code.
     * Returns -1 if detection fails.
     */
    fun getGboardVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(PluginEntry.PACKAGE_NAME, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Detect the installed Gboard version name.
     * Returns "unknown" if detection fails.
     */
    fun getGboardVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(PluginEntry.PACKAGE_NAME, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Returns a human-readable diagnostic string for logging.
     */
    fun getDiagnosticString(context: Context?): String {
        val gboardVersion = context?.let { getGboardVersionName(it) } ?: "unknown"
        val gboardCode = context?.let { getGboardVersionCode(it) } ?: -1
        return buildString {
            append("Android $androidVersion (API $sdkInt)")
            append(" | $manufacturer ${Build.MODEL}")
            if (hasAggressiveOem) append(" [OEM: aggressive]")
            append(" | Gboard $gboardVersion ($gboardCode)")
        }
    }
}
