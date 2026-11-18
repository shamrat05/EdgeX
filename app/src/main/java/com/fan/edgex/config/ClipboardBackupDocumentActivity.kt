package com.fan.edgex.config

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.TextView
import android.widget.Toast
import com.fan.edgex.IShellCallback
import com.fan.edgex.R
import java.io.File
import java.io.IOException

/** Moves one backup between the EdgeX archive store and a user-selected document. */
class ClipboardBackupDocumentActivity : Activity() {
    companion object {
        const val EXTRA_ARCHIVE = "clipboard_backup_archive"
        const val EXTRA_CALLBACK = "clipboard_backup_callback"
        private const val PICK_DOCUMENT = 1
    }

    private val archiveName by lazy { intent.getStringExtra(EXTRA_ARCHIVE) }
    private val callback by lazy {
        IShellCallback.Stub.asInterface(intent.extras?.getBinder(EXTRA_CALLBACK))
    }

    private fun exportFile(): File? = archiveName
        ?.takeIf(ClipboardBackupArchive.backupName::matches)
        ?.let { File(File(createDeviceProtectedStorageContext().filesDir, ClipboardBackupArchive.BACKUP_DIRECTORY), it) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val saving = archiveName != null
        if (saving && exportFile()?.isFile != true || !saving && callback == null) {
            finish()
            return
        }

        val picker = Intent(if (saving) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (saving) {
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, archiveName)
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip", "application/x-zip-compressed",
                    "application/json", "application/octet-stream", "text/plain"
                ))
            }
        }

        try {
            Toast.makeText(this, R.string.clipboard_choose_drive, Toast.LENGTH_SHORT).show()
            startActivityForResult(picker, PICK_DOCUMENT)
        } catch (_: Exception) {
            exportFile()?.delete()
            showFailure(saving)
            finish()
        }
    }

    @Deprecated("Uses the platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_DOCUMENT) return
        val uri = data?.data?.takeIf { resultCode == RESULT_OK }
        if (uri == null) {
            exportFile()?.delete()
            finish()
            return
        }

        if (archiveName != null) {
            writeDocument(uri)
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.clipboard_restore_title)
                .setMessage(R.string.clipboard_restore_confirm)
                .setNegativeButton(R.string.clipboard_cancel) { _, _ -> finish() }
                .setPositiveButton(R.string.clipboard_restore) { _, _ -> readDocument(uri) }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun showProgress(label: Int) {
        setContentView(TextView(this).apply {
            text = getString(label)
            gravity = android.view.Gravity.CENTER
        })
    }

    private fun writeDocument(uri: Uri) {
        showProgress(R.string.clipboard_drive_saving)
        Thread {
            val archive = exportFile()
            val result = runCatching {
                checkNotNull(archive).inputStream().buffered().use { input ->
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("document is not writable")
                    output.use { input.copyTo(it) }
                }
            }
            if (result.isFailure) runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
            archive?.delete()
            runOnUiThread {
                if (result.isSuccess) {
                    Toast.makeText(this, R.string.clipboard_drive_backup_done, Toast.LENGTH_SHORT).show()
                } else showFailure(saving = true)
                finish()
            }
        }.start()
    }

    private fun readDocument(uri: Uri) {
        showProgress(R.string.clipboard_drive_reading)
        Thread {
            var staged: File? = null
            val result = runCatching {
                val directory = File(createDeviceProtectedStorageContext().filesDir, ClipboardBackupArchive.IMPORT_DIRECTORY)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("import directory unavailable")
                val stream = contentResolver.openInputStream(uri)?.buffered()
                    ?: throw IOException("document is not readable")
                stream.use { input ->
                    input.mark(2)
                    val zip = input.read() == 0x50 && input.read() == 0x4b
                    input.reset()
                    val file = File.createTempFile("edgex-import-", if (zip) ".zip" else ".json", directory)
                    staged = file
                    val limit = if (zip) ClipboardBackupArchive.MAX_BYTES + 1024 * 1024
                        else ClipboardBackupArchive.MAX_MANIFEST_BYTES.toLong()
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > limit) throw IOException("backup exceeds size limit")
                            output.write(buffer, 0, count)
                        }
                        if (total == 0L) throw IOException("empty backup")
                    }
                    checkNotNull(callback).onResult(true, file.absolutePath)
                }
            }
            if (result.isFailure) staged?.delete()
            runOnUiThread {
                if (result.isFailure) showFailure(saving = false)
                finish()
            }
        }.start()
    }

    private fun showFailure(saving: Boolean) {
        Toast.makeText(
            this, if (saving) R.string.clipboard_backup_failed else R.string.clipboard_restore_failed,
            Toast.LENGTH_LONG
        ).show()
    }
}
