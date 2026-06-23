package com.keyflux

import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findAndHookMethod

object ThemeHooker {
    fun hook(plugin: PluginEntry, classLoader: ClassLoader) {
        plugin.apply {
            tryHook("Resources#getColor(Int)") {
                val m = XposedHelpers.findMethodExact(
                    "android.content.res.Resources",
                    classLoader,
                    "getColor",
                    Int::class.javaPrimitiveType
                )
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enableAmoled) return
                        val res = param.thisObject as? Resources ?: return
                        val isDarkMode = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        if (!isDarkMode) return
                        val id = param.args[0] as Int
                        val result = param.result as Int
                        overrideColor(res, id, result)?.let { param.result = it }
                    }
                })
            }

            tryHook("Resources#getColor(Int, Theme)") {
                val m = XposedHelpers.findMethodExact(
                    "android.content.res.Resources",
                    classLoader,
                    "getColor",
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java
                )
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enableAmoled) return
                        val res = param.thisObject as? Resources ?: return
                        val isDarkMode = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        if (!isDarkMode) return
                        val id = param.args[0] as Int
                        val result = param.result as Int
                        overrideColor(res, id, result)?.let { param.result = it }
                    }
                })
            }

            tryHook("TypedArray#getColor(Int, Int)") {
                val m = XposedHelpers.findMethodExact(
                    "android.content.res.TypedArray",
                    classLoader,
                    "getColor",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enableAmoled) return
                        val typedArray = param.thisObject as? TypedArray ?: return
                        val index = param.args[0] as Int
                        val result = param.result as Int
                        val colorId = typedArray.getResourceId(index, 0)
                        if (colorId != 0) {
                            val res = typedArray.resources ?: return
                            val isDarkMode = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                            if (!isDarkMode) return
                            overrideColor(res, colorId, result)?.let { param.result = it }
                        }
                    }
                })
            }
        }
    }
}
