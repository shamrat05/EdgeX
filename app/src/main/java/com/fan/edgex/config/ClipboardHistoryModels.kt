package com.fan.edgex.config

/** Content kind of a clipboard record. */
enum class ClipType { TEXT, IMAGE }

/**
 * A single clipboard history record.
 *
 * Text records use [text]; image records use [imagePath] (an EdgeX-owned file)
 * and never fake a text field. [imageUri] keeps the original content:// URI of
 * the capture so the image can be re-copied to the primary clipboard later.
 * MediaStore screenshots also retain their original relative folder and name;
 * [imagePath] is only EdgeX's private history copy.
 *
 * [timestamp] is captured in the hook process when the clipboard changes.
 * Legacy records read from the version-1 properties file have no timestamp and
 * use 0L; they are shown in the "Earlier" section instead of a fabricated date.
 *
 * [sourceUid] is the calling UID captured through Binder while the clipboard
 * write is in flight. -1 means unknown.
 */
data class ClipEntry(
    val text: String = "",
    val timestamp: Long = 0L,
    val pinned: Boolean = false,
    val group: String? = null,
    val sourceUid: Int = -1,
    val type: ClipType = ClipType.TEXT,
    val imagePath: String? = null,
    val imageUri: String? = null,
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val isScreenshot: Boolean = false,
    /** Original shared-storage file for Termux/path fallback, when known. */
    val sourcePath: String? = null,
    val originalRelativePath: String? = null,
    val originalDisplayName: String? = null
) {
    /** Original provider/media URI; kept in the legacy persisted imageUri field. */
    val originalUri: String? get() = imageUri

    /** EdgeX-owned history copy; ordinary file managers cannot browse it. */
    val privateHistoryPath: String? get() = imagePath

    /** Stable identity: text for text clips, the owned file path for images. */
    val id: String
        get() = when (type) {
            ClipType.TEXT -> "t:$text"
            ClipType.IMAGE -> "i:${imagePath ?: imageUri ?: timestamp}"
        }

    /** Search key for images uses only metadata EdgeX actually knows. */
    val searchSource: String
        get() = when (type) {
            ClipType.TEXT -> text
            ClipType.IMAGE -> buildString {
                append("image")
                mimeType?.let { append(' ').append(it) }
                group?.let { append(' ').append(it) }
            }
        }
}

/** Full persisted clipboard state: entries, user groups and small UI flags. */
data class ClipboardHistorySnapshot(
    val entries: List<ClipEntry> = emptyList(),
    val groups: List<String> = emptyList(),
    val hintDismissed: Boolean = false
)

data class ClipboardHistoryMergeResult(
    val snapshot: ClipboardHistorySnapshot,
    val addedCount: Int,
    val duplicateCount: Int
)

/** Adds only clips not already present, retaining the current snapshot first. */
object ClipboardHistoryMerge {
    fun merge(
        current: ClipboardHistorySnapshot,
        restored: ClipboardHistorySnapshot,
        imageIdentity: (ClipEntry) -> String? = { null }
    ): ClipboardHistoryMergeResult {
        fun identity(entry: ClipEntry): String = when (entry.type) {
            ClipType.TEXT -> "text:${entry.text}"
            ClipType.IMAGE -> imageIdentity(entry)?.let { "image:$it" }
                ?: "image:${entry.imagePath ?: entry.imageUri ?: entry.timestamp}"
        }

        val seen = current.entries.mapTo(HashSet(), ::identity)
        val added = ArrayList<ClipEntry>()
        var duplicates = 0
        restored.entries.forEach { entry ->
            if (seen.add(identity(entry))) added += entry else duplicates++
        }

        val groups = ArrayList(current.groups)
        val groupNames = groups.mapTo(HashSet()) { it.lowercase(java.util.Locale.ROOT) }
        restored.groups.forEach { group ->
            if (groupNames.add(group.lowercase(java.util.Locale.ROOT))) groups += group
        }

        return ClipboardHistoryMergeResult(
            snapshot = ClipboardHistorySnapshot(
                entries = current.entries + added,
                groups = groups,
                hintDismissed = current.hintDismissed || restored.hintDismissed
            ),
            addedCount = added.size,
            duplicateCount = duplicates
        )
    }
}

/** Narrow filename/path heuristic for Android screenshots imported from MediaStore. */
object ScreenshotMediaFilter {
    fun matches(relativePath: String?, displayName: String?): Boolean {
        val screenshotFolders = setOf("screenshots", "screen_shots", "screen captures", "screen_captures")
        val inScreenshotFolder = relativePath.orEmpty()
            .replace('\\', '/')
            .split('/')
            .any { it.trim().lowercase(java.util.Locale.ROOT) in screenshotFolders }
        if (inScreenshotFolder) return true

        val normalizedName = displayName.orEmpty().lowercase(java.util.Locale.ROOT)
        return normalizedName.startsWith("screenshot") ||
            normalizedName.startsWith("screen_shot") ||
            normalizedName.startsWith("screen-capture") ||
            normalizedName.startsWith("screencapture")
    }

    /** Only allow root-assisted screenshot copies from shared primary storage. */
    fun isSafeSourcePath(sourcePath: String?): Boolean {
        if (sourcePath.isNullOrBlank()) return false
        val canonicalPath = runCatching { java.io.File(sourcePath).canonicalPath }.getOrNull()
            ?: return false
        val storageRoot = runCatching { java.io.File("/storage/emulated/0").canonicalPath }
            .getOrDefault("/storage/emulated/0")
        return canonicalPath.startsWith("$storageRoot/")
    }
}

/**
 * Pure serialization for [HookClipboardHistoryStore]. Keeping encode/decode
 * free of file IO makes the backward-compatibility rules unit-testable.
 *
 * Version 1 (existing installs) stored only:
 *   __version=1, count=N, entry.0=text, entry.1=text, ...
 *
 * Version 2 added per-entry fields and groups.
 *
 * Version 3 adds image entries. Version 4 marks screenshot image entries so
 * the UI can distinguish them from generic JPEG clipboard images. Version 5
 * stores a validated shared-storage source path for screenshot previews and
 * paste fallback. Version 6 adds the original MediaStore relative path/name.
 */
object ClipboardHistoryCodec {

    const val VERSION = "6"

    private const val KEY_VERSION = "__version"
    private const val KEY_COUNT = "count"
    private const val KEY_GROUPS_COUNT = "groups.count"
    private const val KEY_HINT_DISMISSED = "ui.hint_dismissed"
    private const val ENTRY_PREFIX = "entry."
    private const val GROUP_PREFIX = "group."

    fun encode(snapshot: ClipboardHistorySnapshot): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        values[KEY_VERSION] = VERSION
        values[KEY_COUNT] = snapshot.entries.size.toString()
        snapshot.entries.forEachIndexed { index, entry ->
            val prefix = "$ENTRY_PREFIX$index"
            when (entry.type) {
                ClipType.TEXT -> values["$prefix.text"] = entry.text
                ClipType.IMAGE -> {
                    values["$prefix.type"] = "image"
                    entry.imagePath?.takeIf { it.isNotEmpty() }?.let { values["$prefix.file"] = it }
                    entry.imageUri?.takeIf { it.isNotEmpty() }?.let { values["$prefix.uri"] = it }
                    entry.mimeType?.takeIf { it.isNotEmpty() }?.let { values["$prefix.mime"] = it }
                    if (entry.isScreenshot) values["$prefix.screenshot"] = "1"
                    entry.sourcePath
                        ?.takeIf(ScreenshotMediaFilter::isSafeSourcePath)
                        ?.let { values["$prefix.source_path"] = it }
                    entry.originalRelativePath
                        ?.takeIf { it.isNotBlank() && it.length <= 512 }
                        ?.let { values["$prefix.relative_path"] = it }
                    entry.originalDisplayName
                        ?.takeIf { it.isNotBlank() && it.length <= 255 }
                        ?.let { values["$prefix.display_name"] = it }
                    if (entry.width > 0) values["$prefix.w"] = entry.width.toString()
                    if (entry.height > 0) values["$prefix.h"] = entry.height.toString()
                }
            }
            values["$prefix.time"] = entry.timestamp.toString()
            if (entry.pinned) values["$prefix.pinned"] = "1"
            entry.group?.takeIf { it.isNotEmpty() }?.let { values["$prefix.group"] = it }
            if (entry.sourceUid >= 0) values["$prefix.uid"] = entry.sourceUid.toString()
        }
        values[KEY_GROUPS_COUNT] = snapshot.groups.size.toString()
        snapshot.groups.forEachIndexed { index, name ->
            values["$GROUP_PREFIX$index"] = name
        }
        if (snapshot.hintDismissed) values[KEY_HINT_DISMISSED] = "1"
        return values
    }

    /**
     * Decodes a properties map. Pinned entries are always kept; ordinary
     * entries are capped at [maxItems]. Version-1 entries (bare `entry.N` keys)
     * are accepted as text-only legacy records, and image entries without a
     * readable owned file are dropped rather than shown as broken rows.
     */
    fun decode(
        values: Map<String, String>,
        maxItems: Int,
        imageExists: (String) -> Boolean = { true }
    ): ClipboardHistorySnapshot {
        val count = values[KEY_COUNT]?.toIntOrNull() ?: 0
        val entries = ArrayList<ClipEntry>(minOf(count, maxItems))
        var unpinned = 0
        for (index in 0 until count) {
            val prefix = "$ENTRY_PREFIX$index"
            val isImage = values["$prefix.type"] == "image"
            val entry: ClipEntry? = if (isImage) {
                val path = values["$prefix.file"]?.takeIf { it.isNotEmpty() }
                val uri = values["$prefix.uri"]?.takeIf { it.isNotEmpty() }
                val fileOk = path == null || imageExists(path)
                val storedUid = values["$prefix.uid"]?.toIntOrNull()
                val isScreenshot = values["$prefix.screenshot"] == "1" ||
                        (values["$prefix.screenshot"] == null &&
                                (storedUid == null || storedUid == -1) &&
                                uri?.startsWith("content://media/external/images/") == true)
                if (path == null && uri == null) {
                    null
                } else if (!fileOk) {
                    // The owned image vanished: keep nothing rather than show a
                    // broken preview row.
                    null
                } else {
                    ClipEntry(
                        type = ClipType.IMAGE,
                        imagePath = path,
                        imageUri = uri,
                        mimeType = values["$prefix.mime"]?.takeIf { it.isNotEmpty() },
                        width = values["$prefix.w"]?.toIntOrNull() ?: 0,
                        height = values["$prefix.h"]?.toIntOrNull() ?: 0,
                        isScreenshot = isScreenshot,
                        sourcePath = values["$prefix.source_path"]
                            ?.takeIf(ScreenshotMediaFilter::isSafeSourcePath),
                        originalRelativePath = values["$prefix.relative_path"]
                            ?.takeIf { it.isNotBlank() && it.length <= 512 },
                        originalDisplayName = values["$prefix.display_name"]
                            ?.takeIf { it.isNotBlank() && it.length <= 255 }
                    )
                }
            } else {
                val text = (values["$prefix.text"] ?: values[prefix])
                    ?.takeIf { it.isNotEmpty() }
                text?.let { ClipEntry(text = it) }
            }
            if (entry == null) continue

            val pinned = values["$prefix.pinned"] == "1"
            if (!pinned) {
                if (unpinned >= maxItems) continue
                unpinned++
            }
            entries += entry.copy(
                timestamp = values["$prefix.time"]?.toLongOrNull() ?: 0L,
                pinned = pinned,
                group = values["$prefix.group"]?.takeIf { it.isNotEmpty() },
                sourceUid = values["$prefix.uid"]?.toIntOrNull() ?: -1
            )
        }
        val groupCount = values[KEY_GROUPS_COUNT]?.toIntOrNull() ?: 0
        val groups = (0 until groupCount).mapNotNull { index ->
            values["$GROUP_PREFIX$index"]?.takeIf { it.isNotEmpty() }
        }
        return ClipboardHistorySnapshot(
            entries = entries,
            groups = groups,
            hintDismissed = values[KEY_HINT_DISMISSED] == "1"
        )
    }
}
