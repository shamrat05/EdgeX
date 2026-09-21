package com.fan.edgex.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ClipboardUiKitTest {

    @Test
    fun `url detection accepts links and bare domains only`() {
        assertTrue(ClipboardUiKit.looksLikeUrl("https://github.com/fcmfcm1999/EdgeX"))
        assertTrue(ClipboardUiKit.looksLikeUrl("http://example.com/path?q=1"))
        assertTrue(ClipboardUiKit.looksLikeUrl("www.example.org"))
        assertTrue(ClipboardUiKit.looksLikeUrl("example.com/path"))
        assertFalse(ClipboardUiKit.looksLikeUrl("just some words"))
        assertFalse(ClipboardUiKit.looksLikeUrl("git clone example.com"))
        assertFalse(ClipboardUiKit.looksLikeUrl("line one\nhttps://example.com"))
    }

    @Test
    fun `email detection`() {
        assertTrue(ClipboardUiKit.looksLikeEmail("someone@example.com"))
        assertFalse(ClipboardUiKit.looksLikeEmail("someone@example"))
        assertFalse(ClipboardUiKit.looksLikeEmail("not an email@example.com"))
    }

    @Test
    fun `shell and code detection`() {
        assertTrue(ClipboardUiKit.looksLikeCode("gh repo clone fcmfcm1999/EdgeX"))
        assertTrue(ClipboardUiKit.looksLikeCode("adb shell pm grant com.foo android.permission.X"))
        assertTrue(ClipboardUiKit.looksLikeCode("npm install --save-dev typescript"))
        assertTrue(ClipboardUiKit.looksLikeCode("fun main() {\n    println(\"hi\")\n}"))
        assertFalse(ClipboardUiKit.looksLikeCode("Remember to buy milk"))
        assertFalse(ClipboardUiKit.looksLikeCode("https://example.com"))
    }

    @Test
    fun `ordinary prose is never classified as code`() {
        assertFalse(ClipboardUiKit.looksLikeCode("Clarification for clip interactions:"))
        assertFalse(ClipboardUiKit.looksLikeCode("STOP BEFORE FINAL BUILD."))
        assertFalse(ClipboardUiKit.looksLikeCode("Here is a list:\n- one\n- two\n- three"))
        assertFalse(ClipboardUiKit.looksLikeCode("Use the [] and () characters in prose."))
        assertFalse(ClipboardUiKit.looksLikeCode("The class of 2026 graduates today."))
        assertFalse(ClipboardUiKit.looksLikeCode("He said \"hello\" -- then left."))
        assertFalse(
            ClipboardUiKit.looksLikeCode("Tap an item to paste.\nLong press for options.")
        )
    }

    @Test
    fun `classification picks the most specific kind`() {
        assertEquals(ClipboardUiKit.ClipKind.LINK, ClipboardUiKit.classify("https://example.com"))
        assertEquals(ClipboardUiKit.ClipKind.EMAIL, ClipboardUiKit.classify("a@b.co"))
        assertEquals(ClipboardUiKit.ClipKind.CODE, ClipboardUiKit.classify("git status"))
        assertEquals(ClipboardUiKit.ClipKind.NOTE, ClipboardUiKit.classify("hello world"))
    }

    @Test
    fun `day sections use calendar boundaries and treat legacy timestamps as earlier`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = now.timeInMillis
        val earlierToday = nowMillis - 60_000L
        val yesterday = nowMillis - 24 * 60 * 60_000L
        val lastWeek = nowMillis - 7 * 24 * 60 * 60_000L

        assertEquals(
            ClipboardUiKit.DaySection.TODAY,
            ClipboardUiKit.daySection(earlierToday, nowMillis)
        )
        assertEquals(
            ClipboardUiKit.DaySection.YESTERDAY,
            ClipboardUiKit.daySection(yesterday, nowMillis)
        )
        assertEquals(
            ClipboardUiKit.DaySection.EARLIER,
            ClipboardUiKit.daySection(lastWeek, nowMillis)
        )
        assertEquals(
            ClipboardUiKit.DaySection.EARLIER,
            ClipboardUiKit.daySection(0L, nowMillis)
        )
    }
}
