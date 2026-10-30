package org.koitharu.kotatsu.search.unified

import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.security.MessageDigest

/** Catalogue reads only. Next links are evidence, never arbitrary URLs to request with credentials. */
internal object TrackerCatalogue {
    private const val SIZE = 20

    suspend fun read(service: ScrobblerService, query: String, cursor: String?,
        fetch: suspend (Request) -> JsonElement): CataloguePage = decode(service, query, cursor,
        JSONTokener(fetch(request(service, query, cursor)).toString()).nextValue())

    fun request(service: ScrobblerService, query: String, cursor: String?): Request {
        require(query.trim().length in 2..100) { "Enter 2–100 characters for tracker catalogue search" }
        val page = page(service, query, cursor)
        val offset = (page - 1) * SIZE
        if (service == ScrobblerService.ANILIST) {
            val gql = """query { Page(page: $page, perPage: $SIZE) {
                pageInfo { hasNextPage }
                media(type: MANGA, sort: SEARCH_MATCH, search: ${JSONObject.quote(query)}) {
                    id title { userPreferred romaji english native } synonyms format isAdult chapters siteUrl
                    coverImage { large }
                }
            } }"""
            return Request.Builder().url("https://graphql.anilist.co").post(
                JSONObject().put("query", gql).toString().toRequestBody("application/json".toMediaType()),
            ).build()
        }
        val url = when (service) {
            ScrobblerService.MAL -> "https://api.myanimelist.net/v2/manga".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.take(64)).addQueryParameter("limit", "$SIZE")
                .addQueryParameter("offset", "$offset").addQueryParameter("nsfw", "true")
                .addQueryParameter("fields", "alternative_titles,media_type,nsfw,num_chapters")
            ScrobblerService.KITSU -> "https://kitsu.app/api/edge/manga".toHttpUrl().newBuilder()
                .addQueryParameter("filter[text]", query).addQueryParameter("page[limit]", "$SIZE")
                .addQueryParameter("page[offset]", "$offset")
            ScrobblerService.SHIKIMORI -> "https://shikimori.io/api/mangas".toHttpUrl().newBuilder()
                .addQueryParameter("search", query).addQueryParameter("page", "$page")
                .addQueryParameter("limit", "$SIZE").addQueryParameter("censored", "false")
            ScrobblerService.MANGABAKA -> "https://api.mangabaka.org/v2/series/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query).addQueryParameter("page", "$page").addQueryParameter("limit", "$SIZE")
            else -> error("Unsupported catalogue")
        }.build()
        return Request.Builder().url(url).get().build()
    }

    fun decode(service: ScrobblerService, query: String, cursor: String?, response: Any): CataloguePage {
        val page = page(service, query, cursor)
        val root = response as? JSONObject
        require(root == null || !root.has("errors") || root.optJSONArray("errors")?.length() == 0) {
            "Tracker rejected catalogue search"
        }
        val rows: JSONArray
        val next: Boolean
        when (service) {
            ScrobblerService.ANILIST -> {
                val value = requireNotNull(root).getJSONObject("data").getJSONObject("Page")
                rows = value.getJSONArray("media"); next = value.getJSONObject("pageInfo").getBoolean("hasNextPage")
            }
            ScrobblerService.MAL -> {
                rows = requireNotNull(root).getJSONArray("data")
                next = root.optJSONObject("paging")?.text("next") != null
            }
            ScrobblerService.KITSU -> {
                rows = requireNotNull(root).getJSONArray("data")
                next = root.optJSONObject("links")?.text("next") != null
            }
            ScrobblerService.SHIKIMORI -> {
                rows = response as? JSONArray ?: error("Invalid Shikimori catalogue")
                next = rows.length() >= SIZE
            }
            ScrobblerService.MANGABAKA -> {
                rows = requireNotNull(root).getJSONArray("data")
                val pagination = root.optJSONObject("pagination")
                next = if (pagination != null) pagination.text("next") != null else rows.length() == SIZE
            }
        }
        require(rows.length() <= 100) { "Tracker catalogue page is too large" }
        val titles = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val data = when (service) {
                ScrobblerService.MAL -> row.getJSONObject("node")
                ScrobblerService.KITSU -> row.getJSONObject("attributes")
                else -> row
            }
            val id = row.takeIf { service == ScrobblerService.KITSU }?.text("id") ?: data.text("id")
                ?: error("Tracker returned no work identity")
            val names = linkedSetOf<String>()
            val title: String
            val cover: String?
            val url: String
            val format: String?
            val total: String?
            when (service) {
                ScrobblerService.ANILIST -> {
                    data.getJSONObject("title").let { t ->
                        listOf("userPreferred", "romaji", "english", "native").mapNotNullTo(names) { t.text(it) }
                    }
                    data.optJSONArray("synonyms").strings().let(names::addAll)
                    title = names.firstOrNull() ?: "#$id"; cover = data.optJSONObject("coverImage")?.text("large")
                    url = "https://anilist.co/manga/$id"; format = data.text("format"); total = data.text("chapters")
                }
                ScrobblerService.MAL -> {
                    title = data.getString("title"); names += title
                    data.optJSONObject("alternative_titles")?.let {
                        listOf("en", "ja").mapNotNullTo(names) { key -> it.text(key) }
                        names += it.optJSONArray("synonyms").strings()
                    }
                    cover = data.optJSONObject("main_picture")?.text("large") ?: data.optJSONObject("main_picture")?.text("medium")
                    url = "https://myanimelist.net/manga/$id"; format = data.text("media_type"); total = data.text("num_chapters")
                }
                ScrobblerService.KITSU -> {
                    data.optJSONObject("titles")?.let { t -> t.keys().forEach { t.text(it)?.let(names::add) } }
                    title = data.text("canonicalTitle") ?: names.firstOrNull() ?: "#$id"
                    cover = data.optJSONObject("posterImage")?.text("medium")
                    url = "https://kitsu.app/manga/$id"; format = data.text("subtype"); total = data.text("chapterCount")
                }
                ScrobblerService.SHIKIMORI -> {
                    title = data.getString("name"); data.text("russian")?.let(names::add)
                    cover = data.optJSONObject("image")?.text("original")?.let {
                        if (it.startsWith('/')) "https://shikimori.io$it" else it
                    }
                    url = "https://shikimori.io/mangas/$id"; format = data.text("kind"); total = data.text("chapters")
                }
                ScrobblerService.MANGABAKA -> {
                    data.optJSONArray("titles")?.let { ts -> (0 until ts.length()).forEach { i ->
                        ts.optJSONObject(i)?.text("title")?.let(names::add)
                    } }
                    title = data.text("title") ?: names.firstOrNull() ?: "#$id"
                    cover = data.optJSONObject("cover")?.text("x250")
                    url = "https://mangabaka.org/$id"; format = data.text("type"); total = data.text("total_chapters")
                }
            }
            CatalogueTitle(id, title, (names - title).take(512).toSet(), cover, url, format, classify(service, data), total)
        }
        require(titles.map { it.id }.distinct().size == titles.size) { "Tracker returned duplicate catalogue identities" }
        return CataloguePage(titles, if (next && titles.isNotEmpty()) cursor(service, query, page + 1) else null)
    }

    fun classify(service: ScrobblerService, value: JsonElement): CatalogueContent =
        classify(service, JSONTokener(value.toString()).nextValue() as? JSONObject ?: JSONObject())

    fun classify(service: ScrobblerService, value: JSONObject): CatalogueContent = when (service) {
        ScrobblerService.ANILIST -> when (value.opt("isAdult")) {
            true -> CatalogueContent.ADULT; false -> CatalogueContent.SAFE; else -> CatalogueContent.UNKNOWN
        }
        ScrobblerService.MAL -> when (value.text("nsfw")?.lowercase()) {
            "white" -> CatalogueContent.SAFE; "gray" -> CatalogueContent.MIXED; "black" -> CatalogueContent.ADULT
            else -> CatalogueContent.UNKNOWN
        }
        ScrobblerService.KITSU -> when {
            value.opt("nsfw") == true -> CatalogueContent.ADULT
            else -> when (value.text("ageRating")?.uppercase()) {
                "G", "PG" -> CatalogueContent.SAFE; "R" -> CatalogueContent.MIXED; "R18" -> CatalogueContent.ADULT
                else -> CatalogueContent.UNKNOWN
            }
        }
        ScrobblerService.SHIKIMORI -> when (value.text("rating")?.lowercase()) {
            "g", "pg", "pg_13", "r" -> CatalogueContent.SAFE
            "r_plus" -> CatalogueContent.MIXED; "rx" -> CatalogueContent.ADULT; else -> CatalogueContent.UNKNOWN
        }
        ScrobblerService.MANGABAKA -> when (value.text("content_rating")?.lowercase()) {
            "safe" -> CatalogueContent.SAFE; "suggestive" -> CatalogueContent.MIXED
            "erotica", "pornographic" -> CatalogueContent.ADULT; else -> CatalogueContent.UNKNOWN
        }
    }

    private fun fingerprint(service: ScrobblerService, query: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${service.name}:$query".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun cursor(service: ScrobblerService, query: String, page: Int) = "1:${fingerprint(service, query)}:$page"
    private fun page(service: ScrobblerService, query: String, cursor: String?): Int {
        if (cursor == null) return 1
        val p = cursor.split(':')
        require(p.size == 3 && p[0] == "1" && p[1] == fingerprint(service, query)) { "Catalogue cursor changed" }
        return requireNotNull(p[2].toIntOrNull()).also { require(it in 2..1000) }
    }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else {
        require(length() <= 512)
        (0 until length()).mapNotNull { optString(it).takeIf { t -> !isNull(it) && t.isNotBlank() } }
    }
}
