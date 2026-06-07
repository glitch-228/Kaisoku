package org.koitharu.kotatsu.backups.mihon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MihonBackupCodecTest {

	@Test
	fun `round-trips a backup losslessly`() {
		val backup = MihonBackup(
			backupManga = listOf(
				MihonBackupManga(
					source = 123L,
					url = "/manga/1",
					title = "Title",
					favorite = true,
					status = 1,
					genre = listOf("Action", "Comedy"),
					chapters = listOf(MihonBackupChapter(url = "/c/1", read = true, lastPageRead = 5, sourceOrder = 0)),
					categories = listOf(0L),
				),
			),
			backupCategories = listOf(MihonBackupCategory(name = "Reading", order = 0)),
			backupSources = listOf(MihonBackupSource(name = "Src", sourceId = 123L)),
		)
		val bytes = ByteArrayOutputStream().also { MihonBackupCodec.encode(backup, it) }.toByteArray()
		val decoded = MihonBackupCodec.decode(ByteArrayInputStream(bytes))

		assertEquals(1, decoded.backupManga.size)
		val m = decoded.backupManga.single()
		assertEquals(123L, m.source)
		assertEquals("/manga/1", m.url)
		assertEquals("Title", m.title)
		assertTrue(m.favorite)
		assertEquals(listOf("Action", "Comedy"), m.genre)
		assertEquals("/c/1", m.chapters.single().url)
		assertEquals(5L, m.chapters.single().lastPageRead)
		assertEquals("Reading", decoded.backupCategories.single().name)
		assertEquals(123L, decoded.backupSources.single().sourceId)
	}

	@Test
	fun `kaisokuUid is stable and source-scoped`() {
		val a = kaisokuUid("MANGADEX", "/manga/1")
		assertEquals(a, kaisokuUid("MANGADEX", "/manga/1"))
		assertNotEquals(a, kaisokuUid("MANGADEX", "/manga/2"))
		assertNotEquals(a, kaisokuUid("OTHER", "/manga/1"))
	}

	@Test
	fun `kaisokuUid matches the generateUid hash formula`() {
		fun reference(source: String, url: String): Long {
			var h = 1125899906842597L
			source.forEach { h = 31 * h + it.code }
			url.forEach { h = 31 * h + it.code }
			return h
		}
		assertEquals(reference("MANGADEX", "/manga/1"), kaisokuUid("MANGADEX", "/manga/1"))
	}
}
