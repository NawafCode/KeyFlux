package com.keyflux

import android.view.inputmethod.InputConnection
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Date
import kotlinx.coroutines.*

object GodModeHookers {

    fun initGboard(classLoader: ClassLoader) {
        hookInputConnection(classLoader)
    }

    private fun log(msg: String) {
        XposedBridge.log("KeyFlux-GodMode: $msg")
    }

    private fun hookInputConnection(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                classLoader,
                "getCurrentInputConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val originalIc = param.result as? InputConnection ?: return
                        
                        // Prevent infinite wrapping
                        if (Proxy.isProxyClass(originalIc.javaClass) && Proxy.getInvocationHandler(originalIc) is KeyFluxInputConnectionHandler) {
                            return
                        }

                        // Create a dynamic proxy to safely intercept InputConnection methods
                        val proxyIc = Proxy.newProxyInstance(
                            classLoader,
                            arrayOf(InputConnection::class.java),
                            KeyFluxInputConnectionHandler(originalIc)
                        ) as InputConnection

                        param.result = proxyIc
                    }
                }
            )
            log("InputConnection safely hooked via dynamic proxy")
        } catch (t: Throwable) {
            log("Failed to hook InputConnection: ${t.message}")
        }
    }

    class KeyFluxInputConnectionHandler(private val original: InputConnection) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            try {
                if (method.name == "commitText" && args != null && args.size >= 2) {
                    val text = args[0] as? CharSequence
                    val newCursorPosition = args[1] as? Int ?: 1
                    
                    if (text != null) {
                        val strText = text.toString()
                        
                        // Trigger on space
                        if (strText == " ") {
                            val textBefore = original.getTextBeforeCursor(20, 0)?.toString() ?: ""
                            
                            if (textBefore.endsWith(".date")) {
                                log("Macro triggered: replacing .date with current date")
                                original.deleteSurroundingText(5, 0) // delete ".date"
                                val dateStr = Date().toString()
                                return original.commitText(dateStr + " ", newCursorPosition)
                            }
                            
                            if (textBefore.contains("/ai ")) {
                                val prompt = textBefore.substringAfterLast("/ai ").trim()
                                log("Inline AI triggered with prompt: $prompt")
                                val lengthToDelete = "/ai $prompt".length
                                original.deleteSurroundingText(lengthToDelete, 0)
                                
                                CoroutineScope(Dispatchers.IO).launch {
                                    delay(1000) // simulate thought process
                                    val aiResponse = "🤖 [AI Response to '$prompt']"
                                    withContext(Dispatchers.Main) {
                                        original.commitText(aiResponse + " ", newCursorPosition)
                                    }
                                }
                                return true // Consume the space
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                log("Error in proxy invoke: ${e.message}")
            }
            
            // Forward to original
            return if (args != null) method.invoke(original, *args) else method.invoke(original)
        }
    }
}
