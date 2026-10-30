package org.koitharu.kotatsu.search.unified

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.search.domain.ReadingSourceScope
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus

@Serializable
data class TrackerLibraryFilterState(
    val query: String = "",
    val status: String? = null,
    val alphabetical: Boolean = false,
    val sources: ReadingSourceScope = ReadingSourceScope(),
    val linkState: String = "ALL",
    val downloaded: Boolean = false,
)

internal fun filterTrackerCards(cards: List<TrackerSearchCard>, filter: TrackerLibraryFilterState,
    enabled: Set<String>, pinned: Set<String>, downloaded: Set<Long>): List<TrackerSearchCard> = cards
    .map { card -> card.copy(editions = card.editions.filter {
        filter.sources.contains(it.source.name, enabled, pinned)
    }) }
    .filter { card ->
        trackerQueryMatches(card.work, filter.query) &&
            (filter.status == null || card.saved?.status?.let {
                trackerStatus(ScrobblerService.valueOf(card.account.provider), it)
            } == filter.status) &&
            (filter.linkState != "LINKED" || card.editions.isNotEmpty()) &&
            (filter.linkState != "UNLINKED" || card.editions.isEmpty()) &&
            (!filter.downloaded || card.editions.any { it.id in downloaded })
    }.let { list -> if (filter.alphabetical) list.sortedWith(compareBy({ it.work.title.lowercase() }, { it.work.id }))
        else list.sortedWith(compareByDescending<TrackerSearchCard> {
            it.saved?.updatedAt?.toLongOrNull() ?: runCatching {
                java.time.Instant.parse(it.saved?.updatedAt).toEpochMilli() / 1000
            }.getOrDefault(0)
        }.thenBy { it.work.id }) }
