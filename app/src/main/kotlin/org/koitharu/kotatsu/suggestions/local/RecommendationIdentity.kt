package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import java.security.MessageDigest

/** Equal names are never identity evidence. Only source IDs or confirmed provider IDs may merge. */
internal object RecommendationIdentity {
    fun trackerVersion(values: List<ScrobblingEntity>): String {
        // Scores, notes and chapter updates must not trigger discovery during reading.
        val identities = values.map { "${it.scrobbler}:${it.mangaId}:${it.targetId}:${it.accountId}" }
            .distinct().sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(identities.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun feedbackKeys(value: Recommendation): Set<String> = buildSet {
        add(value.key)
        value.trustedIdentifiers.forEach { (provider, identifier) ->
            add("work:${value.manga.recommendationKind()}:$provider:$identifier")
        }
    }

    fun deduplicate(values: List<Recommendation>): List<Recommendation> {
        val seen = mutableSetOf<String>()
        val relations = mutableMapOf<String, MutableSet<String>>()
        values.filter { it.relatedTo.isNotEmpty() }.forEach { value ->
            feedbackKeys(value).forEach { key ->
                val seeds = relations.getOrPut(key, ::linkedSetOf)
                seeds.addAll(value.relatedTo.sorted().take(MAX_RECOMMENDATION_RELATION_SEEDS - seeds.size))
            }
        }
        return RecommendationContentPolicy.retainAdultMetadata(values)
            .sortedWith(compareBy<Recommendation> { it.target is RecommendationTarget.PublicTitle }
            .thenBy { if (it.provenance == RecommendationProvenance.RELATED) 0 else 1 }
            .thenByDescending { it.manga.tags.size + it.manga.authors.orEmpty().size }
            .thenBy { if (it.provenance == RecommendationProvenance.CACHE) 1 else 0 }
            .thenBy { it.key })
            .filter { value ->
                val keys = feedbackKeys(value)
                if (keys.any { it in seen }) false else { seen.addAll(keys); true }
            }
            .map { value ->
                value.copy(relatedTo = feedbackKeys(value).flatMap { relations[it].orEmpty() }
                    .distinct().sorted().take(MAX_RECOMMENDATION_RELATION_SEEDS).toSet())
            }
    }
}
