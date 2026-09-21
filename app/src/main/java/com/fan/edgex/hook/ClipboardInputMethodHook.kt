package com.fan.edgex.hook

import android.content.ComponentName
import android.content.ClipDescription
import android.content.Context
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import com.fan.edgex.BuildConfig
import com.fan.edgex.IClipboardImageBridge
import com.fan.edgex.IClipboardImeBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Runs only inside LSPosed-scoped input methods; this is where the live IME connection exists. */
object ClipboardInputMethodHook {
    private const val TAG = "EdgeX"
    private const val SESSION_FIELD = "edgex_clipboard_ime_session"
    private const val BRIDGE_SERVICE = "${BuildConfig.APPLICATION_ID}.config.ClipboardImageBridgeService"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Packages recommended by the LSPosed scope metadata. Other IMEs can be added in LSPosed. */
    val supportedPackages = setOf(
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    )

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in supportedPackages) return
        runCatching {
            val serviceClass = XposedHelpers.findClass(
                "android.inputmethodservice.InputMethodService", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(serviceClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ime = param.thisObject as? InputMethodService ?: return
                    if (XposedHelpers.getAdditionalInstanceField(ime, SESSION_FIELD) != null) return
                    val session = ImeSession(ime)
                    XposedHelpers.setAdditionalInstanceField(ime, SESSION_FIELD, session)
                    session.connect()
                }
            })
            XposedBridge.hookAllMethods(serviceClass, "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ime = param.thisObject as? InputMethodService ?: return
                    (XposedHelpers.getAdditionalInstanceField(ime, SESSION_FIELD) as? ImeSession)
                        ?.disconnect()
                    XposedHelpers.removeAdditionalInstanceField(ime, SESSION_FIELD)
                }
            })
            XposedBridge.log("$TAG: clipboard rich-content hook installed in ${lpparam.packageName}")
        }.onFailure {
            XposedBridge.log("$TAG: clipboard IME hook failed in ${lpparam.packageName}: ${it.javaClass.simpleName}")
        }
    }

    internal fun editorAcceptsMime(editor: EditorInfo?, mimeType: String?): Boolean {
        if (editor == null || mimeType.isNullOrBlank()) return false
        if (isPasswordEditor(editor)) return false
        val accepted = editor.contentMimeTypes ?: return false
        return accepted.any { acceptedType -> mimeMatches(mimeType, acceptedType) }
    }

    internal fun mimeMatches(mimeType: String, acceptedType: String): Boolean {
        val mime = mimeType.trim().lowercase()
        val accepted = acceptedType.trim().lowercase()
        if (mime == accepted || accepted == "*/*") return true
        return accepted.endsWith("/*") && mime.startsWith(accepted.dropLast(1))
    }

    private fun isPasswordEditor(editor: EditorInfo): Boolean {
        val inputClass = editor.inputType and InputType.TYPE_MASK_CLASS
        val variation = editor.inputType and InputType.TYPE_MASK_VARIATION
        return (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private class ImeSession(private val ime: InputMethodService) {
        @Volatile private var bridge: IClipboardImageBridge? = null
        @Volatile private var bound = false

        private val callback = object : IClipboardImeBridge.Stub() {
            override fun commitImage(
                uri: String?,
                mimeType: String?,
                label: String?,
                targetPackage: String?
            ): Boolean {
                if (uri.isNullOrBlank() || mimeType.isNullOrBlank() || targetPackage.isNullOrBlank()) {
                    return false
                }
                val task = FutureTask {
                    val editor = ime.currentInputEditorInfo
                    if (!ime.currentInputStarted || editor?.packageName != targetPackage ||
                        !editorAcceptsMime(editor, mimeType)
                    ) return@FutureTask false

                    val connection: InputConnection = ime.currentInputConnection ?: return@FutureTask false
                    val description = ClipDescription(
                        label?.take(80).orEmpty().ifBlank { "Image" },
                        arrayOf(mimeType)
                    )
                    val content = runCatching {
                        InputContentInfo(Uri.parse(uri), description, null)
                    }.getOrNull() ?: return@FutureTask false
                    runCatching {
                        connection.commitContent(
                            content,
                            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                            null
                        )
                    }.getOrDefault(false)
                }
                if (Looper.myLooper() == ime.mainLooper) {
                    task.run()
                } else if (!mainHandler.post(task)) {
                    return false
                }
                return runCatching { task.get(3, TimeUnit.SECONDS) }.getOrDefault(false)
            }
        }

        private val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val remote = IClipboardImageBridge.Stub.asInterface(binder) ?: return
                bridge = remote
                runCatching { remote.registerImeBridge(callback.asBinder()) }
                    .onFailure {
                        XposedBridge.log("$TAG: IME bridge registration failed: ${it.javaClass.simpleName}")
                    }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bridge = null
            }

            override fun onBindingDied(name: ComponentName?) {
                bridge = null
                unbind()
            }

            override fun onNullBinding(name: ComponentName?) {
                bridge = null
                unbind()
            }
        }

        fun connect() {
            val intent = android.content.Intent().setComponent(
                ComponentName(BuildConfig.APPLICATION_ID, BRIDGE_SERVICE)
            )
            bound = runCatching {
                ime.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) XposedBridge.log("$TAG: could not bind the clipboard IME bridge")
        }

        fun disconnect() {
            val remote = bridge
            runCatching { remote?.unregisterImeBridge(callback.asBinder()) }
            bridge = null
            unbind()
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            runCatching { ime.unbindService(serviceConnection) }
        }
    }
}
