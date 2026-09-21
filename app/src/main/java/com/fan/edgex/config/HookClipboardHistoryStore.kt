package com.fan.edgex.config

import com.fan.edgex.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Clipboard history persistence shared between the EdgeX app process and the
 * injected system_server hook. The file lives under /data/system/edgex so both
 * processes can read it; the hook process (uid 1000) writes it atomically.
 */
object HookClipboardHistoryStore {
    private const val HISTORY_FILE = "clipboard_history.properties"
    private const val TEMP_FILE = "$HISTORY_FILE.tmp"

    fun readForHook(maxItems: Int): ClipboardHistorySnapshot =
        read(historyFileForHook(), maxItems)

    fun writeForHook(snapshot: ClipboardHistorySnapshot): Boolean =
        write(systemHistoryFile(), snapshot)

    private fun historyFileForHook(): File =
        systemHistoryFile().takeIf { it.isFile && it.canRead() }
            ?: File("/data/user_de/0/${BuildConfig.APPLICATION_ID}/files/$HISTORY_FILE")

    private fun read(file: File, maxItems: Int): ClipboardHistorySnapshot {
        if (!file.isFile || !file.canRead()) return ClipboardHistorySnapshot()
        return runCatching {
            val properties = Properties()
            FileInputStream(file).use(properties::load)
            val values = properties.stringPropertyNames()
                .associateWith { properties.getProperty(it, "") }
            ClipboardHistoryCodec.decode(values, maxItems)
        }.getOrDefault(ClipboardHistorySnapshot())
    }

    private fun write(file: File, snapshot: ClipboardHistorySnapshot): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()

            val properties = Properties()
            ClipboardHistoryCodec.encode(snapshot).forEach { (key, value) ->
                properties.setProperty(key, value)
            }

            val temp = File(file.parentFile, TEMP_FILE)
            FileOutputStream(temp).use { out ->
                properties.store(out, "EdgeX clipboard history")
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            makeHookReadable(file)
            true
        }.getOrDefault(false)
    }

    private fun systemHistoryFile(): File =
        File("/data/system/edgex/$HISTORY_FILE")

    private fun makeHookReadable(file: File) {
        file.setReadable(true, false)
        file.setWritable(true, true)

        file.parentFile?.let { filesDir ->
            filesDir.setExecutable(true, false)
            filesDir.setReadable(true, false)
        }

        file.parentFile?.parentFile?.setExecutable(true, false)
    }
}
