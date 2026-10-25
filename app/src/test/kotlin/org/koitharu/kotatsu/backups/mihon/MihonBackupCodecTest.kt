package org.koitharu.kotatsu.backups.mihon

import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream

class MihonBackupCodecTest {
    @Test fun gzipAndPlainPayloadsPreserveUnreadGapsLargeIdsAndFractionalTracking() {
        val original = MihonBackup(manga = listOf(MihonBackupManga(source = 7, url = "/book", title = "作品 كتاب",
            chapters = (1..3).map { MihonBackupChapter(url = "/$it", name = "Chapter $it", read = it != 2,
                lastPageRead = if (it == 2) 7 else 0, chapterNumber = it.toFloat()) },
            tracking = listOf(MihonBackupTracking(syncId = 2, mediaId = Long.MAX_VALUE,
                libraryId = Long.MAX_VALUE - 1, lastChapterRead = 12.5f, score = 8.75f)))))
        val bytes = ProtoBuf.encodeToByteArray(MihonBackup.serializer(), original)
        val zipped = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(bytes) } }.toByteArray()
        val first = MihonBackupCodec.decode(ByteArrayInputStream(bytes))
        val second = MihonBackupCodec.decode(ByteArrayInputStream(zipped))
        assertEquals(original, first.backup)
        assertEquals(first, second)
        assertEquals(listOf(true, false, true), first.backup.manga.single().chapters.map { it.read })
    }

    @Test fun legacyMediaIdFieldKeepsUpstreamPrecedenceAndBothRawValues() {
        val tracking = MihonBackupTracking(mediaIdInt = 123, mediaId = Long.MAX_VALUE, libraryId = Long.MAX_VALUE - 1,
            trackingUrl = "https://fixture.invalid/list/item", private = true)
        val payload = ProtoBuf.encodeToByteArray(MihonBackup.serializer(), MihonBackup(manga = listOf(
            MihonBackupManga(url = "/book", tracking = listOf(tracking)))))
        val restored = MihonBackupCodec.decode(ByteArrayInputStream(payload)).backup.manga.single().tracking.single()
        assertEquals(tracking, restored)
        assertEquals(123L, restored.effectiveMediaId)
        assertEquals(Long.MAX_VALUE, restored.mediaId)
        assertEquals("mihon:9", org.koitharu.kotatsu.core.model.MangaSource("mihon:9").name)
    }

    @Test fun duplicateEditionAndChapterReferencesAreRejectedWithoutGuessing() {
        val manga = MihonBackupManga(source = 7, url = "/book", chapters = listOf(MihonBackupChapter(url = "/1")))
        assertThrows(IllegalArgumentException::class.java) { MihonBackupCodec.validate(MihonBackup(manga = listOf(manga, manga))) }
        assertThrows(IllegalArgumentException::class.java) {
            MihonBackupCodec.validate(MihonBackup(manga = listOf(manga.copy(chapters = manga.chapters + manga.chapters))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MihonBackupCodec.validate(MihonBackup(manga = listOf(manga.copy(chapters = listOf(MihonBackupChapter())))))
        }
    }

    @Test fun pageAndRawProgressValidationNeverWrapsOrTruncates() {
        assertEquals(Int.MAX_VALUE, MihonBackupCodec.checkedPage(Int.MAX_VALUE.toLong()))
        for (invalid in listOf(-1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { MihonBackupCodec.checkedPage(invalid) }
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.5f)) {
            assertThrows(IllegalArgumentException::class.java) {
                MihonBackupCodec.validate(MihonBackup(manga = listOf(MihonBackupManga(url = "/book",
                    tracking = listOf(MihonBackupTracking(lastChapterRead = invalid))))))
            }
        }
    }

    @Test fun recordCountIsCheckedBeforeAllocatingDecodedTitles() {
        val bytes = ByteArray((MihonBackupCodec.MAX_TITLES + 1) * 2) { if (it % 2 == 0) 10 else 0 }
        val error = assertThrows(IOException::class.java) { MihonBackupCodec.decode(ByteArrayInputStream(bytes)) }
        assertTrue(error.message.orEmpty().contains("Too many titles"))
    }

    @Test fun compressedAndInflatedInputsHaveIndependentLimits() {
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(0, offset, offset + length)
                return length
            }
        }
        assertThrows(IOException::class.java) { MihonBackupCodec.decode(endless) }
        val zipped = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { gzip ->
                val block = ByteArray(32 * 1024)
                repeat(MihonBackupCodec.MAX_PAYLOAD_BYTES / block.size + 1) { gzip.write(block) }
            }
        }.toByteArray()
        assertTrue(zipped.size < MihonBackupCodec.MAX_COMPRESSED_BYTES)
        assertThrows(IOException::class.java) { MihonBackupCodec.decode(ByteArrayInputStream(zipped)) }
    }

    @Test fun malformedWireLengthsAreRejectedBeforeLibraryMutation() {
        for (bytes in listOf(byteArrayOf(10, 127, 1), byteArrayOf(10, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2))) {
            assertThrows(IOException::class.java) { MihonBackupCodec.decode(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun nestedHistoryGenresAndPackedOrUnpackedCategoriesAreBoundedBeforeDecoding() {
        val histories = ByteArrayOutputStream().apply {
            repeat(MihonBackupCodec.MAX_CHAPTERS + 1) { writeField(104 * 8 + 2, byteArrayOf()) }
        }.toByteArray()
        val genres = ByteArrayOutputStream().apply { repeat(257) { writeField(7 * 8 + 2, byteArrayOf()) } }.toByteArray()
        val packed = ByteArrayOutputStream().apply { writeField(17 * 8 + 2, ByteArray(1_001)) }.toByteArray()
        val unpacked = ByteArrayOutputStream().apply { repeat(1_001) { writeVarint(17 * 8); writeVarint(0) } }.toByteArray()
        for (body in listOf(histories, genres, packed, unpacked)) {
            val root = ByteArrayOutputStream().apply { writeField(10, body) }.toByteArray()
            val error = assertThrows(IOException::class.java) { MihonBackupCodec.decode(ByteArrayInputStream(root)) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Too many", ignoreCase = true))
        }
    }

    private fun ByteArrayOutputStream.writeField(tag: Int, value: ByteArray) {
        writeVarint(tag)
        writeVarint(value.size)
        write(value)
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var current = value
        while (current >= 128) {
            write((current and 127) or 128)
            current = current ushr 7
        }
        write(current)
    }
}
