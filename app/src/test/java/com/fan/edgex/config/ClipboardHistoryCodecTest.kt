package com.fan.edgex.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryCodecTest {

    @Test
    fun `round trip preserves every field`() {
        val snapshot = ClipboardHistorySnapshot(
            entries = listOf(
                ClipEntry("hello", timestamp = 1_700_000_000_000L, sourceUid = 10_123),
                ClipEntry("gh repo clone fcmfcm1999/EdgeX", pinned = true, group = "Commands"),
                ClipEntry("line1\nline2", timestamp = 42L, group = "Notes")
            ),
            groups = listOf("Commands", "Notes"),
            hintDismissed = true
        )

        val decoded = ClipboardHistoryCodec.decode(
            ClipboardHistoryCodec.encode(snapshot)
        )

        assertEquals(snapshot.entries, decoded.entries)
        assertEquals(snapshot.groups, decoded.groups)
        assertTrue(decoded.hintDismissed)
    }

    @Test
    fun `version one files decode as legacy text-only entries`() {
        val legacy = mapOf(
            "__version" to "1",
            "count" to "2",
            "entry.0" to "first",
            "entry.1" to "second"
        )

        val decoded = ClipboardHistoryCodec.decode(legacy)

        assertEquals(2, decoded.entries.size)
        assertEquals("first", decoded.entries[0].text)
        assertEquals(0L, decoded.entries[0].timestamp)
        assertFalse(decoded.entries[0].pinned)
        assertNull(decoded.entries[0].group)
        assertEquals(-1, decoded.entries[0].sourceUid)
        assertTrue(decoded.groups.isEmpty())
        assertFalse(decoded.hintDismissed)
    }

    @Test
    fun `history round trips more than fifty ordinary and protected clips`() {
        val entries = List(65) { ClipEntry(text = "clip-$it", timestamp = 100L + it) } +
            List(55) { ClipEntry(text = "pinned-$it", pinned = true) } +
            List(55) { ClipEntry(text = "grouped-$it", group = "Work") }

        val decoded = ClipboardHistoryCodec.decode(
            ClipboardHistoryCodec.encode(ClipboardHistorySnapshot(entries = entries))
        )

        assertEquals(entries, decoded.entries)
    }

    @Test
    fun `ordinary clips expire 30 days after capture`() {
        val capturedAt = 1_700_000_000_000L
        val text = ClipEntry(text = "ordinary", timestamp = capturedAt)
        val image = ClipEntry(type = ClipType.IMAGE, timestamp = capturedAt)
        val expected = capturedAt + 2_592_000_000L

        assertEquals(expected, ClipboardHistoryRetention.expiresAt(text))
        assertEquals(expected, ClipboardHistoryRetention.expiresAt(image))
        assertNull(ClipboardHistoryRetention.expiresAt(text.copy(pinned = true)))
        assertNull(ClipboardHistoryRetention.expiresAt(image.copy(group = "Work")))
        assertNull(ClipboardHistoryRetention.expiresAt(text.copy(timestamp = 0)))
    }

    @Test
    fun `blank group values are treated as ungrouped`() {
        val values = mapOf(
            "count" to "1",
            "entry.0.text" to "x",
            "entry.0.group" to ""
        )

        val decoded = ClipboardHistoryCodec.decode(values)

        assertNull(decoded.entries[0].group)
    }
}
