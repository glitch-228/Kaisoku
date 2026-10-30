package org.koitharu.kotatsu.search.unified

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.search.domain.SearchTrackerAccount
import org.koitharu.kotatsu.search.ui.multi.SearchResultsListModel

class UnifiedSearchSectionsTest {
    @Test fun confirmedMatchesComeFirstAndOnlyExactLinkedSourceIdentityIsDeduplicated() {
        val first = manga(1)
        val sourceOnly = manga(2)
        val catalogue = CatalogueTitle("remote", "Same title", url = "https://tracker.test/remote")
        val account = SearchTrackerAccount("ANILIST", "owner", 1, 42)
        val linked = TrackerSearchCard(account, catalogue, editions = listOf(first))
        val other = TrackerSearchCard(account, catalogue.copy(id = "another-edition"))
        val source = SearchResultsListModel(0, LocalMangaSource, null, null,
            listOf(first, sourceOnly).map { MangaGridModel(it, null, 0, null, false, false) }, null)
        val rows = unifiedSearchSections(listOf(source), listOf(linked, other), true)
        assertEquals(R.string.tracker_search_linked, (rows.first() as TrackerSearchSection).title)
        assertEquals(listOf(2L), (rows[1] as SearchResultsListModel).list.map { it.id })
        assertEquals("another-edition", (rows[2] as TrackerSearchSection).cards.single().work.id)
        assertSame(CatalogueMore, rows.last())
    }

    @Test fun missingOrFailedCatalogueNeverRemovesIndependentSourceOnlyWorks() {
        val source = SearchResultsListModel(0, LocalMangaSource, null, null,
            listOf(MangaGridModel(manga(2), null, 0, null, false, false)), null)
        assertEquals(listOf(source), unifiedSearchSections(listOf(source), emptyList(), false))
    }

    private fun manga(id: Long) = Manga(id, "Same title", emptySet(), "/$id", "https://source.test/$id", -1f,
        null, null, emptySet(), null, emptySet(), source = LocalMangaSource)
}
