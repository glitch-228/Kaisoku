package org.koitharu.kotatsu.suggestions.local

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.model.MangaSourceRegistry
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag

/** UI-only public title. Routing always goes to a readable-source search/chooser. */
data class PublicRecommendationSource(val provider: String, val kind: RecommendationKind) : MangaSource {
    override val name: String = "RECOMMENDATION:$provider:${kind.name}"
}

object RecommendationCodec {
    private fun JSONArray.strings() = (0 until length()).mapTo(linkedSetOf()) { getString(it) }
    fun encode(value: Recommendation): JSONObject = JSONObject().apply {
        val manga = value.manga
        put("id", manga.id); put("title", manga.title); put("aliases", JSONArray(manga.altTitles))
        put("url", manga.url); put("publicUrl", manga.publicUrl); put("rating", manga.rating.takeIf { it.isFinite() } ?: -1f)
        put("cover", manga.coverUrl); put("source", manga.source.name); put("adult", manga.contentRating?.name)
        put("authors", JSONArray(manga.authors)); put("state", manga.state?.name)
        put("tags", JSONArray(manga.tags.map { JSONObject().put("title", it.title).put("key", it.key) }))
        put("provenance", value.provenance.name); put("score", value.score); put("reason", value.reason.name)
        put("because", value.because); put("confidence", value.confidence)
        put("identifiers", JSONObject(value.trustedIdentifiers))
        (value.target as? RecommendationTarget.PublicTitle)?.let {
            put("provider", it.provider); put("identifier", it.identifier)
            put("kind", (manga.source as PublicRecommendationSource).kind.name)
        }
    }

    fun decode(json: JSONObject): Recommendation? = runCatching {
        val provider = json.optString("provider")
        val source = if (provider.isNotEmpty()) PublicRecommendationSource(provider, RecommendationKind.valueOf(json.getString("kind")))
        else org.koitharu.kotatsu.core.model.MangaSource(json.getString("source"))
        val tags = json.getJSONArray("tags")
        val manga = Manga(json.getLong("id"), json.getString("title"), json.getJSONArray("aliases").strings(),
            json.getString("url"), json.getString("publicUrl"), json.optDouble("rating", -1.0).toFloat(),
            json.optString("adult").takeIf { it.isNotEmpty() }?.let { ContentRating.valueOf(it) },
            json.optString("cover").takeIf { it.isNotEmpty() },
            (0 until tags.length()).mapTo(linkedSetOf()) {
                tags.getJSONObject(it).let { tag -> MangaTag(tag.getString("title"), tag.getString("key"), source) }
            }, json.optString("state").takeIf { it.isNotEmpty() }?.let { MangaState.valueOf(it) },
            json.getJSONArray("authors").strings(), source = source)
        Recommendation(if (provider.isEmpty()) RecommendationTarget.SourceTitle(manga)
            else RecommendationTarget.PublicTitle(provider, json.getString("identifier"), manga),
            json.optDouble("score"), RecommendationReason.valueOf(json.getString("reason")),
            json.optString("because").takeIf { it.isNotEmpty() }, RecommendationProvenance.valueOf(json.getString("provenance")),
            json.optDouble("confidence", 0.0), json.optJSONObject("identifiers")?.let { identifiers ->
                identifiers.keys().asSequence().associateWith { identifiers.getString(it) }
            }.orEmpty())
    }.getOrNull()

    fun encodeList(values: List<Recommendation>): String = JSONArray(values.map(::encode)).toString()
    fun decodeList(raw: String): List<Recommendation> = runCatching {
        val values = JSONArray(raw)
        (0 until values.length()).mapNotNull { decode(values.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun encodeBaseline(value: ChapterBaseline): String = JSONObject().apply {
        put("branch", value.branch); put("total", value.total); put("digest", value.digest)
        put("number", value.terminalNumber); put("date", value.terminalDate); put("observed", value.observedAt)
        put("releases", value.releases); put("read", value.lastReadingAt)
        put("releaseIds", JSONArray(value.releaseChapterIds.sorted()))
        put("readerBoundary", value.confirmedReadingBoundary); put("exactGeneration", value.exactGeneration)
        put("releaseIdentityVersion", value.releaseIdentityVersion)
        put("chapterListIdentity", value.chapterListIdentity)
    }.toString()
    fun decodeBaseline(raw: String): ChapterBaseline? = runCatching {
        require(raw.length <= 512 * 1024)
        JSONObject(raw).let { json ->
            val ids = json.optJSONArray("releaseIds")
            require((ids?.length() ?: 0) <= ChapterReleaseEvidence.MAX_RELEASE_IDENTITIES)
            ChapterBaseline(json.optString("branch").takeIf(String::isNotEmpty), json.getInt("total"),
            json.getString("digest"), json.getDouble("number").toFloat(), json.getLong("date"), json.getLong("observed"),
            json.getInt("releases"), json.getLong("read"),
            (0 until (ids?.length() ?: 0)).mapTo(linkedSetOf()) { ids!!.getLong(it) },
            json.optBoolean("readerBoundary", false),
            json.optString("exactGeneration").takeIf { it.isNotEmpty() && it != "null" && it.length <= 128 },
            json.optInt("releaseIdentityVersion", 0), json.optString("chapterListIdentity")) }
    }.getOrNull()
}
