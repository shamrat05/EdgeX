package com.fan.edgex.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImageModelTest {

    @Test
    fun `restore merge keeps current clips and skips exact duplicate text`() {
        val current = ClipboardHistorySnapshot(
            entries = listOf(ClipEntry(text = "keep", pinned = true), ClipEntry(text = "same")),
            groups = listOf("Current"),
            hintDismissed = true
        )
        val restored = ClipboardHistorySnapshot(
            entries = listOf(ClipEntry(text = "same"), ClipEntry(text = "import")),
            groups = listOf("current", "From backup")
        )

        val result = ClipboardHistoryMerge.merge(current, restored)

        assertEquals(listOf("keep", "same", "import"), result.snapshot.entries.map { it.text })
        assertEquals(listOf("Current", "From backup"), result.snapshot.groups)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.duplicateCount)
        assertTrue(result.snapshot.hintDismissed)
    }

    @Test
    fun `restore merge can skip byte-identical image entries`() {
        val current = ClipboardHistorySnapshot(
            entries = listOf(ClipEntry(type = ClipType.IMAGE, imagePath = "/current.png"))
        )
        val restored = ClipboardHistorySnapshot(
            entries = listOf(ClipEntry(type = ClipType.IMAGE, imagePath = "/restored.png"))
        )

        val result = ClipboardHistoryMerge.merge(current, restored) { "same-sha256" }

        assertEquals(1, result.snapshot.entries.size)
        assertEquals(0, result.addedCount)
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun `screenshot media filter matches common folder and filename conventions`() {
        assertTrue(ScreenshotMediaFilter.matches("Pictures/Screenshots/", "IMG_1.png"))
        assertTrue(ScreenshotMediaFilter.matches("DCIM/Screen_Captures/", "capture.png"))
        assertTrue(ScreenshotMediaFilter.matches("Pictures/", "Screenshot_20260921.png"))
        assertFalse(ScreenshotMediaFilter.matches("Pictures/Camera", "IMG_20260921.png"))
    }

    @Test
    fun `screenshot source paths stay inside shared primary storage`() {
        assertTrue(
            ScreenshotMediaFilter.isSafeSourcePath(
                "/storage/emulated/0/Pictures/Screenshots/Screenshot_1.png"
            )
        )
        assertFalse(ScreenshotMediaFilter.isSafeSourcePath("/data/system/private/Screenshot_1.png"))
        assertFalse(
            ScreenshotMediaFilter.isSafeSourcePath(
                "/storage/emulated/0/../../data/system/private/Screenshot_1.png"
            )
        )
        assertFalse(ScreenshotMediaFilter.isSafeSourcePath(null))
    }

    @Test
    fun `text and image entries have distinct identities`() {
        val text = ClipEntry(text = "hello")
        val image = ClipEntry(type = ClipType.IMAGE, imagePath = "/data/x/clip-1.png")

        assertEquals("t:hello", text.id)
        assertEquals("i:/data/x/clip-1.png", image.id)
        assertFalse(text.id == image.id)
    }

    @Test
    fun `image search source never exposes binary or paths`() {
        val image = ClipEntry(
            type = ClipType.IMAGE,
            imagePath = "/data/x/clip-1.png",
            mimeType = "image/png",
            group = "Shots"
        )
        val source = image.searchSource
        assertTrue(source.contains("image"))
        assertTrue(source.contains("image/png"))
        assertTrue(source.contains("Shots"))
        assertFalse(source.contains("/data/x"))
    }

    @Test
    fun `image entries round trip through the codec`() {
        val snapshot = ClipboardHistorySnapshot(
            entries = listOf(
                ClipEntry(text = "plain text", timestamp = 5L),
                ClipEntry(
                    type = ClipType.IMAGE,
                    imagePath = "/data/system/edgex/clip-9.png",
                    imageUri = "content://media/external/images/9",
                    mimeType = "image/png",
                    width = 1080,
                    height = 2400,
                    pinned = true,
                    group = "Work",
                    timestamp = 7L
                )
            ),
            groups = listOf("Work")
        )

        val decoded = ClipboardHistoryCodec.decode(
            ClipboardHistoryCodec.encode(snapshot), maxItems = 50, imageExists = { true }
        )

        assertEquals(2, decoded.entries.size)
        val image = decoded.entries[1]
        assertEquals(ClipType.IMAGE, image.type)
        assertEquals("/data/system/edgex/clip-9.png", image.imagePath)
        assertEquals("content://media/external/images/9", image.imageUri)
        assertEquals("image/png", image.mimeType)
        assertEquals(1080, image.width)
        assertEquals(2400, image.height)
        assertTrue(image.pinned)
        assertEquals("Work", image.group)
        assertEquals(ClipType.TEXT, decoded.entries[0].type)
        assertEquals("plain text", decoded.entries[0].text)
    }

    @Test
    fun `screenshot image marker round trips and legacy media rows are recognized`() {
        val marked = ClipboardHistorySnapshot(
            entries = listOf(
                ClipEntry(
                    type = ClipType.IMAGE,
                    imagePath = "/data/x/screenshot.jpg",
                    imageUri = "content://media/external/images/media/42",
                    mimeType = "image/jpeg",
                    isScreenshot = true
                )
            )
        )
        val encoded = ClipboardHistoryCodec.encode(marked)
        assertTrue(ClipboardHistoryCodec.decode(encoded, 10).entries.single().isScreenshot)

        val legacy = encoded.toMutableMap().apply { remove("entry.0.screenshot") }
        assertTrue(
            ClipboardHistoryCodec.decode(legacy, 10).entries.single().isScreenshot
        )
    }

    @Test
    fun `screenshot source path round trips only for shared primary storage`() {
        val screenshotPath = "/storage/emulated/0/Pictures/Screenshots/Screenshot_1.jpg"
        val safe = ClipboardHistoryCodec.encode(
            ClipboardHistorySnapshot(
                entries = listOf(
                    ClipEntry(
                        type = ClipType.IMAGE,
                        imagePath = "/data/edgex/clip.jpg",
                        imageUri = "content://media/external/images/1",
                        mimeType = "image/jpeg",
                        isScreenshot = true,
                        sourcePath = screenshotPath
                    )
                )
            )
        )
        assertEquals(
            screenshotPath,
            ClipboardHistoryCodec.decode(safe, 10).entries.single().sourcePath
        )

        val unsafe = safe.toMutableMap().apply {
            this["entry.0.source_path"] = "/data/system/private.jpg"
        }
        assertNull(ClipboardHistoryCodec.decode(unsafe, 10).entries.single().sourcePath)
    }

    @Test
    fun `image entries whose owned file vanished are dropped`() {
        val encoded = ClipboardHistoryCodec.encode(
            ClipboardHistorySnapshot(
                entries = listOf(
                    ClipEntry(text = "keep"),
                    ClipEntry(type = ClipType.IMAGE, imagePath = "/gone.png", mimeType = "image/png")
                )
            )
        )

        val decoded = ClipboardHistoryCodec.decode(encoded, maxItems = 50, imageExists = { false })

        assertEquals(listOf("keep"), decoded.entries.map { it.text })
        assertTrue(decoded.entries.none { it.type == ClipType.IMAGE })
    }

    @Test
    fun `legacy version one file still decodes as text only`() {
        val legacy = mapOf(
            "__version" to "1",
            "count" to "1",
            "entry.0" to "old clip"
        )
        val decoded = ClipboardHistoryCodec.decode(legacy, maxItems = 50)
        assertEquals(ClipType.TEXT, decoded.entries.single().type)
        assertEquals("old clip", decoded.entries.single().text)
    }

    @Test
    fun `mime whitelist accepts common image types only`() {
        assertTrue(ClipboardImageStore.isSupported("image/png"))
        assertTrue(ClipboardImageStore.isSupported("image/jpeg"))
        assertTrue(ClipboardImageStore.isSupported("image/webp"))
        assertFalse(ClipboardImageStore.isSupported("text/plain"))
        assertFalse(ClipboardImageStore.isSupported("application/pdf"))
        assertFalse(ClipboardImageStore.isSupported(null))
    }

    @Test
    fun `extension mapping never trusts the source file name`() {
        assertEquals("png", ClipboardImageStore.extensionFor("image/png"))
        assertEquals("jpg", ClipboardImageStore.extensionFor("image/jpeg"))
        assertEquals("img", ClipboardImageStore.extensionFor("application/octet-stream"))
    }

    @Test
    fun `generated file names are safe and derived from the clip id`() {
        val name = ClipboardImageStore.fileNameFor("../../etc/passwd", "image/png")
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
        assertTrue(name.startsWith("clip-"))
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun `thumbnail sampling keeps the decoded size at or above the target`() {
        // 200 -> 100 >= 64, one halving is enough
        assertEquals(2, ClipboardImageStore.sampleSizeFor(200, 200, 64))
        // 1024 -> 512 -> 256 -> 128 >= 64
        assertEquals(8, ClipboardImageStore.sampleSizeFor(1024, 1024, 64))
        assertEquals(32, ClipboardImageStore.sampleSizeFor(4096, 3072, 64))
        assertEquals(1, ClipboardImageStore.sampleSizeFor(120, 120, 64))
        assertEquals(1, ClipboardImageStore.sampleSizeFor(0, 0, 64))
    }

    @Test
    fun `byte limits reject empty and oversized payloads`() {
        assertFalse(ClipboardImageStore.canStore(0))
        assertFalse(ClipboardImageStore.canStore(ClipboardImageStore.MAX_BYTES + 1L))
        assertTrue(ClipboardImageStore.canStore(1024))
    }
}
