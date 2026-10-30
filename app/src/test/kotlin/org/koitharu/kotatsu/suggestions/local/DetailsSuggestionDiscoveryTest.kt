package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.lang.reflect.Proxy

@OptIn(InternalParsersApi::class)
class DetailsSuggestionDiscoveryTest {
    private val seed = Manga(1, "Seed title", emptySet(), "/1", "https://fixture.invalid/1", -1f, null, null,
        setOf(MangaTag("Adventure", "adventure", TestMangaSource), MangaTag("Fantasy", "fantasy", TestMangaSource)),
        null, emptySet(), source = TestMangaSource)

    @Test fun directRelationsUseOneCallAndCarryTheActualSeed() = runBlocking {
        val fixture = Fake(related = true)
        val values = discoverDetailsSuggestions(fixture.repository, seed, true)
        assertEquals(listOf("discoverRelated"), fixture.calls)
        assertEquals(RecommendationProvenance.RELATED, values.single().provenance)
        assertEquals(setOf(seed.recommendationSourceKey()), values.single().relatedTo)
    }

    @Test fun titleOnlyGenreFallbackUsesSeedKeysWithoutRequestingFilterMetadata() = runBlocking {
        val fixture = Fake(genres = true)
        val values = discoverDetailsSuggestions(fixture.repository, seed, true)
        assertEquals(listOf("discover"), fixture.calls)
        assertEquals(setOf(seed.tags.first()), fixture.filter!!.tags)
        assertNull(fixture.filter!!.query)
        assertEquals(SortOrder.POPULARITY, fixture.order)
        assertEquals(RecommendationProvenance.GENRE, values.single().provenance)
        assertTrue(values.single().relatedTo.isEmpty())
    }

    @Test fun currentModeKeepsItsTitleSearchEvenWhenGenreDiscoveryExists() = runBlocking {
        val fixture = Fake(genres = true)
        val result = discoverDetailsSuggestions(fixture.repository, seed, false)
        assertEquals(seed.title, fixture.filter!!.query)
        assertTrue(fixture.filter!!.tags.isEmpty())
        assertEquals(SortOrder.RELEVANCE, fixture.order)
        assertEquals(RecommendationProvenance.SEARCH, result.single().provenance)
        assertEquals(1, fixture.calls.size)
    }

    @Test fun multipleGenresAreBoundedByCapabilitiesAndNeverBorrowOtherSourceKeys() = runBlocking {
        val fixture = Fake(genres = true, multiple = true)
        val moreTags = seed.tags + (1..8).map { MangaTag("Genre $it", "z$it", TestMangaSource) }
        discoverDetailsSuggestions(fixture.repository, seed.copy(tags = moreTags), true)
        assertEquals(3, fixture.filter!!.tags.size)
        val foreign = object : org.koitharu.kotatsu.parsers.model.MangaSource { override val name = "other" }
        val other = Fake(genres = true)
        discoverDetailsSuggestions(other.repository, seed.copy(tags = setOf(MangaTag("Other", "other", foreign))), true)
        assertEquals(seed.title, other.filter!!.query)
        assertTrue(other.filter!!.tags.isEmpty())
    }

    @Test fun missingGenreMetadataUsesOneIndependentTitleSearch() = runBlocking {
        val fixture = Fake(genres = true)
        discoverDetailsSuggestions(fixture.repository, seed.copy(tags = emptySet(), title = "a".repeat(200)), true)
        assertEquals(120, fixture.filter!!.query!!.length)
        assertEquals(listOf("discover"), fixture.calls)
    }

    @Test fun unsupportedSourcesAndBlankQueriesNeverTriggerUnfilteredPopularDiscovery() = runBlocking {
        val unsupported = Fake(search = false)
        assertTrue(discoverDetailsSuggestions(unsupported.repository, seed, true).isEmpty())
        assertTrue(unsupported.calls.isEmpty())
        val blank = Fake()
        assertTrue(discoverDetailsSuggestions(blank.repository, seed.copy(title = " ", tags = emptySet()), true).isEmpty())
        assertTrue(blank.calls.isEmpty())
    }

    @Test fun cancellationDoesNotTryAnotherDiscoveryMethod() = runBlocking {
        val fixture = Fake(related = true, cancelled = true)
        try { discoverDetailsSuggestions(fixture.repository, seed, true); fail("Expected cancellation") }
        catch (_: CancellationException) { assertEquals(listOf("discoverRelated"), fixture.calls) }
    }

    private inner class Fake(related: Boolean = false, genres: Boolean = false, search: Boolean = true,
        multiple: Boolean = false, cancelled: Boolean = false) {
        val calls = mutableListOf<String>()
        var filter: MangaListFilter? = null
        var order: SortOrder? = null
        val repository = Proxy.newProxyInstance(MangaRepository::class.java.classLoader,
            arrayOf(MangaRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getSupportsRelatedDiscovery" -> related
                "getSupportsGenreDiscovery" -> genres
                "getFilterCapabilities" -> MangaListFilterCapabilities(isSearchSupported = search, isMultipleTagsSupported = multiple)
                "getSortOrders" -> setOf(SortOrder.RELEVANCE, SortOrder.POPULARITY)
                "getDefaultSortOrder" -> SortOrder.POPULARITY
                "discover", "discoverRelated" -> {
                    calls.add(method.name)
                    if (cancelled) throw CancellationException("fixture")
                    if (method.name == "discover") { order = args[0] as SortOrder; filter = args[1] as MangaListFilter }
                    listOf(seed.copy(id = 2, title = "Related"))
                }
                else -> error("Unexpected adapter call: ${method.name}; browse state/full details must be untouched")
            }
        } as MangaRepository
    }
}
