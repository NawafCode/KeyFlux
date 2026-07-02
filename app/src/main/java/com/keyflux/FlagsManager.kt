package com.keyflux

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import org.luckypray.dexkit.wrap.DexMethod

object FlagsManager {
    fun hook(plugin: PluginEntry, classLoader: ClassLoader, dexMethod: DexMethod) {
        plugin.apply {
            val methodName = dexMethod.name
            val className = dexMethod.className
            val tag = "$className#$methodName"
            log("Hooking ReadConfig method: $tag")
            tryHook(tag) {
                findAndHookMethod(
                    className, classLoader, methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val name = flagsOverride.getFlagName(param.thisObject)
                                if (name == null) {
                                    log("Flag name field is null or unresolved")
                                    return
                                }

                                val override = flagsOverride.evaluateFlagOverride(name)
                                if (override != null) {
                                    param.result = override
                                    if (logSwitch) log("Overrode flag $name to $override")
                                }
                            } catch (t: Throwable) {
                                log("Error evaluating flag override: ${t.message}")
                            }
                        }
                    }
                )
            }
        }
    }
}
