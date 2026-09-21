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
            ClipboardHistoryCodec.encode(snapshot), maxItems = 50
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

        val decoded = ClipboardHistoryCodec.decode(legacy, maxItems = 50)

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
    fun `pinned entries survive the ordinary limit`() {
        val values = mapOf(
            "count" to "4",
            "entry.0.text" to "a",
            "entry.1.text" to "b",
            "entry.2.text" to "keep-me",
            "entry.2.pinned" to "1",
            "entry.3.text" to "c"
        )

        val decoded = ClipboardHistoryCodec.decode(values, maxItems = 2)

        // Two newest unpinned entries plus the pinned one.
        assertEquals(listOf("a", "b", "keep-me"), decoded.entries.map { it.text })
        assertTrue(decoded.entries.last().pinned)
    }

    @Test
    fun `blank group values are treated as ungrouped`() {
        val values = mapOf(
            "count" to "1",
            "entry.0.text" to "x",
            "entry.0.group" to ""
        )

        val decoded = ClipboardHistoryCodec.decode(values, maxItems = 50)

        assertNull(decoded.entries[0].group)
    }
}
