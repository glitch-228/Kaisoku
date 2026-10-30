package org.koitharu.kotatsu.search.unified

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.search.domain.*

class TrackerLibraryFilterTest {
    private val account = SearchTrackerAccount("ANILIST", "owner", 2, 4)
    private val a = title("1", "狐", "12.50", listOf(manga(1, LocalMangaSource)))
    private val b = title("2", "ثعلب", "2", listOf(manga(2, UnknownMangaSource)))
    private val c = title("3", "Fox", "0", emptyList())

    @Test fun oneSourceKeepsUnknownTitlesButOnlyItsConfirmedEditions() {
        val scope = ReadingSourceScope(SourceScopeKind.SINGLE, LocalMangaSource.name)
        val rows = filterTrackerCards(listOf(a, b, c), TrackerLibraryFilterState(sources = scope), emptySet(), emptySet(), emptySet())
        assertEquals(3, rows.size)
        assertEquals(listOf("1"), rows.filter { it.editions.isNotEmpty() }.map { it.work.id })
        assertEquals(setOf("2", "3"), rows.filter { it.editions.isEmpty() }.map { it.work.id }.toSet())
        assertEquals("12.50", rows.first { it.work.id == "1" }.saved?.progress)
    }

    @Test fun linkedUnlinkedDownloadedAndPinnedFiltersHaveDistinctMeaning() {
        val enabled = setOf(LocalMangaSource.name, UnknownMangaSource.name)
        val cards = listOf(a, b, c)
        assertEquals(setOf("1", "2"), filterTrackerCards(cards, TrackerLibraryFilterState(linkState = "LINKED"),
            enabled, emptySet(), emptySet()).map { it.work.id }.toSet())
        assertEquals(listOf("3"), filterTrackerCards(cards, TrackerLibraryFilterState(linkState = "UNLINKED"),
            enabled, emptySet(), emptySet()).map { it.work.id })
        assertEquals(listOf("2"), filterTrackerCards(cards, TrackerLibraryFilterState(downloaded = true),
            enabled, emptySet(), setOf(2)).map { it.work.id })
        val pinned = TrackerLibraryFilterState(sources = ReadingSourceScope(SourceScopeKind.PINNED), linkState = "LINKED")
        assertEquals(listOf("1"), filterTrackerCards(cards, pinned, enabled, setOf(LocalMangaSource.name), emptySet()).map { it.work.id })
    }

    @Test fun cjkRtlAliasesAndStableSortingDoNotMergeDifferentWorks() {
        val enabled = setOf(LocalMangaSource.name, UnknownMangaSource.name)
        listOf("狐", "ثعلب", "alias").forEach { query ->
            assertTrue(filterTrackerCards(listOf(a, b, c), TrackerLibraryFilterState(query = query), enabled,
                emptySet(), emptySet()).isNotEmpty())
        }
        val same = c.copy(work = c.work.copy(id = "different-edition"))
        val one = filterTrackerCards(listOf(a, b, c, same), TrackerLibraryFilterState(alphabetical = true), enabled, emptySet(), emptySet())
        val two = filterTrackerCards(listOf(same, c, b, a), TrackerLibraryFilterState(alphabetical = true), enabled, emptySet(), emptySet())
        assertEquals(one, two); assertEquals(4, one.size)
    }

    @Test fun requestRoundTripKeepsSourceAndOpaqueTrackerContextWithoutChangingAnyIds() {
        val request = SearchRequest("狐", sources = ReadingSourceScope(SourceScopeKind.SINGLE, "mihon:123"),
            tracker = account.copy(provider = "MANGABAKA", account = "opaque-uuid"), trackerTarget = "opaque-work")
        assertEquals(request, SearchRequest.decode(request.encode()))
        assertNull(SearchRequest.decode("{broken"))
        assertThrows(IllegalArgumentException::class.java) { ReadingSourceScope(SourceScopeKind.SINGLE) }
        assertThrows(IllegalArgumentException::class.java) { SearchRequest("a", trackerTarget = "without-owner") }
    }

    private fun title(id: String, name: String, progress: String, editions: List<Manga>): TrackerSearchCard {
        val work = CatalogueTitle(id, name, setOf("alias"), url = "https://tracker.test/$id")
        return TrackerSearchCard(account, work, SavedTrackerTitle(work, "entry-$id", "CURRENT", progress), editions)
    }
    private fun manga(id: Long, source: MangaSource) = Manga(id, "Title", emptySet(), "/$id", "https://source.test/$id",
        -1f, null, null, emptySet(), null, emptySet(), source = source)
}
