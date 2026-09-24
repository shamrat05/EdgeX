package com.fan.edgex.hook

import com.fan.edgex.config.ClipEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ClipboardRowModelTest {

    private val labels = RowLabels(
        pinned = "Pinned",
        today = "Today",
        yesterday = "Yesterday",
        earlier = "Earlier",
        history = "History",
        groupEmpty = "Empty group",
        empty = "Empty",
        emptyHint = "Hint",
        noResults = "No results",
        historyEmpty = "No history"
    )

    private val meta: (ClipEntry) -> String = { "t" }
    private val kind: (ClipEntry) -> ClipboardUiKit.ClipKind = { ClipboardUiKit.ClipKind.NOTE }
    private val searchKey: (ClipEntry) -> String = { ClipboardRowModel.searchKey(it.text) }

    private fun build(
        entries: List<ClipEntry>,
        groups: List<String> = emptyList(),
        query: String = "",
        collapsed: Set<String> = emptySet(),
        now: Long = 1_700_000_000_000L
    ): List<DisplayRow> = ClipboardRowModel.build(
        entries = entries,
        groups = groups,
        query = query,
        collapsed = collapsed,
        labels = labels,
        metaOf = meta,
        kindOf = kind,
        searchKeyOf = searchKey,
        now = now,
        zone = TimeZone.getTimeZone("UTC")
    )

    private fun clips(rows: List<DisplayRow>): List<DisplayRow.Clip> = rows.filterIsInstance<DisplayRow.Clip>()

    @Test
    fun `empty history yields a single empty message`() {
        val rows = build(emptyList())
        assertEquals(1, rows.size)
        assertTrue(rows[0] is DisplayRow.Message)
        assertEquals("Empty", (rows[0] as DisplayRow.Message).title)
    }

    @Test
    fun `search is case insensitive substring matching`() {
        val entries = listOf(
            ClipEntry("gh repo clone fcmfcm1999/EdgeX"),
            ClipEntry("npm install --save-dev typescript"),
            ClipEntry("Remember to buy MILK")
        )
        val rows = build(entries, query = "Repo")
        assertEquals(listOf("gh repo clone fcmfcm1999/EdgeX"), clips(rows).map { it.text })

        val rows2 = build(entries, query = "milk")
        assertEquals(listOf("Remember to buy MILK"), clips(rows2).map { it.text })
    }

    @Test
    fun `search matches unicode text`() {
        val entries = listOf(ClipEntry("你好世界"), ClipEntry("hello"))
        val rows = build(entries, query = "世界")
        assertEquals(listOf("你好世界"), clips(rows).map { it.text })
    }

    @Test
    fun `search with zero matches yields the no results message`() {
        val rows = build(listOf(ClipEntry("alpha")), query = "zzz")
        assertEquals(1, rows.size)
        assertEquals("No results", (rows[0] as DisplayRow.Message).title)
    }

    @Test
    fun `search scans a large history and finds every match`() {
        val entries = (1..250).map { ClipEntry("entry number $it common") }
        val rows = build(entries, query = "common")
        assertEquals(250, clips(rows).size)
    }

    @Test
    fun `normal history shows pinned and ungrouped clips but excludes grouped clips`() {
        val entries = listOf(
            ClipEntry("pinned one", pinned = true, group = "Work"),
            ClipEntry("grouped one", group = "Work"),
            ClipEntry("today one", timestamp = 1_700_000_000_000L),
            ClipEntry("yesterday one", timestamp = 1_699_900_000_000L),
            ClipEntry("earlier one", timestamp = 1_690_000_000_000L)
        )
        val rows = build(entries, groups = listOf("Work"))
        val texts = clips(rows).map { it.text }
        assertEquals(listOf("pinned one", "today one", "yesterday one", "earlier one"), texts)
        assertEquals(4, texts.size)
        val headers = rows.filterIsInstance<DisplayRow.Header>().map { it.key }
        assertTrue(headers.contains("pinned"))
        assertTrue(headers.contains("today"))
        assertTrue(headers.contains("yesterday"))
        assertTrue(headers.contains("earlier"))
        assertFalse(headers.any { it.startsWith("group:") })
        assertEquals(1, texts.count { it == "pinned one" })
        assertEquals(0, texts.count { it == "grouped one" })
    }

    @Test
    fun `grouped unpinned clip does not appear in normal history`() {
        val entries = listOf(
            ClipEntry("grouped", group = "Work"),
            ClipEntry("plain")
        )
        val rows = build(entries, groups = listOf("Work"))
        val headers = rows.filterIsInstance<DisplayRow.Header>().map { it.key }
        assertFalse(headers.any { it.startsWith("group:") })
        assertEquals(listOf("plain"), clips(rows).map { it.text })
    }

    @Test
    fun `collapsed section contributes only its header`() {
        val entries = (1..5).map { ClipEntry("clip $it") }
        val rows = build(entries, collapsed = setOf("earlier"))
        assertTrue(rows.any { it is DisplayRow.Header && it.key == "earlier" && it.collapsed })
        assertEquals(0, clips(rows).size)
        // Only the Earlier header exists for those clips.
        val earlierHeader = rows.filterIsInstance<DisplayRow.Header>().single { it.key == "earlier" }
        assertEquals(5, earlierHeader.count)
    }

    @Test
    fun `expanded section contributes every child`() {
        val entries = (1..5).map { ClipEntry("clip $it") }
        val rows = build(entries)
        assertEquals(5, clips(rows).size)
    }

    @Test
    fun `search includes pinned and grouped clips and hides empty sections`() {
        val entries = listOf(
            ClipEntry("repo pinned", pinned = true),
            ClipEntry("repo grouped", group = "Code"),
            ClipEntry("repo plain"),
            ClipEntry("unrelated")
        )
        val rows = build(entries, groups = listOf("Code"), query = "repo")
        assertEquals(3, clips(rows).size)
        val headers = rows.filterIsInstance<DisplayRow.Header>().map { it.key }
        assertTrue(headers.contains("search:pinned"))
        assertTrue(headers.contains("search:other"))
        assertEquals(listOf("repo pinned", "repo grouped", "repo plain"), clips(rows).map { it.text })
        assertFalse(headers.any { it.contains("empty") })
    }

    @Test
    fun `stable keys are deterministic and unique per clip text`() {
        val entries = listOf(ClipEntry("a"), ClipEntry("b"), ClipEntry("a"))
        val rows = build(entries)
        val keys = clips(rows).map { it.stableKey }
        assertEquals(listOf("clip:t:a", "clip:t:b", "clip:t:a"), keys)
        val header = rows.filterIsInstance<DisplayRow.Header>().first()
        assertEquals("header:earlier", header.stableKey)
    }

    @Test
    fun `day buckets use calendar boundaries and legacy timestamps fall into earlier`() {
        val now = 1_700_000_000_000L
        val zone = TimeZone.getTimeZone("UTC")
        assertTrue(
            ClipboardRowModel.dayBucket(now - 60_000L, now, zone) == DayBucket.TODAY
        )
        assertTrue(
            ClipboardRowModel.dayBucket(now - 24 * 60 * 60_000L, now, zone) == DayBucket.YESTERDAY
        )
        assertTrue(
            ClipboardRowModel.dayBucket(now - 10L * 24 * 60 * 60_000L, now, zone) == DayBucket.EARLIER
        )
        assertTrue(ClipboardRowModel.dayBucket(0L, now, zone) == DayBucket.EARLIER)
    }

    @Test
    fun `empty user group is represented by tray empty state not normal history`() {
        val rows = build(listOf(ClipEntry("plain")), groups = listOf("Empty"))
        assertFalse(rows.filterIsInstance<DisplayRow.Header>().any { it.key == "group:Empty" })
        val groupRows = ClipboardRowModel.buildGroup(
            entries = listOf(ClipEntry("plain")), group = "Empty", emptyMessage = "Empty group",
            metaOf = meta, kindOf = kind
        )
        assertEquals(1, groupRows.size)
        assertEquals("Empty group", (groupRows.single() as DisplayRow.Message).title)
    }

    @Test
    fun `group tray excludes pinned members already shown in pinned section`() {
        val entries = listOf(
            ClipEntry("work one", group = "Work"),
            ClipEntry("pinned work", pinned = true, group = "Work"),
            ClipEntry("code", group = "Code")
        )
        val rows = ClipboardRowModel.buildGroup(entries, "Work", "Empty", meta, kind)
        assertEquals(listOf("work one"), clips(rows).map { it.text })
    }
}
