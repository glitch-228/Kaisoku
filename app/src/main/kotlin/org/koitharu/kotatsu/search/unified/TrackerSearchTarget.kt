package org.koitharu.kotatsu.search.unified

import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchRequest

interface TrackerSearchTarget {
    val trackerSearchQuery: String
    val trackerSearchPreview: Flow<List<TrackerSearchCard>>
    fun onSearchQueryChanged(query: String)
    fun searchRequest(query: String, kind: SearchKind): SearchRequest
    fun onTrackerSearchClick(card: TrackerSearchCard)
}
