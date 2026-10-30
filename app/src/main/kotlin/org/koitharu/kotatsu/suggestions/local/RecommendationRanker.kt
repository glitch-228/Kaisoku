package org.koitharu.kotatsu.suggestions.local

import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/** Small content vectors; no model, embeddings, or library data leaves the device. */
object RecommendationRanker {
    private val stopWords = setOf("the", "a", "an", "of", "and", "to", "in", "my", "is", "no", "i", "и", "в", "на")
    private val separators = Regex("[\\p{P}\\p{Z}\\s]+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(separators, " ").trim()

    private fun terms(value: String): Set<String> = buildSet {
        normalize(value).split(' ').filter { it.length > 1 && it !in stopWords }.forEach { word ->
            val characters = ArrayList<String>()
            var index = 0
            var cjk = false
            while (index < word.length) {
                val code = Character.codePointAt(word, index)
                val end = index + Character.charCount(code)
                cjk = cjk || code in 0x3040..0x30ff || code in 0x3400..0x9fff || code in 0x20000..0x323af
                characters.add(word.substring(index, end))
                index = end
            }
            if (cjk) {
                characters.windowed(2).take(40).forEach { add(it.joinToString("")) }
            } else add(word)
        }
    }

    private fun features(title: org.koitharu.kotatsu.parsers.model.Manga): Map<String, Double> = buildMap {
        fun addGroup(prefix: String, items: Set<String>, weight: Double) {
            if (items.isEmpty()) return
            items.forEach { put(prefix + it, weight / items.size) }
        }
        addGroup("g:", title.tags.mapTo(linkedSetOf()) { normalize(it.title) }.filterTo(linkedSetOf()) { it.isNotEmpty() }, .7)
        addGroup("a:", title.authors.orEmpty().mapTo(linkedSetOf(), ::normalize).filterTo(linkedSetOf()) { it.isNotEmpty() }, .2)
        addGroup("t:", (title.altTitles + title.title).flatMapTo(linkedSetOf(), ::terms), .1)
    }

    private class Weights(val values: Map<String, Double>) {
        val entries = values.entries.toTypedArray()
    }

    private val emptyWeights = Weights(emptyMap())

    private fun similarity(a: Weights, b: Weights): Double {
        // Weighted overlap keeps absent metadata absent (no renormalization to invented 100% similarity).
        var overlap = 0.0
        for (entry in a.entries) {
            overlap += min(entry.value, b.values[entry.key] ?: 0.0)
        }
        return overlap.coerceIn(0.0, 1.0)
    }

    fun rank(
        candidates: List<Recommendation>,
        taste: List<TasteTitle>,
        request: RecommendationRequest,
        diversity: Int,
        now: Long,
        limit: Int = 160,
        titleOnlyDetails: Boolean = false,
        publicRelationKeys: Set<String> = emptySet(),
    ): List<Recommendation> {
        if (titleOnlyDetails && request is RecommendationRequest.Details) {
            return rankTitleOnly(candidates, request.seed, limit, publicRelationKeys)
        }
        val titles = taste.distinctBy { it.manga.source.name to it.manga.id }
        val vectors = candidates.associate { it.key to features(it.manga) }
        val tasteVectors = titles.map { it to features(it.manga) }
        val documents = vectors.values + tasteVectors.map { it.second }
        val frequency = documents.flatMap { it.keys }.groupingBy { it }.eachCount()
        fun downweight(vector: Map<String, Double>) = vector.mapValues { (key, weight) ->
            weight / sqrt((frequency[key] ?: 1).toDouble())
        }
        val candidateVectors = vectors.mapValues { Weights(downweight(it.value)) }
        val weightedTasteVectors = tasteVectors.map { (title, vector) -> title to Weights(downweight(vector)) }
        val seed = (request as? RecommendationRequest.Details)?.seed
        val seedVector = seed?.let { Weights(downweight(features(it))) } ?: emptyWeights
        val tasteByKind = weightedTasteVectors.groupBy { it.first.manga.recommendationKind() }
        val profiles = tasteByKind.mapValues { (_, input) ->
            val weighted = input.map { (title, vector) ->
                val recency = if (title.lastRead > 0) exp(-ln(2.0) * (now - title.lastRead).coerceAtLeast(0) / (90.0 * 86400000)) else 1.0
                val engagement = ln(1.0 + title.durationMillis.coerceIn(0, 36_000_000) / 60000.0).coerceAtMost(6.0)
                vector to ((if (title.favorite) 4.0 else 0.0) + (if (title.lastRead > 0) 1.0 else 0.0) + engagement) * recency
            }
            val total = weighted.sumOf { it.second }.coerceAtLeast(1.0)
            Weights(buildMap<String, Double> {
                weighted.forEach { (vector, weight) ->
                    vector.values.forEach { (feature, value) ->
                        put(feature, (get(feature) ?: 0.0) + value * weight / total)
                    }
                }
            })
        }
        val ranked = candidates.distinctBy { it.key }.take(500).map { candidate ->
            val vector = candidateVectors.getValue(candidate.key)
            val personal = similarity(vector, profiles[candidate.manga.recommendationKind()] ?: emptyWeights)
            val similar = similarity(vector, seedVector)
            val related = if (candidate.provenance == RecommendationProvenance.RELATED) .08 else 0.0
            val score = (if (seed == null) personal else .65 * similar + .35 * personal) + related
            val because = tasteByKind[candidate.manga.recommendationKind()].orEmpty()
                .maxByOrNull { similarity(vector, it.second) }
                ?.takeIf { similarity(vector, it.second) > .015 }?.first
            val reason = when {
                related > 0 -> RecommendationReason.RELATED
                because != null && personal > similar -> if (because.lastRead > 0) RecommendationReason.BECAUSE_READ
                    else RecommendationReason.BECAUSE_FAVORITE
                vector.values.keys.any { it.startsWith("g:") && it in seedVector.values } -> RecommendationReason.GENRES
                vector.values.keys.any { it.startsWith("a:") && it in seedVector.values } -> RecommendationReason.AUTHOR
                because != null -> if (because.lastRead > 0) RecommendationReason.BECAUSE_READ else RecommendationReason.BECAUSE_FAVORITE
                else -> RecommendationReason.DISCOVERY
            }
            candidate.copy(score = score.coerceIn(0.0, 1.0), confidence = score.coerceIn(0.0, 1.0), reason = reason, because = because?.manga?.title)
        }.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.key })
        val output = ArrayList<Recommendation>(min(limit, ranked.size))
        val remaining = ranked.toMutableList()
        val redundancyScores = mutableMapOf<String, Double>()
        val amount = diversity.coerceIn(0, 100) / 100.0
        val explorationShare = .4 * amount
        // A stable spread schedule, not a random draw on every observer/recreation.
        while (remaining.isNotEmpty() && output.size < limit) {
            val explore = ((output.size + 1) * explorationShare).toInt() > (output.size * explorationShare).toInt()
            val next = remaining.maxWithOrNull(compareBy<Recommendation> { candidate ->
                val redundancy = redundancyScores[candidate.key] ?: 0.0
                if (explore) (1 - redundancy) * .5 + (1 - candidate.score) * .15
                else candidate.score - amount * .3 * redundancy
            }.thenByDescending { it.key }) ?: break
            output.add(next)
            remaining.remove(next)
            remaining.forEach { candidate ->
                redundancyScores[candidate.key] = maxOf(redundancyScores[candidate.key] ?: 0.0,
                    similarity(candidateVectors.getValue(candidate.key), candidateVectors.getValue(next.key)))
            }
        }
        return output
    }

    private fun rankTitleOnly(
        candidates: List<Recommendation>,
        seed: org.koitharu.kotatsu.parsers.model.Manga,
        limit: Int,
        publicRelationKeys: Set<String>,
    ): List<Recommendation> {
        val seedKey = seed.recommendationSourceKey()
        val eligible = candidates.distinctBy { it.key }.filter {
            it.key != seedKey && it.manga.recommendationKind() == seed.recommendationKind()
        }.take(500)
        val seedFeatures = features(seed)
        val vectors = eligible.associate { it.key to features(it.manga) }
        // Neither personal scores nor history-dependent document frequencies belong in this mode.
        val frequency = (vectors.values + listOf(seedFeatures)).flatMap { it.keys }.groupingBy { it }.eachCount()
        fun weights(vector: Map<String, Double>) = Weights(vector.mapValues { (key, value) ->
            value / sqrt((frequency[key] ?: 1).toDouble())
        })
        val seedVector = weights(seedFeatures)
        return eligible.mapNotNull { candidate ->
            val vector = weights(vectors.getValue(candidate.key))
            val similar = similarity(vector, seedVector)
            val related = seedKey in candidate.relatedTo || publicRelationKeys.any { it in candidate.relatedTo }
            if (similar <= 0.0 && !related) return@mapNotNull null
            val reason = when {
                related -> RecommendationReason.TITLE_RELATION
                vector.values.keys.any { it.startsWith("g:") && it in seedFeatures } -> RecommendationReason.GENRES
                vector.values.keys.any { it.startsWith("a:") && it in seedFeatures } -> RecommendationReason.AUTHOR
                else -> RecommendationReason.TITLE_TERMS
            }
            val score = (similar + if (related) .08 else 0.0).coerceIn(0.0, 1.0)
            candidate.copy(score = score, confidence = score, reason = reason, because = null)
        }.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.key }).take(limit.coerceAtLeast(0))
    }
}
