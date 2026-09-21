package com.fan.edgex.config

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.view.inputmethod.InputMethodManager
import com.fan.edgex.BuildConfig
import com.fan.edgex.IClipboardImageBridge
import com.fan.edgex.IClipboardImeBridge

/** Performs provider-backed image clipboard/share work from EdgeX's app UID. */
class ClipboardImageBridgeService : Service() {

    @Volatile private var registeredIme: IClipboardImeBridge? = null
    @Volatile private var registeredImeBinder: IBinder? = null
    @Volatile private var registeredImeUid: Int = -1
    @Volatile private var registeredImePackage: String? = null
    @Volatile private var registeredImeDeath: IBinder.DeathRecipient? = null

    private val bridge = object : IClipboardImageBridge.Stub() {
        @Suppress("UNUSED_PARAMETER")
        override fun setImageClip(
            uriValue: String?,
            label: String?,
            targetPackage: String?
        ): Boolean {
            if (!isSystemServerCaller()) return false
            val uri = validatedUri(uriValue) ?: return false
            val identity = Binder.clearCallingIdentity()
            return try {
                runCatching {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return@runCatching false
                    clipboard.setPrimaryClip(
                        ClipData.newUri(
                            contentResolver,
                            label?.take(80).orEmpty().ifBlank { "Image" },
                            uri
                        )
                    )
                    true
                }.getOrDefault(false)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun commitImageToEditor(
            uriValue: String?,
            mimeType: String?,
            label: String?,
            targetPackage: String?
        ): Boolean {
            if (!isSystemServerCaller() || targetPackage.isNullOrBlank()) return false
            val uri = validatedUri(uriValue) ?: return false
            val mime = mimeType?.takeIf(ClipboardImageStore::isSupported)
                ?: contentResolver.getType(uri)?.takeIf(ClipboardImageStore::isSupported)
                ?: return false
            val identity = Binder.clearCallingIdentity()
            return try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return false
                clipboard.setPrimaryClip(
                    ClipData.newUri(
                        contentResolver,
                        label?.take(80).orEmpty().ifBlank { "Image" },
                        uri
                    )
                )

                val imePackage = activeInputMethodPackage() ?: return false
                val callback = registeredIme
                val callbackBinder = registeredImeBinder
                if (registeredImePackage != imePackage || callback == null ||
                    callbackBinder == null || !callbackBinder.isBinderAlive
                ) return false

                // Grant the active IME access to this private provider URI. Its
                // InputConnection then creates Android's temporary editor grant.
                grantUriPermission(imePackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    callback.commitImage(
                        uri.toString(), mime,
                        label?.take(80).orEmpty().ifBlank { "Image" }, targetPackage
                    )
                } finally {
                    revokeUriPermission(
                        imePackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (_: Throwable) {
                false
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun registerImeBridge(imeBridge: IBinder?): Boolean {
            val callbackBinder = imeBridge ?: return false
            val activePackage = activeInputMethodPackage() ?: return false
            val callingUid = Binder.getCallingUid()
            if (packageManager.getPackagesForUid(callingUid)?.contains(activePackage) != true) {
                return false
            }
            val callback = IClipboardImeBridge.Stub.asInterface(callbackBinder) ?: return false
            val deathRecipient = IBinder.DeathRecipient {
                synchronized(this@ClipboardImageBridgeService) {
                    if (registeredImeBinder == callbackBinder) clearRegisteredImeLocked()
                }
            }
            synchronized(this@ClipboardImageBridgeService) {
                clearRegisteredImeLocked()
                try {
                    callbackBinder.linkToDeath(deathRecipient, 0)
                } catch (_: Throwable) {
                    return false
                }
                registeredIme = callback
                registeredImeBinder = callbackBinder
                registeredImeUid = callingUid
                registeredImePackage = activePackage
                registeredImeDeath = deathRecipient
            }
            return true
        }

        override fun unregisterImeBridge(imeBridge: IBinder?) {
            val callbackBinder = imeBridge ?: return
            val callingUid = Binder.getCallingUid()
            synchronized(this@ClipboardImageBridgeService) {
                if (registeredImeBinder == callbackBinder && registeredImeUid == callingUid) {
                    clearRegisteredImeLocked()
                }
            }
        }

        override fun openImage(uriValue: String?, mimeType: String?, label: String?): Boolean {
            if (!isSystemServerCaller()) return false
            val uri = validatedUri(uriValue) ?: return false
            val mime = mimeType?.takeIf(ClipboardImageStore::isSupported)
                ?: contentResolver.getType(uri)?.takeIf(ClipboardImageStore::isSupported)
                ?: return false
            val identity = Binder.clearCallingIdentity()
            return try {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    clipData = ClipData.newUri(
                        contentResolver, label?.take(80).orEmpty().ifBlank { "Image" }, uri
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Android 15 blocks this background service from launching the
                // viewer. Grant every resolved viewer access here; system_server
                // performs the visible launch after this Binder call returns.
                grantResolvedTargets(view, uri)
            } catch (_: Throwable) {
                false
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun shareImage(uriValue: String?, mimeType: String?, chooserTitle: String?): Boolean {
            if (!isSystemServerCaller()) return false
            val uri = validatedUri(uriValue) ?: return false
            val identity = Binder.clearCallingIdentity()
            return try {
                runCatching {
                    val mime = mimeType?.takeIf(ClipboardImageStore::isSupported)
                        ?: contentResolver.getType(uri)?.takeIf(ClipboardImageStore::isSupported)
                        ?: "image/*"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(contentResolver, "Image", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    grantResolvedTargets(send, uri)
                }.getOrDefault(false)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun loadImageThumbnail(uriValue: String?, targetPx: Int): Bitmap? {
            if (!isSystemServerCaller()) return null
            val uri = validatedUri(uriValue) ?: return null
            val target = targetPx.coerceIn(1, 384)
            val identity = Binder.clearCallingIdentity()
            return try {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = ClipboardImageStore.sampleSizeFor(
                            bounds.outWidth, bounds.outHeight, target
                        )
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }.getOrNull()
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = bridge

    override fun onDestroy() {
        synchronized(this) { clearRegisteredImeLocked() }
        super.onDestroy()
    }

    private fun activeInputMethodPackage(): String? = runCatching {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.currentInputMethodInfo?.packageName
    }.getOrNull()

    private fun clearRegisteredImeLocked() {
        val binder = registeredImeBinder
        val death = registeredImeDeath
        if (binder != null && death != null) runCatching { binder.unlinkToDeath(death, 0) }
        registeredIme = null
        registeredImeBinder = null
        registeredImeUid = -1
        registeredImePackage = null
        registeredImeDeath = null
    }

    private fun validatedUri(value: String?): Uri? {
        val uri = value?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (uri.scheme != "content" || uri.authority != "${BuildConfig.APPLICATION_ID}.clipboardimages") {
            return null
        }
        return uri
    }

    private fun grantResolvedTargets(intent: Intent, uri: Uri): Boolean {
        val packages = packageManager.queryIntentActivities(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        ).asSequence().map { it.activityInfo.packageName }.distinct().toList()
        if (packages.isEmpty()) return false
        packages.forEach { packageName ->
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return true
    }

    private fun isSystemServerCaller(): Boolean {
        val callerUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callerUid)
        return callerUid == Process.SYSTEM_UID && callerPackages?.contains("android") == true
    }
}
