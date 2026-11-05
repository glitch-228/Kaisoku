package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.core.parser.hasChapterText
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.parsers.model.Manga

enum class RecommendationKind { MANGA, NOVEL }
enum class RecommendationProvenance { CACHE, RELATED, GENRE, SEARCH, POPULAR, LATEST, ANILIST, MANGAUPDATES }

sealed interface RecommendationTarget {
    data class SourceTitle(val manga: Manga) : RecommendationTarget
    data class PublicTitle(val provider: String, val identifier: String, val manga: Manga) : RecommendationTarget
}

data class Recommendation(
    val target: RecommendationTarget,
    val score: Double = 0.0,
    val reason: RecommendationReason = RecommendationReason.DISCOVERY,
    val because: String? = null,
    val provenance: RecommendationProvenance = RecommendationProvenance.CACHE,
    val confidence: Double = 0.0,
    val trustedIdentifiers: Map<String, String> = emptyMap(),
    /** Seed identities for witnessed relationships; a shared cache's provenance alone is not evidence. */
    val relatedTo: Set<String> = emptySet(),
) {
    val manga: Manga get() = when (target) {
        is RecommendationTarget.SourceTitle -> target.manga
        is RecommendationTarget.PublicTitle -> target.manga
    }
    val key: String = when (target) {
        is RecommendationTarget.SourceTitle -> "source:${manga.source.name}:${manga.id}"
        is RecommendationTarget.PublicTitle -> "public:${manga.recommendationKind()}:${target.provider}:${target.identifier}"
    }
}

enum class RecommendationReason {
    BECAUSE_READ, BECAUSE_FAVORITE, GENRES, AUTHOR, RELATED, DISCOVERY, NEW_CHAPTERS, TITLE_TERMS, TITLE_RELATION,
}

internal const val MAX_RECOMMENDATION_RELATION_SEEDS = 32

internal fun Manga.recommendationSourceKey(): String = "source:${source.name}:$id"

internal fun RecommendationProviderLink.relationKey(seed: Manga): String =
    "provider:$provider:$identifier:$kind:${seed.recommendationSourceKey()}"

internal fun Recommendation.withRelationTo(seed: Manga, provider: RecommendationProviderLink? = null): Recommendation = copy(
    relatedTo = (listOf(provider?.relationKey(seed) ?: seed.recommendationSourceKey()) + relatedTo.sorted())
        .distinct().take(MAX_RECOMMENDATION_RELATION_SEEDS).toSet(),
)

fun Manga.recommendationKind(): RecommendationKind =
    when (val current = source.unwrap()) {
        is PublicRecommendationSource -> current.kind
        else -> if (current.hasChapterText()) RecommendationKind.NOVEL else RecommendationKind.MANGA
    }

sealed interface RecommendationRequest {
    data object Explore : RecommendationRequest
    data class Details(val seed: Manga) : RecommendationRequest
}

data class TasteTitle(
    val manga: Manga,
    val favorite: Boolean,
    val lastRead: Long,
    val durationMillis: Long = 0,
    /** How far through it the reader got, 0..1, or negative when not known. */
    val progress: Float = -1f,
    /** Chapters in the reading history, for estimating time spent when statistics are off. */
    val historyChapters: Int = 0,
)

data class RecommendationSnapshot(
    val results: List<Recommendation> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
)
