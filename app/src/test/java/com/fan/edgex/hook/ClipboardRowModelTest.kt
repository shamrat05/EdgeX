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
    fun `pinned clips are not duplicated in group or chronological history`() {
        val entries = listOf(
            ClipEntry("pinned one", pinned = true, group = "Work"),
            ClipEntry("grouped one", group = "Work"),
            ClipEntry("plain one")
        )
        val rows = build(entries, groups = listOf("Work"))
        val texts = clips(rows).map { it.text }
        assertEquals(listOf("pinned one", "grouped one", "plain one"), texts)
        assertEquals(3, texts.size)
        // Pinned clip appears exactly once (in Pinned), grouped once (in Work).
        assertEquals(1, texts.count { it == "pinned one" })
        assertEquals(1, texts.count { it == "grouped one" })
    }

    @Test
    fun `grouped unpinned clip does not appear in chronological history`() {
        val entries = listOf(
            ClipEntry("grouped", group = "Work"),
            ClipEntry("plain")
        )
        val rows = build(entries, groups = listOf("Work"))
        val headers = rows.filterIsInstance<DisplayRow.Header>().map { it.key }
        assertTrue(headers.contains("group:Work"))
        // The grouped clip sits under the group header, not Today/Yesterday/Earlier.
        val plainIndex = rows.indexOfFirst { it is DisplayRow.Clip && it.text == "plain" }
        val groupedIndex = rows.indexOfFirst { it is DisplayRow.Clip && it.text == "grouped" }
        val workHeader = rows.indexOfFirst { it is DisplayRow.Header && it.key == "group:Work" }
        assertTrue(groupedIndex > workHeader)
        assertTrue(plainIndex > groupedIndex)
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
        assertTrue(headers.contains("search:group:Code"))
        assertTrue(headers.contains("search:other"))
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
    fun `empty user group still renders a manageable header`() {
        val rows = build(listOf(ClipEntry("plain")), groups = listOf("Empty"))
        val header = rows.filterIsInstance<DisplayRow.Header>().single { it.key == "group:Empty" }
        assertEquals(0, header.count)
        assertTrue(rows.any { it is DisplayRow.Message && it.title == "Empty group" })
    }
}
