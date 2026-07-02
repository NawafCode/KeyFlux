package com.keyflux

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Reflection helper wrappers for handling Gboard preference library obfuscation.
 * All methods try the standard AndroidX Preference API first, then dynamically
 * discover the correct method by signature matching (parameter count + types).
 * Falls back to hardcoded obfuscated names only as a last resort.
 */
internal class PreferenceUIHelper(private val plugin: PluginEntry) {

    private fun log(msg: String) = plugin.log(msg)
    private fun logAlways(msg: String) = plugin.logAlways(msg)

    // --- Dynamic method discovery cache ---
    // Maps: (className, methodName) -> resolved Method
    private val methodCache = HashMap<String, Method>()
    private val fieldCache = HashMap<String, java.lang.reflect.Field>()

    // --- Screen & Context ---

    fun getPreferenceScreen(fragment: Any, classLoader: ClassLoader): Any? {
        val preferenceGroupClass = try {
            XposedHelpers.findClass("androidx.preference.PreferenceGroup", classLoader)
        } catch (t: Throwable) { null }

        try {
            return XposedHelpers.callMethod(fragment, "getPreferenceScreen")
        } catch (t: Throwable) {}

        // Dynamic scan: find zero-arg method returning PreferenceGroup subtype
        try {
            var clazz: Class<*>? = fragment.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.isEmpty() && !method.returnType.isPrimitive &&
                        method.returnType != String::class.java && method.returnType != Context::class.java) {
                        try {
                            method.isAccessible = true
                            val result = method.invoke(fragment)
                            if (result != null) {
                                if (preferenceGroupClass != null && preferenceGroupClass.isInstance(result)) {
                                    logAlways("Found PreferenceScreen dynamically: ${method.name}")
                                    return result
                                }
                                val resClassName = result.javaClass.name.lowercase()
                                if (resClassName.contains("preferencescreen") || resClassName.contains("preferencegroup")) {
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

    fun getFragmentContext(fragment: Any): Context? {
        try {
            return XposedHelpers.callMethod(fragment, "getContext") as? Context
        } catch (t: Throwable) {}

        // Dynamic scan: find zero-arg method returning Context
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

        if (fragment is android.app.Fragment) return fragment.context

        log("getContext completely failed")
        return null
    }

    // --- PreferenceGroup operations ---

    fun findPreference(group: Any, key: String): Any? {
        // Try standard AndroidX API first
        try {
            return XposedHelpers.callMethod(group, "findPreference", key)
        } catch (t: Throwable) {}

        // Traverse children by key — avoids setter false positives (setTitle returns `this`)
        val count = getPreferenceCount(group)
        for (i in 0 until count) {
            val child = getPreference(group, i) ?: continue
            val childKey = getPreferenceKey(child)
            if (childKey == key) {
                log("findPreference found via key traversal: child[$i] = $key")
                return child
            }
            // Recurse into nested PreferenceGroups (categories)
            if (count > 0) {
                try {
                    val nestedCount = getPreferenceCount(child)
                    if (nestedCount > 0) {
                        for (j in 0 until nestedCount) {
                            val nestedChild = getPreference(child, j) ?: continue
                            val nestedKey = getPreferenceKey(nestedChild)
                            if (nestedKey == key) {
                                log("findPreference found via nested traversal: [$i][$j] = $key")
                                return nestedChild
                            }
                        }
                    }
                } catch (e: Throwable) {}
            }
        }

        log("findPreference failed for $key")
        return null
    }

    fun addPreference(group: Any, preference: Any): Boolean {
        // Try standard AndroidX API first
        try {
            XposedHelpers.callMethod(group, "addPreference", preference)
            return true
        } catch (t: Throwable) {}

        // Dynamic scan: find single-arg method returning boolean, where arg is NOT primitive
        // (excludes setVisible(boolean), setEnabled(boolean), etc.)
        try {
            var clazz: Class<*>? = group.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        !method.parameterTypes[0].isPrimitive &&
                        method.returnType == Boolean::class.javaPrimitiveType) {
                        try {
                            method.isAccessible = true
                            method.invoke(group, preference)
                            log("addPreference found via dynamic scan: ${method.name}")
                            return true
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("addPreference failed")
        return false
    }

    fun getPreferenceCount(group: Any): Int {
        // Try standard AndroidX API first
        try {
            return XposedHelpers.callMethod(group, "getPreferenceCount") as Int
        } catch (t: Throwable) {}

        // Dynamic scan: find zero-arg method returning int
        try {
            var clazz: Class<*>? = group.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.isEmpty() &&
                        method.returnType == Int::class.javaPrimitiveType) {
                        try {
                            method.isAccessible = true
                            val result = method.invoke(group) as Int
                            log("getPreferenceCount found via dynamic scan: ${method.name} = $result")
                            return result
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("getPreferenceCount failed")
        return 0
    }

    fun getPreference(group: Any, index: Int): Any? {
        // Try standard AndroidX API first
        try {
            return XposedHelpers.callMethod(group, "getPreference", index)
        } catch (t: Throwable) {}

        // Dynamic scan: find single-int-arg method returning Object
        try {
            var clazz: Class<*>? = group.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        !method.returnType.isPrimitive && method.returnType != Void.TYPE) {
                        try {
                            method.isAccessible = true
                            val result = method.invoke(group, index)
                            if (result != null) {
                                log("getPreference found via dynamic scan: ${method.name}")
                                return result
                            }
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("getPreference failed for index $index")
        return null
    }

    // --- Preference property accessors ---

    fun getPreferenceKey(pref: Any): String? {
        // Try standard AndroidX API first
        try {
            return XposedHelpers.callMethod(pref, "getKey") as? String
        } catch (t: Throwable) {}

        // Dynamic scan: find zero-arg method returning String
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.isEmpty() && method.returnType == String::class.java) {
                        try {
                            method.isAccessible = true
                            val result = method.invoke(pref) as? String
                            if (!result.isNullOrEmpty()) {
                                log("getPreferenceKey found via dynamic scan: ${method.name}")
                                return result
                            }
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        // Fallback: try direct field access for mKey
        try {
            val mKeyVal = XposedHelpers.getObjectField(pref, "mKey") as? String
            if (!mKeyVal.isNullOrEmpty()) return mKeyVal
        } catch (t: Throwable) {}

        log("getPreferenceKey failed")
        return null
    }

    fun setPreferenceKey(pref: Any, key: String) {
        // Try standard AndroidX API first
        try {
            XposedHelpers.callMethod(pref, "setKey", key)
            return
        } catch (t: Throwable) {}

        // Try direct field access for mKey
        try {
            XposedHelpers.setObjectField(pref, "mKey", key)
            log("setPreferenceKey set via mKey field")
            return
        } catch (t: Throwable) {}

        // Dynamic scan: find single-String-arg void method
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == String::class.java &&
                        method.returnType == Void.TYPE) {
                        try {
                            method.isAccessible = true
                            method.invoke(pref, key)
                            log("setPreferenceKey found via dynamic scan: ${method.name}")
                            return
                        } catch (e: Throwable) {
                            // If the method threw an exception (e.g. setDependency("key") throwing IllegalStateException),
                            // it might have still mutated internal state (mDependencyKey = "key").
                            // We MUST revert this side effect by invoking it with null before continuing.
                            try {
                                method.invoke(pref, null as String?)
                                log("setPreferenceKey reverted side-effect for: ${method.name}")
                            } catch (e2: Throwable) {}
                        }
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("setPreferenceKey failed for $key")
    }

    fun setPreferenceTitle(pref: Any, title: CharSequence) {
        // Try standard AndroidX API first
        try {
            XposedHelpers.callMethod(pref, "setTitle", title)
            return
        } catch (t: Throwable) {}

        // Try direct field access for mTitle
        try {
            XposedHelpers.setObjectField(pref, "mTitle", title)
            log("setPreferenceTitle set via mTitle field")
            return
        } catch (t: Throwable) {}

        // Dynamic scan: find single-CharSequence-arg void method
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == CharSequence::class.java &&
                        method.returnType == Void.TYPE) {
                        try {
                            method.isAccessible = true
                            method.invoke(pref, title)
                            log("setPreferenceTitle found via dynamic scan: ${method.name}")
                            return
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("setPreferenceTitle failed")
    }

    fun setPreferenceSummary(pref: Any, summary: CharSequence) {
        // Try standard AndroidX API first
        try {
            XposedHelpers.callMethod(pref, "setSummary", summary)
            return
        } catch (t: Throwable) {}

        // Try direct field access for mSummary
        try {
            XposedHelpers.setObjectField(pref, "mSummary", summary)
            log("setPreferenceSummary set via mSummary field")
            return
        } catch (t: Throwable) {}

        // Dynamic scan: find single-CharSequence-arg void method
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == CharSequence::class.java &&
                        method.returnType == Void.TYPE) {
                        try {
                            method.isAccessible = true
                            method.invoke(pref, summary)
                            log("setPreferenceSummary found via dynamic scan: ${method.name}")
                            return
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("setPreferenceSummary failed")
    }

    fun setPreferenceIntent(pref: Any, intent: Intent) {
        try {
            XposedHelpers.callMethod(pref, "setIntent", intent)
            return
        } catch (t: Throwable) {}

        // Dynamic scan: find single-Intent-arg method returning void
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        Intent::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        method.returnType == Void.TYPE) {
                        try {
                            method.isAccessible = true
                            method.invoke(pref, intent)
                            log("setPreferenceIntent found via dynamic scan: ${method.name}")
                            return
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("setPreferenceIntent failed")
    }

    fun setPreferenceChecked(pref: Any, checked: Boolean) {
        try {
            XposedHelpers.callMethod(pref, "setChecked", checked)
            return
        } catch (t: Throwable) {}

        // Dynamic scan: find single-boolean-arg method returning void
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        method.returnType == Void.TYPE) {
                        try {
                            method.isAccessible = true
                            method.invoke(pref, checked)
                            log("setPreferenceChecked found via dynamic scan: ${method.name}")
                            return
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("setPreferenceChecked failed")
    }

    fun setOnPreferenceChangeListener(pref: Any, listener: Any) {
        // Try standard AndroidX API first
        try {
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", listener)
            return
        } catch (t: Throwable) {}

        // Dynamic scan: find single-arg method with listener-type parameter
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].isInterface &&
                        method.returnType == Void.TYPE) {
                        try {
                            method.isAccessible = true
                            method.invoke(pref, listener)
                            log("setOnPreferenceChangeListener found via dynamic scan: ${method.name}")
                            return
                        } catch (e: Throwable) {
                            log("setOnPreferenceChangeListener invoke failed on ${method.name}: ${e.javaClass.name} - ${e.message}")
                        }
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("setOnPreferenceChangeListener failed")
    }

    fun setOnPreferenceClickListener(pref: Any, listener: Any) {
        // Try standard AndroidX API first
        try {
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", listener)
            return
        } catch (t: Throwable) {}

        // Dynamic scan: find single-arg method with listener-type parameter
        try {
            var clazz: Class<*>? = pref.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].isInterface &&
                        method.returnType == Void.TYPE) {
                        try {
                            method.isAccessible = true
                            method.invoke(pref, listener)
                            log("setOnPreferenceClickListener found via dynamic scan: ${method.name}")
                            return
                        } catch (e: Throwable) {}
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {}

        log("setOnPreferenceClickListener failed")
    }

    fun setPreferenceEnabled(pref: Any, enabled: Boolean) {
        try {
            XposedHelpers.callMethod(pref, "setEnabled", enabled)
            return
        } catch (t: Throwable) {}

        try {
            XposedHelpers.setObjectField(pref, "mEnabled", enabled)
            log("setPreferenceEnabled set via mEnabled field")
            return
        } catch (t: Throwable) {}
    }
}
