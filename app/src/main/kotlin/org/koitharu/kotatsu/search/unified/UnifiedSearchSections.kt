package org.koitharu.kotatsu.search.unified

import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.search.ui.multi.SearchResultsListModel

internal fun unifiedSearchSections(sourceRows: List<ListModel>, cards: List<TrackerSearchCard>,
    hasMore: Boolean): List<ListModel> {
    if (cards.isEmpty()) return sourceRows + if (hasMore) listOf(CatalogueMore) else emptyList()
    val linked = cards.filter { it.editions.isNotEmpty() }
    val identities = linked.flatMap { it.editions }.map { it.source.name to it.id }.toSet()
    val remaining = cards.filter { it.editions.isEmpty() }
    return buildList {
        if (linked.isNotEmpty()) add(TrackerSearchSection(R.string.tracker_search_linked, linked))
        for (row in sourceRows) when (row) {
            is EmptyState -> Unit
            LoadingState -> Unit
            is SearchResultsListModel -> {
                val retained = row.list.filterNot { it.source.name to it.id in identities }
                if (retained.isNotEmpty()) add(row.copy(list = retained))
            }
            else -> add(row)
        }
        val saved = remaining.filter { it.saved != null }
        val other = remaining.filter { it.saved == null }
        if (saved.isNotEmpty()) add(TrackerSearchSection(R.string.tracker_search_saved, saved))
        if (other.isNotEmpty()) add(TrackerSearchSection(R.string.tracker_search_catalogue, other))
        if (hasMore) add(CatalogueMore)
    }
}
