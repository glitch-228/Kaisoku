package org.koitharu.kotatsu.core.parser.lnreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for the `lnreader:` source name scheme and plugin-id round-trip
 * (persisted favourites/history resolve back to typed sources).
 */
class LnReaderSourceTest {

	@Test
	fun `source name embeds the plugin id`() {
		val source = LnReaderMangaSource(pluginId = "novelbin", displayName = "NovelBin", lang = "English")
		assertEquals("lnreader:novelbin", source.name)
	}

	@Test
	fun `plugin id round-trips through the name`() {
		assertEquals("novelbin", LnReaderMangaSource.extractPluginId("lnreader:novelbin"))
		assertNull(LnReaderMangaSource.extractPluginId("mihon:eu.kanade.test/1"))
		assertNull(LnReaderMangaSource.extractPluginId("lnreader:"))
		assertNull(LnReaderMangaSource.extractPluginId("MANGAREADER"))
	}

	@Test
	fun `sources with the same id are equal regardless of display fields`() {
		val a = LnReaderMangaSource("x", "One Name", "English")
		val b = LnReaderMangaSource("x", "Another Name", null)
		assertEquals(a, b)
		assertEquals(a.hashCode(), b.hashCode())
		assertNotEquals(a, LnReaderMangaSource("y", "One Name", "English"))
	}

	@Test
	fun `unresolved source keeps the lnreader name scheme`() {
		val unresolved = UnresolvedLnReaderSource("novelbin")
		assertEquals("lnreader:novelbin", unresolved.name)
	}

	@Test
	fun `content type is novel`() {
		val source = LnReaderMangaSource("x", "X")
		assertTrue(source.contentType == org.koitharu.kotatsu.parsers.model.ContentType.NOVEL)
	}

	@Test
	fun `chapter url separator round-trips`() {
		val url = "novel/one|||chapter/2"
		val parts = url.split(LnReaderMangaRepository.CHAPTER_SEPARATOR, limit = 2)
		assertEquals("novel/one", parts[0])
		assertEquals("chapter/2", parts[1])
	}

	@Test
	fun `stable id depends on source and raw value`() {
		// The repository's stableId is private; pin its inputs indirectly: two distinct
		// plugin sources must not collide on names.
		val a = LnReaderMangaSource("plugin-a", "Same Title")
		val b = LnReaderMangaSource("plugin-b", "Same Title")
		assertNotEquals(a.name, b.name)
	}
}
