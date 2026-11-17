package com.fan.edgex.hook

import android.content.ClipData
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object SystemClipboardShareHook {
    private const val INTENT_CREATOR = "com.android.systemui.clipboardoverlay.IntentCreator"
    private const val REMOTE_COPY = "android.intent.action.REMOTE_COPY"

    fun install(classLoader: ClassLoader) {
        runCatching {
            val factory = XposedHelpers.findClass(INTENT_CREATOR, classLoader)
            val share = factory.getDeclaredMethod(
                "getShareIntent", ClipData::class.java, Context::class.java
            ).apply { isAccessible = true }

            // System UI shares this intent factory across both clipboard popup
            // implementations. Only the remote-copy button uses this method.
            XposedHelpers.findAndHookMethod(
                factory, "getRemoteCopyIntent", ClipData::class.java, Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val remoteCopy = param.result as? Intent ?: return
                        if (remoteCopy.action != REMOTE_COPY) return
                        val clip = param.args[0] as? ClipData ?: return
                        if (clip.itemCount == 0) return
                        val context = param.args[1] as? Context ?: return
                        val chooser = runCatching {
                            share.invoke(null, clip, context) as? Intent
                        }.getOrNull()
                        if (chooser?.action == Intent.ACTION_CHOOSER) param.result = chooser
                    }
                }
            )
        }.onSuccess {
            XposedBridge.log("EdgeX: System UI clipboard share hook installed")
        }.onFailure {
            XposedBridge.log("EdgeX: System UI clipboard share hook unavailable (${it.javaClass.simpleName})")
        }
    }
}
