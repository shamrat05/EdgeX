package com.fan.edgex.config

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import com.fan.edgex.IShellCallback
import com.fan.edgex.IShellExecutor
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException

class ShellExecutorService : Service() {

    private val stub = object : IShellExecutor.Stub() {
        override fun execute(command: String, runAsRoot: Boolean, callback: IShellCallback?) {
            if (!isSystemServerCaller()) {
                callback?.onResult(false, "")
                return
            }
            runProtected("shell") {
                try {
                    if (runAsRoot) {
                        val result = Shell.cmd(command).exec()
                        val output = if (result.isSuccess) {
                            result.out.joinToString("\n").trim()
                        } else {
                            result.err.joinToString("\n").trim()
                        }
                        callback?.onResult(result.isSuccess, output)
                    } else {
                        val process = ProcessBuilder("sh", "-c", command)
                            .redirectErrorStream(true)
                            .start()
                        process.outputStream.close()
                        val output = process.inputStream.bufferedReader().readText().trim()
                        val exitCode = process.waitFor()
                        callback?.onResult(exitCode == 0, output)
                    }
                } catch (e: Exception) {
                    callback?.onResult(false, e.message.orEmpty())
                }
            }
        }

        override fun savePngToGallery(
            png: ParcelFileDescriptor?,
            displayName: String?,
            callback: IShellCallback?,
        ) {
            if (!isSystemServerCaller()) {
                callback?.onResult(false, "")
                png?.close()
                return
            }
            runProtected("gallery") {
                var insertedUri: android.net.Uri? = null
                try {
                    if (png == null) throw IOException("PNG pipe is null")
                    val now = System.currentTimeMillis()
                    val values = ContentValues().apply {
                        put(
                            MediaStore.Images.Media.DISPLAY_NAME,
                            displayName?.takeIf { it.isNotBlank() } ?: "Screenshot_$now.png",
                        )
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                        put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
                        put(MediaStore.Images.Media.DATE_TAKEN, now)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = contentResolver
                    insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("MediaStore insert returned null")
                    ParcelFileDescriptor.AutoCloseInputStream(png).use { input ->
                        val output = resolver.openOutputStream(insertedUri, "w")
                            ?: throw IOException("MediaStore output stream is null")
                        output.use { input.copyTo(it) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val publishValues = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        resolver.update(insertedUri, publishValues, null, null)
                    }
                    callback?.onResult(true, insertedUri.toString())
                } catch (e: Exception) {
                    insertedUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                    callback?.onResult(false, e.message.orEmpty())
                    png?.close()
                }
            }
        }

        override fun createClipboardBackup(
            manifestJson: String?,
            imagePaths: MutableList<String>?,
            archiveName: String?,
            callback: IShellCallback?,
        ) {
            if (!isSystemServerCaller()) {
                callback?.onResult(false, "caller rejected")
                return
            }
            createBackup(imagePaths, archiveName, true, callback) {
                manifestJson?.toByteArray(Charsets.UTF_8) ?: throw IOException("missing backup manifest")
            }
        }

        override fun createClipboardBackupFromStream(
            manifest: ParcelFileDescriptor?,
            imagePaths: MutableList<String>?,
            archiveName: String?,
            toDownloads: Boolean,
            callback: IShellCallback?
        ) {
            if (!isSystemServerCaller() || manifest == null) {
                manifest?.close()
                callback?.onResult(false, "backup request rejected")
                return
            }
            createBackup(imagePaths, archiveName, toDownloads, callback) {
                ParcelFileDescriptor.AutoCloseInputStream(manifest).use {
                    it.readNBytes(ClipboardBackupArchive.MAX_MANIFEST_BYTES + 1)
                }
            }
        }

        private fun createBackup(
            imagePaths: List<String>?,
            archiveName: String?,
            toDownloads: Boolean,
            callback: IShellCallback?,
            readManifest: () -> ByteArray
        ) {
            runProtected("clipboard-backup", timeoutMs = 180_000L) {
                var pending: File? = null
                var archive: File? = null
                try {
                    val manifest = readManifest()
                    val name = archiveName?.takeIf(ClipboardBackupArchive.backupName::matches)
                        ?: throw IOException("invalid backup name")
                    val paths = imagePaths.orEmpty().distinct()
                    if (paths.size > ClipboardBackupArchive.MAX_IMAGES) throw IOException("too many backup images")
                    val images = paths.map { path ->
                        ClipboardImageStore.fileFor(this@ShellExecutorService, path)
                            ?: throw IOException("invalid backup image")
                    }.distinctBy(File::getName)

                    val directory = File(
                        createDeviceProtectedStorageContext().filesDir,
                        ClipboardBackupArchive.BACKUP_DIRECTORY
                    )
                    if (!directory.isDirectory && !directory.mkdirs()) {
                        throw IOException("backup directory unavailable")
                    }
                    archive = File(directory, name)
                    pending = File(directory, "$name.tmp")
                    pending.delete()
                    ClipboardBackupArchive.writeZip(pending, manifest, images)
                    if (!pending.renameTo(archive)) {
                        pending.copyTo(archive, overwrite = true)
                        pending.delete()
                    }
                    if (!archive.isFile || archive.length() == 0L) {
                        throw IOException("backup archive is empty")
                    }
                    if (!toDownloads) {
                        callback?.onResult(true, name)
                        return@runProtected
                    }
                    val publicDirectory = "/data/media/0/Download/EdgeX"
                    val publicArchive = "$publicDirectory/$name"
                    val publicPending = "$publicArchive.tmp"
                    val exported = Shell.cmd(
                        "mkdir -p '$publicDirectory' && " +
                            "rm -f '$publicPending' && " +
                            "cp -f '${archive.absolutePath}' '$publicPending' && " +
                            "chmod 0644 '$publicPending' && " +
                            "mv -f '$publicPending' '$publicArchive' && " +
                            "test -s '$publicArchive'"
                    ).exec()
                    if (!exported.isSuccess) {
                        val error = exported.err.joinToString("\n").trim()
                        throw IOException(error.ifEmpty { "backup export failed" })
                    }
                    archive.delete()
                    callback?.onResult(true, "/sdcard/Download/EdgeX/$name")
                } catch (e: Exception) {
                    pending?.delete()
                    archive?.delete()
                    callback?.onResult(false, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = stub

    /**
     * ColorOS freezes ordinary bound app processes after roughly five seconds.
     * A bounded partial wake lock keeps only the active operation schedulable;
     * it is released immediately on completion and has a hard timeout.
     */
    private fun runProtected(name: String, timeoutMs: Long = OPERATION_WAKE_LOCK_MS, operation: () -> Unit) {
        Thread({
            val power = getSystemService(POWER_SERVICE) as PowerManager
            val wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EdgeX:$name"
            ).apply { setReferenceCounted(false) }
            try {
                wakeLock.acquire(timeoutMs)
                operation()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }, "EdgeX-$name").start()
    }

    private fun isSystemServerCaller(): Boolean {
        val callerUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callerUid)
        return callerUid == Process.SYSTEM_UID && callerPackages?.contains("android") == true
    }

    private companion object {
        const val OPERATION_WAKE_LOCK_MS = 60_000L
    }
}
