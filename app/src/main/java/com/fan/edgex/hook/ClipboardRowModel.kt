package com.fan.edgex.hook

import com.fan.edgex.config.ClipEntry
import com.fan.edgex.config.ClipType
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Icon bucket for a non-clip row (kept free of Android resource ids). */
internal enum class RowIcon { PIN, FOLDER, CLOCK, CLIPBOARD, NONE }

/** Day bucket used to group ordinary history. */
internal enum class DayBucket { TODAY, YESTERDAY, EARLIER }

/**
 * Immutable, flattened row model for the clipboard RecyclerView.
 *
 * This file has no Android dependencies so the search/filter/flatten logic is
 * unit-testable on the JVM. Every [build] call returns a brand new list that is
 * handed to the adapter; callers must never mutate a list after submitting it.
 */
internal sealed class DisplayRow {

    abstract val stableKey: String

    data class Header(
        val key: String,
        val title: String,
        val count: Int,
        val group: String?,
        val collapsed: Boolean,
        val icon: RowIcon
    ) : DisplayRow() {
        override val stableKey: String get() = "header:$key"
    }

    data class Clip(
        val entry: ClipEntry,
        val kind: ClipboardUiKit.ClipKind,
        val meta: String
    ) : DisplayRow() {
        val text: String get() = entry.text
        val pinned: Boolean get() = entry.pinned
        val group: String? get() = entry.group
        val isImage: Boolean get() = entry.type == ClipType.IMAGE

        /**
         * Text clips are identified by text (history dedups by text); image
         * clips by their owned file path. Two rows can never share one.
         */
        override val stableKey: String get() = "clip:${entry.id}"
    }

    data class Message(
        val title: String,
        val secondary: String?,
        val icon: RowIcon
    ) : DisplayRow() {
        override val stableKey: String get() = "message:$title"
    }
}

/** Localized labels injected so the row model stays free of Android resources. */
internal class RowLabels(
    val pinned: String,
    val today: String,
    val yesterday: String,
    val earlier: String,
    val history: String,
    val groupEmpty: String,
    val empty: String,
    val emptyHint: String,
    val noResults: String,
    val historyEmpty: String
)

internal object ClipboardRowModel {

    /** Normalized value cached per clip and reused for every keystroke. */
    fun searchKey(text: String): String = text.lowercase(Locale.ROOT)

    fun dayBucket(
        timestamp: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): DayBucket {
        if (timestamp <= 0L) return DayBucket.EARLIER
        val startOfToday = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (timestamp >= startOfToday.timeInMillis) return DayBucket.TODAY
        startOfToday.add(Calendar.DAY_OF_YEAR, -1)
        return if (timestamp >= startOfToday.timeInMillis) DayBucket.YESTERDAY else DayBucket.EARLIER
    }

    /**
     * Builds the visible rows for [entries] given the current [query] and the
     * set of collapsed section [collapsed]. Collapsed sections contribute only
     * their header, never child clip rows.
     */
    fun build(
        entries: List<ClipEntry>,
        groups: List<String>,
        query: String,
        collapsed: Set<String>,
        labels: RowLabels,
        metaOf: (ClipEntry) -> String,
        kindOf: (ClipEntry) -> ClipboardUiKit.ClipKind,
        searchKeyOf: (ClipEntry) -> String,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): List<DisplayRow> {
        val rows = ArrayList<DisplayRow>(entries.size + groups.size + 4)
        if (entries.isEmpty()) {
            rows += DisplayRow.Message(labels.empty, labels.emptyHint, RowIcon.CLIPBOARD)
            return rows
        }

        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isNotEmpty()) {
            val matches = entries.filter { searchKeyOf(it).contains(normalizedQuery) }
            if (matches.isEmpty()) {
                rows += DisplayRow.Message(labels.noResults, null, RowIcon.NONE)
                return rows
            }
            addSection(
                rows, "search:pinned", RowIcon.PIN, labels.pinned,
                matches.filter { it.pinned }, collapsed, metaOf, kindOf
            )
            groups.forEach { name ->
                val items = matches.filter { !it.pinned && it.group == name }
                if (items.isNotEmpty()) {
                    addSection(rows, "search:group:$name", RowIcon.FOLDER, name, items, collapsed, metaOf, kindOf)
                }
            }
            val rest = matches.filter { !it.pinned && (it.group == null || it.group !in groups) }
            if (rest.isNotEmpty()) {
                addSection(rows, "search:other", RowIcon.CLOCK, labels.history, rest, collapsed, metaOf, kindOf)
            }
            return rows
        }

        addSection(
            rows, "pinned", RowIcon.PIN, labels.pinned,
            entries.filter { it.pinned }, collapsed, metaOf, kindOf
        )
        groups.forEach { name ->
            val items = entries.filter { !it.pinned && it.group == name }
            val key = "group:$name"
            val isCollapsed = key in collapsed
            // User groups stay visible even when empty so they can be managed.
            rows += DisplayRow.Header(
                key = key,
                title = name,
                count = items.size,
                group = name,
                collapsed = isCollapsed,
                icon = RowIcon.FOLDER
            )
            if (isCollapsed) return@forEach
            if (items.isEmpty()) {
                rows += DisplayRow.Message(labels.groupEmpty, null, RowIcon.NONE)
            } else {
                items.forEach { entry ->
                    rows += DisplayRow.Clip(
                        entry = entry,
                        kind = kindOf(entry),
                        meta = metaOf(entry)
                    )
                }
            }
        }

        val rest = entries.filter { !it.pinned && (it.group == null || it.group !in groups) }
        val today = ArrayList<ClipEntry>()
        val yesterday = ArrayList<ClipEntry>()
        val earlier = ArrayList<ClipEntry>()
        rest.forEach { entry ->
            when (dayBucket(entry.timestamp, now, zone)) {
                DayBucket.TODAY -> today += entry
                DayBucket.YESTERDAY -> yesterday += entry
                DayBucket.EARLIER -> earlier += entry
            }
        }
        addSection(rows, "today", RowIcon.CLOCK, labels.today, today, collapsed, metaOf, kindOf)
        addSection(rows, "yesterday", RowIcon.CLOCK, labels.yesterday, yesterday, collapsed, metaOf, kindOf)
        addSection(rows, "earlier", RowIcon.CLOCK, labels.earlier, earlier, collapsed, metaOf, kindOf)
        if (rest.isEmpty()) {
            rows += DisplayRow.Message(labels.historyEmpty, labels.emptyHint, RowIcon.NONE)
        }
        return rows
    }

    private fun addSection(
        rows: MutableList<DisplayRow>,
        key: String,
        icon: RowIcon,
        title: String,
        items: List<ClipEntry>,
        collapsed: Set<String>,
        metaOf: (ClipEntry) -> String,
        kindOf: (ClipEntry) -> ClipboardUiKit.ClipKind
    ) {
        if (items.isEmpty()) return
        val isCollapsed = key in collapsed
        rows += DisplayRow.Header(
            key = key,
            title = title,
            count = items.size,
            group = if (icon == RowIcon.FOLDER) title else null,
            collapsed = isCollapsed,
            icon = icon
        )
        if (isCollapsed) return
        items.forEach { entry ->
            rows += DisplayRow.Clip(
                entry = entry,
                kind = kindOf(entry),
                meta = metaOf(entry)
            )
        }
    }
}
