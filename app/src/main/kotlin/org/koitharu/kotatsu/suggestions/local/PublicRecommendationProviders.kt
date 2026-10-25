package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor
import org.koitharu.kotatsu.core.network.FlareSolverrInterceptor
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.await
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class PublicRecommendationRateLimit(val retryAt: Long) : IOException("Public recommendations: too many requests")

@Singleton
class PublicRecommendationProviders @Inject constructor(
    @BaseHttpClient baseClient: OkHttpClient,
    private val settings: AppSettings,
    private val store: RecommendationStore,
    private val library: MangaDatabase,
) {
    private val client = baseClient.newBuilder().apply {
        interceptors().removeAll { it is RateLimitInterceptor || it is CloudFlareInterceptor || it is FlareSolverrInterceptor }
        callTimeout(20, TimeUnit.SECONDS)
    }.build()
    private val parallelism = Semaphore(1)
    private val pendingCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            settings.observe(AppSettings.KEY_SUGGESTIONS_PUBLIC, AppSettings.KEY_SUGGESTIONS_LEGACY).collect {
                if (!enabled()) pendingCalls.forEach { it.cancel() }
            }
        }
    }

    suspend fun withTrustedIdentities(values: List<Recommendation>, contentOverrides: Boolean = true): List<Recommendation> {
        val confirmations = store.dao.links().groupBy { it.key }
        val trackers = library.getScrobblingDao().observeAll(2).first().groupBy { it.mangaId }
        val sourceIds = values.filter { it.target is RecommendationTarget.SourceTitle }
            .map { it.manga.id }.distinct().toLongArray()
        val knownTitles = library.getMangaDao().findAll(sourceIds).associate { it.manga.id to it.toManga() }
        val ratings = if (!contentOverrides) emptyMap() else library.getPreferencesDao().getOverrides(sourceIds).mapNotNull { pref ->
            org.koitharu.kotatsu.core.db.entity.ContentRating(pref.contentRatingOverride)?.let { pref.mangaId to it }
        }.toMap()
        return values.map { value ->
            val target = value.target
            val confirmed = confirmations[value.key].orEmpty().filter { it.kind == value.manga.recommendationKind().name }
                .associate { it.provider to it.identifier }
            val ids = trackers[value.manga.id].orEmpty().map { it.targetId }.filter { it > 0 }.distinct()
            val known = knownTitles[value.manga.id]?.takeIf { it.source.name == value.manga.source.name }
            val trusted = if (known != null) ids.singleOrNull()?.toString() else null
            val direct = Regex("https://anilist\\.co/manga/(\\d+)(?:/|$)").find(value.manga.publicUrl)?.groupValues?.get(1)
            value.copy(target = if (target is RecommendationTarget.SourceTitle) target.copy(
                manga = RecommendationContentPolicy.withKnownRating(target.manga, known,
                    ratings[value.manga.id].takeIf { known != null }),
            ) else target, trustedIdentifiers = confirmed + when {
                target is RecommendationTarget.PublicTitle -> mapOf(target.provider to target.identifier)
                confirmed.containsKey("ANILIST") -> emptyMap()
                direct != null || trusted != null -> mapOf("ANILIST" to (direct ?: trusted!!))
                else -> emptyMap()
            })
        }
    }

    internal suspend fun readingStates(values: List<Recommendation>,
        baselines: Map<String, ChapterBaseline?> = emptyMap()): Map<String, List<RecommendationReadingState>> {
        val workKeys = values.flatMapTo(mutableSetOf(), RecommendationIdentity::feedbackKeys)
        val trackers = library.getScrobblingDao().observeAll(2).first()
            .filter { "work:MANGA:ANILIST:${it.targetId}" in workKeys || "work:NOVEL:ANILIST:${it.targetId}" in workKeys }
        val confirmations = store.dao.links().filter { "work:${it.kind}:${it.provider}:${it.identifier}" in workKeys }
        val ids = (values.filter { it.target is RecommendationTarget.SourceTitle }.map { it.manga.id } +
            trackers.map { it.mangaId } + confirmations.mapNotNull { it.key.substringAfterLast(':').toLongOrNull() })
            .distinct().toLongArray()
        val titles = ids.asList().chunked(400).flatMap { library.getMangaDao().findAll(it.toLongArray()) }
            .map { it.manga.toManga(it.tags.toMangaTags(), null) }
        val enriched = withTrustedIdentities(titles.map { Recommendation(RecommendationTarget.SourceTitle(it)) })
        val histories = ids.asList().chunked(400).flatMap { library.getHistoryDao().findAllByIds(it.toLongArray()) }.associateBy { it.mangaId }
        val exactStates = RecommendationExactProgress(library).read(ids.asList(), baselines)
        val byIdentity = mutableMapOf<String, MutableList<RecommendationReadingState>>()
        enriched.forEach { title ->
            val history = histories[title.manga.id]
            val exact = exactStates[title.manga.id]
            if (history != null || exact != null) {
                val state = RecommendationReadingState(title.key, history?.updatedAt ?: 0L,
                    history?.percent ?: 0f, history?.chaptersCount ?: 0, exact)
                RecommendationIdentity.feedbackKeys(title).forEach { identity -> byIdentity.getOrPut(identity, ::mutableListOf).add(state) }
            }
        }
        return values.associate { value -> value.key to RecommendationIdentity.feedbackKeys(value)
            .flatMap { byIdentity[it].orEmpty() }.distinctBy { it.key } }
    }

    suspend fun links(seed: Manga): List<RecommendationProviderLink> {
        if (!enabled()) return emptyList()
        val key = "source:${seed.source.name}:${seed.id}"
        val confirmed = store.dao.links(key).filter { it.kind == seed.recommendationKind().name }
        if (confirmed.any { it.provider == "ANILIST" }) return confirmed
        val ids = library.getScrobblingDao().findForManga(seed.id).filter { it.scrobbler == 2 && it.targetId > 0 }
            .map { it.targetId }.distinct()
        val direct = Regex("https://anilist\\.co/manga/(\\d+)(?:/|$)").find(seed.publicUrl)?.groupValues?.get(1)
        return confirmed + listOfNotNull((direct ?: ids.singleOrNull()?.toString())?.let {
            RecommendationProviderLink(key, "ANILIST", it, seed.recommendationKind().name)
        })
    }

    suspend fun search(provider: String, seed: Manga): List<Recommendation> {
        check(enabled())
        return if (provider == "ANILIST") {
            val data = call(provider, anilist("query(\$search:String){Page(perPage:8){media(search:\$search,type:MANGA){$MEDIA_FIELDS}}}",
                JSONObject().put("search", seed.title.take(120))))
            items(data.getJSONObject("data").getJSONObject("Page").getJSONArray("media")).mapNotNull { PublicRecommendationMetadata.anilistTitle(it) }
                .filter { allowedMatch(it, seed) }
        } else {
            val response = call(provider, Request.Builder().url("https://api.mangaupdates.com/v1/series/search")
                .post(JSONObject().put("search", seed.title.take(120)).put("stype", "title").put("perpage", 8)
                    .toString().toRequestBody(JSON)).build())
            items(response.getJSONArray("results")).mapNotNull { PublicRecommendationMetadata.muTitle(it.getJSONObject("record")) }
                .filter { allowedMatch(it, seed) }
        }
    }

    suspend fun confirm(seed: Manga, result: Recommendation) {
        check(enabled())
        val target = result.target as RecommendationTarget.PublicTitle
        check(allowedMatch(result, seed))
        store.dao.link(RecommendationProviderLink("source:${seed.source.name}:${seed.id}", target.provider, target.identifier,
            seed.recommendationKind().name))
        val key = RecommendationEngine.contextKey(RecommendationRequest.Details(seed))
        store.dao.result(key)?.let { row ->
            store.dao.result(row.copy(payload = JSONArray(store.results(row)
                .filter { it.target !is RecommendationTarget.PublicTitle }.map { it.key }).toString(), refreshed = 0))
        }
        store.changed()
    }

    suspend fun related(link: RecommendationProviderLink,
        bounded: suspend (String, suspend () -> JSONObject) -> JSONObject?): List<Recommendation> {
        if (!enabled()) return emptyList()
        if (link.provider == "ANILIST") {
            val query = "query(\$id:Int){Media(id:\$id,type:MANGA){format recommendations(perPage:20,sort:RATING_DESC){nodes{mediaRecommendation{$MEDIA_FIELDS}}}}}"
            val response = bounded("public:ANILIST") { call("ANILIST", anilist(query, JSONObject().put("id", link.identifier.toInt()))) } ?: return emptyList()
            val media = response.getJSONObject("data").optJSONObject("Media") ?: return emptyList()
            if ((media.optString("format") == "NOVEL") != (link.kind == "NOVEL")) return emptyList()
            return items(media.getJSONObject("recommendations").getJSONArray("nodes"))
                .mapNotNull { it.optJSONObject("mediaRecommendation")?.let(PublicRecommendationMetadata::anilistTitle) }
                .filter { it.manga.recommendationKind().name == link.kind }
        }
        val seed = bounded("public:MANGAUPDATES") { call("MANGAUPDATES", muRequest(link.identifier)) } ?: return emptyList()
        if ((seed.optString("type") == "Novel") != (link.kind == "NOVEL")) return emptyList()
        // Recommendations lack reliable format/adult metadata. Retrieve at most three compact series records.
        return items(seed.optJSONArray("recommendations") ?: JSONArray()).take(3).mapNotNull { rec ->
            bounded("public:MANGAUPDATES") { call("MANGAUPDATES", muRequest(rec.getLong("series_id").toString())) }
                ?.let(PublicRecommendationMetadata::muTitle)?.takeIf { it.manga.recommendationKind().name == link.kind }
        }
    }

    private fun enabled() = settings.publicRecommendations && !settings.useLegacySuggestions
    private fun allowedMatch(result: Recommendation, seed: Manga): Boolean =
        result.manga.recommendationKind() == seed.recommendationKind() &&
            RecommendationContentPolicy.allowed(result.manga, settings.isNsfwContentDisabled || settings.isSuggestionsExcludeNsfw) &&
            result.manga.tags.none { tag -> settings.suggestionsTagsBlacklist.any {
                RecommendationRanker.normalize(it) == RecommendationRanker.normalize(tag.title)
            } }
    private fun muRequest(id: String) = Request.Builder().url("https://api.mangaupdates.com/v1/series/$id").get().build()
    private fun anilist(query: String, variables: JSONObject) = Request.Builder().url("https://graphql.anilist.co")
        .post(JSONObject().put("query", query).put("variables", variables).toString().toRequestBody(JSON)).build()

    private suspend fun call(provider: String, request: Request): JSONObject = parallelism.withPermit { RecommendationOperations.run {
        check(enabled())
        val cooldown = store.dao.cooldown("public:$provider")?.until ?: 0
        if (cooldown > System.currentTimeMillis()) throw PublicRecommendationRateLimit(cooldown)
        withTimeout(20_000) {
            val pending = client.newCall(request.newBuilder().header("User-Agent", "Kaisoku recommendations").build())
            pendingCalls.add(pending)
            if (!enabled()) pending.cancel()
            try { pending.await().use { response ->
                if (response.code == 429) {
                    val retry = response.header("Retry-After")
                    val now = System.currentTimeMillis()
                    val until = retry?.toLongOrNull()?.let { now + it.coerceIn(1, 86400) * 1000 }
                        ?: runCatching { ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                        ?: now + 60_000
                    store.dao.cooldown(RecommendationCooldown("public:$provider", until))
                    throw PublicRecommendationRateLimit(until)
                }
                if (!response.isSuccessful) throw IOException("$provider HTTP ${response.code}")
                val json = JSONObject(response.body?.string() ?: throw IOException("$provider: empty response"))
                if (json.has("errors")) throw IOException("$provider: ${json.getJSONArray("errors")}")
                check(enabled())
                json
            } } finally { pendingCalls.remove(pending) }
        }
    } }

    private fun items(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MEDIA_FIELDS = "id format siteUrl isAdult title{romaji english native} coverImage{large} genres"
    }
}
