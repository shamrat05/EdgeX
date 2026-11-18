package com.fan.edgex.config

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ClipboardBackupArchive {
    const val MAX_MANIFEST_BYTES = 4_000_000
    const val MAX_IMAGES = 1_000
    const val MAX_BYTES = 512L * 1024 * 1024
    const val BACKUP_DIRECTORY = "clipboard_backups"
    const val IMPORT_DIRECTORY = "clipboard_imports"
    val backupName = Regex("edgex-clipboard-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.zip")
    val importName = Regex("edgex-import-[0-9]+\\.(zip|json)")

    fun writeZip(destination: File, manifest: ByteArray, images: List<File>) {
        if (manifest.size !in 1..MAX_MANIFEST_BYTES) throw IOException("invalid backup manifest size")
        if (images.size > MAX_IMAGES || manifest.size + images.sumOf(File::length) > MAX_BYTES) {
            throw IOException("backup exceeds size limit")
        }
        ZipOutputStream(destination.outputStream().buffered(64 * 1024)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            images.forEach { image ->
                zip.putNextEntry(ZipEntry("images/${image.name}"))
                image.inputStream().buffered(64 * 1024).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
