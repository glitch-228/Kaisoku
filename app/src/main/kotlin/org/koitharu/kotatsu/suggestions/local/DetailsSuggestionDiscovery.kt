package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

/** One stateless source operation, under the engine's existing budget/cooldown/timeout. */
internal suspend fun discoverDetailsSuggestions(
    repository: MangaRepository,
    seed: Manga,
    titleOnly: Boolean,
): List<Recommendation> {
    if (repository.supportsRelatedDiscovery) {
        return repository.discoverRelated(seed).take(60).map {
            Recommendation(RecommendationTarget.SourceTitle(it), provenance = RecommendationProvenance.RELATED)
                .withRelationTo(seed)
        }
    }
    val tags = if (titleOnly && repository.supportsGenreDiscovery) {
        seed.tags.filter { it.source.name == seed.source.name }.sortedBy { it.key }
            .take(if (repository.filterCapabilities.isMultipleTagsSupported) 3 else 1).toSet()
    } else emptySet()
    if (tags.isNotEmpty()) {
        val order = listOf(SortOrder.POPULARITY, SortOrder.RATING, SortOrder.UPDATED)
            .firstOrNull { it in repository.sortOrders } ?: repository.defaultSortOrder
        return repository.discover(order, MangaListFilter(tags = tags)).take(60).map {
            Recommendation(RecommendationTarget.SourceTitle(it), provenance = RecommendationProvenance.GENRE)
        }
    }
    if (!repository.filterCapabilities.isSearchSupported || seed.title.isBlank()) return emptyList()
    val order = SortOrder.RELEVANCE.takeIf { it in repository.sortOrders } ?: repository.defaultSortOrder
    return repository.discover(order, MangaListFilter(query = seed.title.take(120))).take(35).map {
        Recommendation(RecommendationTarget.SourceTitle(it), provenance = RecommendationProvenance.SEARCH)
    }
}
