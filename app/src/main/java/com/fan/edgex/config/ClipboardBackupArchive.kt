package com.fan.edgex.config

internal object ClipboardBackupArchive {
    const val MAX_MANIFEST_BYTES = 4_000_000
    const val MAX_IMAGES = 1_000
    const val MAX_BYTES = 512L * 1024 * 1024
    const val BACKUP_DIRECTORY = "clipboard_backups"
    const val IMPORT_DIRECTORY = "clipboard_imports"
    val backupName = Regex("edgex-clipboard-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.zip")
    val importName = Regex("edgex-import-[0-9]+\\.(zip|json)")
}
