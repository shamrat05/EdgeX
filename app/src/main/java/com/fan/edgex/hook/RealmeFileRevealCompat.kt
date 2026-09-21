package com.fan.edgex.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File

/** Verified reveal-in-folder bridge for the installed ColorOS My Files build. */
internal object RealmeFileRevealCompat {
    private const val PACKAGE = "com.coloros.filemanager"
    private const val ACTIVITY = "com.oplus.filebrowser.FileBrowserActivity"
    private const val ACTION = "oppo.filemanager.intent.action.BROWSER_FILE"
    private const val CURRENT_DIR = "CurrentDir"

    // This private contract was inspected on the installed 16.4.12 build only.
    private const val INSPECTED_VERSION_CODE = 16004012L

    fun reveal(context: Context, originalFilePath: String?, displayName: String?): Boolean {
        if (originalFilePath.isNullOrBlank()) return false
        return runCatching {
            val file = File(originalFilePath).canonicalFile
            // This runs inside system_server. FUSE may deny File.isFile even
            // though the MediaStore row was just opened successfully, so use
            // the already validated shared-storage path instead of probing it.
            if (!com.fan.edgex.config.ScreenshotMediaFilter.isSafeSourcePath(file.path)) {
                return false
            }
            if (!displayName.isNullOrBlank() && file.name != displayName) return false
            val packageInfo = context.packageManager.getPackageInfo(PACKAGE, 0)
            if (packageInfo.longVersionCode != INSPECTED_VERSION_CODE) return false
            val component = ComponentName(PACKAGE, ACTIVITY)
            // Resolve the exact inspected exported activity. The private action
            // is valid even if a ROM's resolver omits it from default queries.
            val activity = context.packageManager.getActivityInfo(component, 0)
            if (!activity.exported || !activity.enabled) return false

            val intent = Intent(ACTION).setComponent(component).apply {
                putExtra(CURRENT_DIR, file.path)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure { error ->
            de.robv.android.xposed.XposedBridge.log(
                "EdgeX: ColorOS file reveal failed: ${error.javaClass.simpleName}"
            )
        }.getOrDefault(false)
    }
}
