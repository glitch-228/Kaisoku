package org.koitharu.kotatsu.details.data

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class MangaDetailsLocaleTest {
    private val source = MangaParserSource.READMANGA_RU
    private val manga = Manga(7, "Title", emptySet(), "/title", "https://example.org/title", 0f,
        null, null, emptySet(), null, emptySet(), null, null, null, source)
    private fun chapter(id: Long, branch: String) = MangaChapter(id, "Chapter", 1f, 0,
        "/$id", null, 0, branch, source)

    @Test fun selectedLanguageBranchOverridesSourceLanguageWithoutMutatingChapters() {
        val chapters = listOf(chapter(1, "English"), chapter(2, "Русский"))
        val details = MangaDetails(manga.copy(chapters = chapters))
        assertEquals("en", details.getLocale("English")?.language)
        assertEquals("ru", details.getLocale("Русский")?.language)
        assertEquals(chapters, details.allChapters)
    }

    @Test fun singleBranchAndUnknownLabelsKeepExistingFallback() {
        assertEquals("en", MangaDetails(manga.copy(chapters = listOf(chapter(1, "English")))).getLocale()?.language)
        val details = MangaDetails(manga)
        assertEquals("ru", details.getLocale("Team XYZ")?.language)
        assertEquals("ru", details.getLocale("")?.language)
    }
}
