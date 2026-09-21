package com.fan.edgex.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Log
import com.fan.edgex.BuildConfig
import com.fan.edgex.R
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Owns the EdgeX-side image files for image clipboard entries.
 *
 * Files live under the app's device-protected files dir so both the injected
 * system_server hook (uid 1000, via the root shell bridge) and the app process
 * can reach them. Nothing here decodes or copies on the main thread by itself;
 * callers pass an executor.
 */
object ClipboardImageStore {

    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 30
    const val MAX_DIMENSION = 4096

    private const val DIR = "clipboard_images"

    private val supportedMime = setOf(
        "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif"
    )

    fun directory(context: Context): File =
        File(moduleDeviceProtectedContext(context).filesDir, DIR)

    private fun moduleDeviceProtectedContext(context: Context): Context = runCatching {
        val moduleContext = if (context.packageName == BuildConfig.APPLICATION_ID) {
            context
        } else {
            context.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        }
        moduleContext.createDeviceProtectedStorageContext()
    }.getOrElse {
        context.createDeviceProtectedStorageContext()
    }

    /** Absolute path stored in history, resolved to an existing owned file. */
    fun fileFor(context: Context, path: String?): File? {
        if (path.isNullOrEmpty()) return null
        return runCatching {
            val directory = directory(context).canonicalFile
            val file = File(path).canonicalFile
            file.takeIf {
                it.parentFile == directory && OWNED_IMAGE_NAME.matches(it.name) &&
                    it.isFile && canStore(it.length())
            }
        }.getOrNull()
    }

    fun isSupported(mime: String?): Boolean {
        if (mime.isNullOrEmpty()) return false
        val normalized = mime.lowercase()
        return supportedMime.any { normalized == it || normalized.startsWith(it) }
    }

    /** Stable, collision-free name derived from the clip id, never user input. */
    fun fileNameFor(clipId: String, mime: String?): String {
        val safeId = clipId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(48)
            .ifEmpty { System.currentTimeMillis().toString() }
        return "clip-$safeId.${extensionFor(mime)}"
    }

    fun extensionFor(mime: String?): String = when (mime?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "img"
    }

    fun mimeFor(file: File): String? = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> null
    }

    /** Bounds a copy request: rejects absurd sizes before any bytes move. */
    fun canStore(bytes: Long): Boolean = bytes in 1..MAX_BYTES.toLong()

    fun delete(context: Context, path: String?): Boolean {
        val file = fileFor(context, path) ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** Removes owned images that no live entry references. */
    fun sweepOrphans(context: Context, referenced: Set<String>) {
        val dir = directory(context)
        val children = dir.listFiles() ?: return
        children.forEach { file ->
            if (file.isFile && file.absolutePath !in referenced) {
                runCatching { file.delete() }
            }
        }
    }

    // ── Thumbnails ─────────────────────────────────────────────────────────────

    private val thumbCache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failedThumbnailKeys = LinkedHashSet<String>()
    private val bridgeThumbnailAttempts = LinkedHashSet<String>()
    private val loggedThumbnailFailures = LinkedHashSet<String>()

    fun cachedThumbnail(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        return thumbCache.get(path)
    }

    fun cacheThumbnail(path: String?, bitmap: Bitmap?) {
        if (path.isNullOrEmpty() || bitmap == null) return
        synchronized(failedThumbnailKeys) { failedThumbnailKeys.remove(path) }
        thumbCache.put(path, bitmap)
    }

    fun markBridgeThumbnailAttempt(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        synchronized(bridgeThumbnailAttempts) {
            if (!bridgeThumbnailAttempts.add(path)) return false
            if (bridgeThumbnailAttempts.size > 64) bridgeThumbnailAttempts.remove(bridgeThumbnailAttempts.first())
            return true
        }
    }

    fun reportThumbnailUnavailable(path: String?) {
        if (path.isNullOrEmpty()) return
        val shouldLog = synchronized(loggedThumbnailFailures) {
            if (!loggedThumbnailFailures.add(path)) false
            else {
                if (loggedThumbnailFailures.size > 64) loggedThumbnailFailures.remove(loggedThumbnailFailures.first())
                true
            }
        }
        if (shouldLog) Log.w("EdgeX", "clipboard image preview unavailable after provider fallback")
    }

    /**
     * Decodes a bounded thumbnail off the calling thread. Sampling happens
     * before decode, so a 4000x3000 screenshot is never fully materialised.
     */
    fun loadThumbnail(context: Context, entry: ClipEntry, targetPx: Int): Bitmap? {
        val key = entry.imagePath ?: entry.sourcePath ?: entry.imageUri ?: return null
        cachedThumbnail(key)?.let { return it }
        synchronized(failedThumbnailKeys) {
            if (key in failedThumbnailKeys) return null
        }

        val target = targetPx.coerceIn(1, MAX_DIMENSION)
        val bitmap = entry.imagePath
            ?.let { fileFor(context, it) }
            ?.let { file -> decodeThumbnail(target) { FileInputStream(file) } }
            ?: entry.sourcePath
                ?.takeIf(ScreenshotMediaFilter::isSafeSourcePath)
                ?.let(::File)
                ?.takeIf { it.isFile }
                ?.let { file -> decodeThumbnail(target) { FileInputStream(file) } }
            ?: entry.imageUri
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme == "content" }
                ?.let { uri ->
                    decodeThumbnail(target) { context.contentResolver.openInputStream(uri) }
                }

        if (bitmap == null) {
            synchronized(failedThumbnailKeys) {
                if (failedThumbnailKeys.size >= 64) failedThumbnailKeys.remove(failedThumbnailKeys.first())
                failedThumbnailKeys.add(key)
            }
            return null
        }
        synchronized(failedThumbnailKeys) { failedThumbnailKeys.remove(key) }
        thumbCache.put(key, bitmap)
        return bitmap
    }

    private fun decodeThumbnail(targetPx: Int, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open()?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { open()?.use { BitmapFactory.decodeStream(it, null, options) } }
            .getOrNull()
    }

    fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 > targetPx) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    fun clearThumbnailCache() {
        thumbCache.evictAll()
        synchronized(failedThumbnailKeys) { failedThumbnailKeys.clear() }
        synchronized(bridgeThumbnailAttempts) { bridgeThumbnailAttempts.clear() }
        synchronized(loggedThumbnailFailures) { loggedThumbnailFailures.clear() }
    }

    // ── Clipboard URI for restoring an image ───────────────────────────────────

    /**
     * Builds the stable URI served by [ClipboardImageProvider]. ClipboardService
     * grants/revokes readers with the clip lifetime; share intents carry their
     * own temporary URI grant flag.
     */
    fun shareUri(context: Context, path: String?): Uri? {
        val file = fileFor(context, path) ?: return null
        return runCatching {
            val directoryPath = directory(context).canonicalPath
            if (file.canonicalFile.parentFile?.canonicalPath != directoryPath) return null
            Uri.Builder()
                .scheme("content")
                .authority("${BuildConfig.APPLICATION_ID}.clipboardimages")
                .appendPath("images")
                .appendPath(file.name)
                .build()
        }.getOrNull()
    }

    /** Human label shown for an image clip row / preview. */
    fun displayLabel(context: Context, entry: ClipEntry): String = runCatching {
        val moduleContext = moduleDeviceProtectedContext(context)
        if (entry.isScreenshot) {
            moduleContext.getString(R.string.clipboard_image_screenshot)
        } else {
            when (entry.mimeType) {
                "image/png" -> moduleContext.getString(R.string.clipboard_image_png)
                "image/jpeg", "image/jpg" -> moduleContext.getString(R.string.clipboard_image_jpeg)
                "image/gif" -> moduleContext.getString(R.string.clipboard_image_gif)
                "image/webp" -> moduleContext.getString(R.string.clipboard_image_webp)
                else -> moduleContext.getString(R.string.clipboard_image)
            }
        }
    }.getOrDefault("Image")

    private val OWNED_IMAGE_NAME = Regex("clip-[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9]{1,8}")
}
