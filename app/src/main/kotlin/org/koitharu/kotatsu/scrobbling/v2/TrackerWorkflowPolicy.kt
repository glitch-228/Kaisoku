package org.koitharu.kotatsu.scrobbling.v2

import java.text.Normalizer
import java.util.Locale

sealed interface CategoryStatusDecision {
    data object Unmapped : CategoryStatusDecision
    data class Status(val value: String, val categories: Set<Long>) : CategoryStatusDecision
    data class Conflict(val categoriesByStatus: Map<String, Set<Long>>) : CategoryStatusDecision
}

object CategoryStatusPolicy {
    fun evaluate(enabled: Boolean, memberships: Set<Long>, mappings: Map<Long, String>): CategoryStatusDecision {
        if (!enabled) return CategoryStatusDecision.Unmapped
        val applicable = memberships.mapNotNull { category ->
            mappings[category]?.takeIf(String::isNotBlank)?.let { it to category }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        return when (applicable.size) {
            0 -> CategoryStatusDecision.Unmapped
            1 -> applicable.entries.single().let { CategoryStatusDecision.Status(it.key, it.value) }
            else -> CategoryStatusDecision.Conflict(applicable)
        }
    }
}

enum class TrackerMatchConfidence { TRUSTED_ID, TITLE_SUGGESTION, AMBIGUOUS, UNRESOLVED }

data class TrackerMatchProposal(
    val candidates: List<TrackerTitle>,
    val confidence: TrackerMatchConfidence,
    val suggestedMediaId: String?,
)

/** A unique normalized title remains a suggestion, never permission to write a link/list. */
object BulkTrackerMatchPolicy {
    const val MAX_BATCH = 100

    fun propose(
        titles: Set<String>,
        candidates: List<TrackerTitle>,
        trustedMediaIds: Set<String> = emptySet(),
        expectedFormat: String? = null,
    ): TrackerMatchProposal {
        val distinct = candidates.distinctBy { it.mediaId }.filter { candidate ->
            expectedFormat == null || candidate.format == null || candidate.format.equals(expectedFormat, true)
        }
        val trusted = distinct.filter { it.mediaId in trustedMediaIds }
        if (trustedMediaIds.size > 1 || trusted.size > 1) {
            return TrackerMatchProposal(distinct, TrackerMatchConfidence.AMBIGUOUS, null)
        }
        if (trusted.size == 1) return TrackerMatchProposal(distinct, TrackerMatchConfidence.TRUSTED_ID, trusted.single().mediaId)
        val normalized = titles.map(::normalize).filter { it.length >= 3 }.toSet()
        val matches = distinct.filter { candidate ->
            (candidate.aliases + candidate.title).any { normalize(it) in normalized }
        }
        return when {
            matches.size == 1 -> TrackerMatchProposal(distinct, TrackerMatchConfidence.TITLE_SUGGESTION, matches.single().mediaId)
            matches.size > 1 -> TrackerMatchProposal(distinct, TrackerMatchConfidence.AMBIGUOUS, null)
            else -> TrackerMatchProposal(distinct, TrackerMatchConfidence.UNRESOLVED, null)
        }
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
}
