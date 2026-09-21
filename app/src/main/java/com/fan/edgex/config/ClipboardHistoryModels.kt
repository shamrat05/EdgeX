package com.fan.edgex.config

/**
 * A single clipboard history record.
 *
 * [timestamp] is captured in the hook process when the clipboard changes.
 * Legacy records read from the version-1 properties file have no timestamp and
 * use 0L; they are shown in the "Earlier" section instead of a fabricated date.
 *
 * [sourceUid] is the calling UID captured through Binder while the clipboard
 * write is in flight. -1 means unknown.
 */
data class ClipEntry(
    val text: String,
    val timestamp: Long = 0L,
    val pinned: Boolean = false,
    val group: String? = null,
    val sourceUid: Int = -1
)

/** Full persisted clipboard state: entries, user groups and small UI flags. */
data class ClipboardHistorySnapshot(
    val entries: List<ClipEntry> = emptyList(),
    val groups: List<String> = emptyList(),
    val hintDismissed: Boolean = false
)

/**
 * Pure serialization for [HookClipboardHistoryStore]. Keeping encode/decode
 * free of file IO makes the backward-compatibility rules unit-testable.
 *
 * Version 1 (existing installs) stored only:
 *   __version=1, count=N, entry.0=text, entry.1=text, ...
 *
 * Version 2 adds optional per-entry fields. The text key keeps the legacy
 * `entry.N` name so old files stay readable and new files degrade gracefully.
 */
object ClipboardHistoryCodec {

    const val VERSION = "2"

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
            values["$prefix.text"] = entry.text
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
     * entries are capped at [maxItems]. Version-1 entries (no `.text` key)
     * are accepted as text-only legacy records.
     */
    fun decode(values: Map<String, String>, maxItems: Int): ClipboardHistorySnapshot {
        val count = values[KEY_COUNT]?.toIntOrNull() ?: 0
        val entries = ArrayList<ClipEntry>(minOf(count, maxItems))
        var unpinned = 0
        for (index in 0 until count) {
            val prefix = "$ENTRY_PREFIX$index"
            val text = (values["$prefix.text"] ?: values[prefix])
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            val pinned = values["$prefix.pinned"] == "1"
            if (!pinned) {
                if (unpinned >= maxItems) continue
                unpinned++
            }
            entries += ClipEntry(
                text = text,
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
