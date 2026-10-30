package org.koitharu.kotatsu.search.unified

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.search.domain.SearchTrackerAccount

class TrackerSearchCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private fun page(id: String = "opaque") = CataloguePage(listOf(CatalogueTitle(id, "Title", url = "https://test.invalid/$id")), null)

    @Test fun catalogueExpiresAtFifteenMinutesButStaleDataSurvivesRecreationForFailures() {
        var now = 1000L
        val folder = temp.newFolder()
        val cache = TrackerSearchCache(folder) { now }
        cache.saveCatalogue("account-A", page())
        now += 899_999; assertNotNull(cache.catalogue("account-A"))
        now++; assertNull(cache.catalogue("account-A"))
        assertNotNull(TrackerSearchCache(folder) { now }.catalogue("account-A", stale = true))
        assertNull(cache.catalogue("account-B", stale = true))
        now = 500; assertNull(cache.catalogue("account-A"))
    }

    @Test fun rawAccountAndFractionalProgressSurviveWithoutNumericProjection() {
        val folder = temp.newFolder()
        val cache = TrackerSearchCache(folder) { 1000L }
        val owner = SearchTrackerAccount("MANGABAKA", "opaque-owner", 3, 9)
        val saved = SavedTrackerTitle(page().titles.single(), "opaque-entry", "CURRENT", "12.50", volume = "2.75")
        cache.saveLibrary(owner, listOf(saved))
        assertEquals(listOf(saved), TrackerSearchCache(folder).library(owner)?.titles)
        assertNull(cache.library(owner.copy(account = "another")))
        assertNull(cache.library(owner.copy(revision = 4)))
        assertEquals(owner, cache.account(owner.provider))
    }

    @Test fun derivedCatalogueIsBoundedIncludingEmptySearchPages() {
        val folder = temp.newFolder()
        val cache = TrackerSearchCache(folder) { 1000L }
        repeat(20) { index -> cache.saveCatalogue("query$index", CataloguePage((0 until 100).map {
            CatalogueTitle("$index-$it", "Title", url = "https://test.invalid")
        }, null)) }
        val counts = (0 until 20).sumOf { cache.catalogue("query$it", stale = true)?.titles?.size ?: 0 }
        assertTrue(counts <= 1000)
        repeat(100) { cache.saveCatalogue("empty$it", CataloguePage(emptyList(), null)) }
        assertTrue(folder.listFiles()!!.count { it.name.startsWith("catalogue_") } <= 64)
    }

    @Test fun concurrentIdenticalRequestsShareOneFetch() = runTest {
        val cache = TrackerSearchCache(temp.newFolder()) { 1000L }
        val coordinator = CatalogueRequestCoordinator(cache)
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val a = async { coordinator.get("same", { true }) { calls++; gate.await(); page() } }
        val b = async { coordinator.get("same", { true }) { calls++; page() } }
        yield(); gate.complete(Unit)
        assertEquals(a.await(), b.await()); assertEquals(1, calls)
    }

    @Test fun disableOrAccountChangeDuringFetchCannotCacheOrPublishIt() = runTest {
        val cache = TrackerSearchCache(temp.newFolder()) { 1000L }
        val coordinator = CatalogueRequestCoordinator(cache)
        var current = true
        try { coordinator.get("old", { current }) { current = false; page() }; fail() }
        catch (_: IllegalStateException) { }
        assertNull(cache.catalogue("old", stale = true))
        var calls = 0
        try { coordinator.get("disabled", { false }) { calls++; page() }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }

    @Test fun canceledTransportCannotPopulateCacheEvenIfItReturnsLate() = runTest {
        val cache = TrackerSearchCache(temp.newFolder()) { 1000L }
        val coordinator = CatalogueRequestCoordinator(cache)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val work = launch { coordinator.get("cancelled", { true }) {
            started.complete(Unit); withContext(NonCancellable) { finish.await() }; page()
        } }
        started.await(); work.cancel(); finish.complete(Unit); work.join()
        assertNull(cache.catalogue("cancelled", stale = true))
    }
}
