package com.fan.edgex.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.fan.edgex.BuildConfig
import java.io.File
import java.io.FileNotFoundException

/** Serves only generated clipboard image files from the app's device-protected directory. */
class ClipboardImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = resolveImage(uri)?.let(ClipboardImageStore::mimeFor)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolveImage(uri) ?: return null
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only clipboard image")
        val file = resolveImage(uri) ?: throw FileNotFoundException("clipboard image unavailable")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun resolveImage(uri: Uri): File? {
        if (uri.scheme != "content" || uri.authority != "${BuildConfig.APPLICATION_ID}.clipboardimages") {
            return null
        }
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != "images") return null
        val name = parts[1]
        if (!FILE_NAME.matches(name)) return null

        val directory = context?.let(ClipboardImageStore::directory) ?: return null
        return runCatching {
            val canonicalDirectory = directory.canonicalFile
            val file = File(canonicalDirectory, name).canonicalFile
            if (file.parentFile != canonicalDirectory || !file.isFile ||
                !ClipboardImageStore.canStore(file.length())
            ) null else file
        }.getOrNull()
    }

    private companion object {
        val FILE_NAME = Regex("clip-[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9]{1,8}")
    }
}
