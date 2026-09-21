package com.fan.edgex.config

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import com.fan.edgex.IShellCallback
import com.fan.edgex.IShellExecutor
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShellExecutorService : Service() {

    private val stub = object : IShellExecutor.Stub() {
        override fun execute(command: String, runAsRoot: Boolean, callback: IShellCallback?) {
            if (!isSystemServerCaller()) {
                callback?.onResult(false, "")
                return
            }
            Thread {
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
            }.start()
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
            Thread {
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
            }.start()
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
            Thread {
                var pending: File? = null
                try {
                    val manifest = manifestJson
                        ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024 }
                        ?: throw IOException("invalid backup manifest")
                    val name = archiveName
                        ?.takeIf { BACKUP_NAME.matches(it) }
                        ?: throw IOException("invalid backup name")
                    val paths = imagePaths.orEmpty().distinct()
                    if (paths.size > 100) throw IOException("too many backup images")

                    val directory = File(
                        createDeviceProtectedStorageContext().filesDir,
                        "clipboard_backups"
                    )
                    if (!directory.isDirectory && !directory.mkdirs()) {
                        throw IOException("backup directory unavailable")
                    }
                    val archive = File(directory, name)
                    pending = File(directory, "$name.tmp")
                    pending.delete()
                    val seenNames = HashSet<String>()
                    ZipOutputStream(pending.outputStream().buffered()).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        paths.forEach { path ->
                            val image = ClipboardImageStore.fileFor(
                                this@ShellExecutorService,
                                path
                            )
                                ?: throw IOException("invalid backup image")
                            if (!seenNames.add(image.name)) return@forEach
                            zip.putNextEntry(ZipEntry("images/${image.name}"))
                            image.inputStream().buffered().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    if (!pending.renameTo(archive)) {
                        pending.copyTo(archive, overwrite = true)
                        pending.delete()
                    }
                    if (!archive.isFile || archive.length() == 0L) {
                        throw IOException("backup archive is empty")
                    }
                    callback?.onResult(true, archive.absolutePath)
                } catch (e: Exception) {
                    pending?.delete()
                    callback?.onResult(false, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                }
            }.start()
        }
    }

    override fun onBind(intent: Intent): IBinder = stub

    private fun isSystemServerCaller(): Boolean {
        val callerUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callerUid)
        return callerUid == Process.SYSTEM_UID && callerPackages?.contains("android") == true
    }

    private companion object {
        val BACKUP_NAME = Regex("edgex-clipboard-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.zip")
    }
}
