package org.koitharu.kotatsu.search.unified

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coalesces concurrent cache misses; obsolete/cancelled readers cannot populate the cache. */
internal class CatalogueRequestCoordinator(private val cache: TrackerSearchCache) {
    private val gate = Mutex()
    suspend fun get(key: String, current: () -> Boolean, fetch: suspend () -> CataloguePage): CataloguePage = gate.withLock {
        currentCoroutineContext().ensureActive()
        check(current()) { "Search settings or tracker account changed" }
        cache.catalogue(key)?.let { return@withLock it }
        val page = fetch()
        currentCoroutineContext().ensureActive()
        check(current()) { "Search settings or tracker account changed" }
        cache.saveCatalogue(key, page)
        page
    }
}
