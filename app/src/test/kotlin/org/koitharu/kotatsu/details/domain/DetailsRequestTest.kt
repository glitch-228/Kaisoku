package org.koitharu.kotatsu.details.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class DetailsRequestTest {
	@Test fun novelsDoNotLoadImageThumbnailsOnDetails() {
		val manga = Manga(7, "Title", emptySet(), "/title", "https://readmanga.ru/title", 0f,
			null, null, emptySet(), null, emptySet(), null, null, null, MangaParserSource.READMANGA_RU)
		assertTrue(org.koitharu.kotatsu.details.data.MangaDetails(manga).supportsPageThumbnails)
		val novel = manga.copy(source = org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource("jaomix.ru", "Jaomix"))
		assertFalse(org.koitharu.kotatsu.details.data.MangaDetails(novel).supportsPageThumbnails)
	}
	@Test fun absentMetadataKeepsSeedButExplicitEmptyValuesReplaceIt() {
		val source = MangaParserSource.READMANGA_RU
		val seed = Manga(7, "Title", emptySet(), "/title", "https://readmanga.ru/title", 0f,
			null, "cover", emptySet(), null, emptySet(), "large", "Description",
			listOf(MangaChapter(9, "Chapter", 1f, 0, "/chapter", null, 0, null, source)), source)
		val absent = seed.copy(description = null, coverUrl = null, largeCoverUrl = null, chapters = null)
		assertEquals(seed, absent.withMissingDetailsFrom(seed))
		val empty = seed.copy(description = "", coverUrl = "", largeCoverUrl = "", chapters = emptyList())
		assertEquals(empty, empty.withMissingDetailsFrom(seed))
	}

	@Test fun titleRequestAndAuxiliaryRequestsAreDistinguished() {
		assertTrue(isMangaDetailsUrl("https://readmanga.ru/title?mtr=1", "/title", "https://readmanga.ru/title"))
		assertFalse(isMangaDetailsUrl("https://readmanga.ru/title/chapters", "/title", "https://readmanga.ru/title"))
		assertFalse(isMangaDetailsUrl("https://images.example/title", "/title", "https://readmanga.ru/title"))
	}
	@Test fun mangaLibApiMetadataIsDistinctFromChapters() {
		assertTrue(isMangaDetailsUrl("https://api.cdnlibs.org/api/manga/1--title?fields[]=summary", "1--title", "https://mangalib.org/ru/manga/1--title"))
		assertFalse(isMangaDetailsUrl("https://api.cdnlibs.org/api/manga/1--title/chapters", "1--title", "https://mangalib.org/ru/manga/1--title"))
	}
}
