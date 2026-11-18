package com.fan.edgex.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ClipboardBatchBackupTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun undoRestoresTheWholeBatchInHistoryOrder() {
        val original = listOf(
            ClipEntry(text = "One", pinned = true),
            ClipEntry(text = "Two", group = "Work"),
            ClipEntry(type = ClipType.IMAGE, imagePath = "/images/three.png"),
            ClipEntry(text = "Four")
        )
        val history = original.toMutableList()
        val selected = setOf(original[0].id, original[2].id)
        val removed = history.removeClips(selected)

        assertEquals(listOf(original[1], original[3]), history)
        history.restoreClips(removed)
        history.restoreClips(removed)
        assertEquals(original, history)
    }

    @Test
    fun zipCompressesTextAndPreservesManifestAndImageBytes() {
        val manifest = """{"entries":[{"text":"${"clipboard ".repeat(512)}","pinned":true}],"groups":["Work"]}"""
            .toByteArray()
        val image = files.newFile("clip.png")
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        image.writeBytes(imageBytes)
        val backup = files.newFile("backup.zip")

        ClipboardBackupArchive.writeZip(backup, manifest, listOf(image))

        ZipFile(backup).use { zip ->
            val entry = zip.getEntry("manifest.json")
            assertEquals(ZipEntry.DEFLATED, entry.method)
            assertTrue(entry.compressedSize < entry.size)
            assertArrayEquals(manifest, zip.getInputStream(entry).use { it.readBytes() })
            assertArrayEquals(imageBytes, zip.getInputStream(zip.getEntry("images/clip.png")).use { it.readBytes() })
        }
    }

    @Test(expected = IOException::class)
    fun rejectsAManifestLargerThanTheRestoreLimit() {
        ClipboardBackupArchive.writeZip(
            files.newFile("oversized.zip"),
            ByteArray(ClipboardBackupArchive.MAX_MANIFEST_BYTES + 1),
            emptyList()
        )
    }
}
