package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import org.koitharu.kotatsu.scrobbling.v2.TrackerTitle
import java.security.MessageDigest

/** Fixed-origin requests and lossless codecs. This lane has no local-library or mutation side effects. */
internal object TrackerRawLibrary {
    const val MAX_ENTRIES = 20_000
    const val MAX_PAGES = 1_000
    private val digestPattern = Regex("[0-9a-f]{64}")

    private data class Cursor(val page: Int, val previous: String?)

    fun request(service: ScrobblerService, owner: TrackerOwner, revision: Long, cursor: String?): Request {
        val page = cursor(service, owner, revision, cursor).page
        val size = pageSize(service)
        val offset = (page - 1) * size
        val url = when (service) {
            ScrobblerService.ANILIST -> {
                val query = """
                    query { Page(page: $page, perPage: $size) {
                        pageInfo { hasNextPage }
                        mediaList(userId: ${numericOwner(owner)}, type: MANGA, sort: MEDIA_ID) {
                            id userId mediaId status progress progressVolumes score(format: POINT_100) notes updatedAt
                            media { id type chapters format description siteUrl synonyms
                                title { userPreferred romaji english native } coverImage { large } }
                        }
                    } }
                """.trimIndent()
                val body = buildJsonObject { put("query", query) }.toString()
                    .toRequestBody("application/json".toMediaType())
                return Request.Builder().url("https://graphql.anilist.co").post(body).build()
            }
            ScrobblerService.MAL -> "https://api.myanimelist.net/v2/users/@me/mangalist".toHttpUrl().newBuilder()
                .addQueryParameter("limit", size.toString()).addQueryParameter("offset", offset.toString())
                .addQueryParameter("nsfw", "true")
                .addQueryParameter("fields", "list_status{status,score,num_chapters_read,num_volumes_read,comments,updated_at,is_rereading},num_chapters,alternative_titles,media_type")
                .build()
            ScrobblerService.KITSU -> "https://kitsu.app/api/edge/library-entries".toHttpUrl().newBuilder()
                .addQueryParameter("filter[user_id]", numericOwner(owner))
                .addQueryParameter("filter[media_type]", "Manga")
                .addQueryParameter("include", "manga,user")
                .addQueryParameter("sort", "id")
                .addQueryParameter("page[limit]", size.toString())
                .addQueryParameter("page[offset]", offset.toString()).build()
            ScrobblerService.SHIKIMORI -> "https://shikimori.io/api/users/${numericOwner(owner)}/manga_rates"
                .toHttpUrl().newBuilder().addQueryParameter("page", page.toString())
                .addQueryParameter("limit", size.toString()).addQueryParameter("censored", "false").build()
            ScrobblerService.MANGABAKA -> "https://api.mangabaka.org/v2/my/library".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString()).addQueryParameter("limit", size.toString())
                .addQueryParameter("sort_by", "created_at_asc").build()
        }
        return Request.Builder().url(url).get().build()
    }

    suspend fun read(
        service: ScrobblerService,
        owner: TrackerOwner,
        revision: Long,
        cursor: String?,
        fetch: suspend (Request) -> JsonElement,
    ): TrackerLibraryPage {
        currentCoroutineContext().ensureActive()
        val response = fetch(request(service, owner, revision, cursor))
        currentCoroutineContext().ensureActive()
        return decode(service, owner, revision, cursor, response).also { currentCoroutineContext().ensureActive() }
    }

    fun decode(
        service: ScrobblerService,
        owner: TrackerOwner,
        revision: Long,
        cursor: String?,
        response: JsonElement,
    ): TrackerLibraryPage {
        val position = cursor(service, owner, revision, cursor)
        val size = pageSize(service)
        val rows: List<JsonObject>
        val hasNext: Boolean
        val entries: List<TrackerRemoteEntry>
        when (service) {
            ScrobblerService.ANILIST -> {
                val root = response.objectValue()
                require((root["errors"] as? JsonArray).isNullOrEmpty()) { "AniList rejected the library query" }
                val page = root.obj("data").obj("Page")
                rows = page.array("mediaList")
                hasNext = page.obj("pageInfo").raw("hasNextPage").strictBoolean()
                require(rows.size <= size) { "Tracker page is too large" }
                entries = rows.map { row ->
                    require(row.raw("userId") == owner.accountId) { "Tracker returned another account" }
                    val media = row.obj("media")
                    val id = identity(media, "id")
                    require(row.raw("mediaId") == id && media.raw("type") == "MANGA") {
                        "Tracker returned another work or adaptation"
                    }
                    val names = media.obj("title")
                    val title = title(id, names.raw("userPreferred") ?: names.raw("romaji")
                        ?: names.raw("english") ?: names.raw("native"),
                        setOfNotNull(names.raw("romaji"), names.raw("english"), names.raw("native")) + media.textArray("synonyms"),
                        media["coverImage"].objectOrNull()?.raw("large"), media.raw("description"),
                        "https://anilist.co/manga/$id", media.raw("format"))
                    TrackerRawRead.entry(service, owner.accountId, id, title, row).copy(total = media.raw("chapters"))
                }
            }
            ScrobblerService.MAL -> {
                val root = response.objectValue()
                rows = root.array("data")
                require(rows.size <= size) { "Tracker page is too large" }
                hasNext = root["paging"].objectOrNull()?.raw("next") != null
                entries = rows.map { row ->
                    val media = row.obj("node")
                    val id = identity(media, "id")
                    val names = media["alternative_titles"].objectOrNull()
                    val title = title(id, media.raw("title"), names?.let {
                        setOfNotNull(it.raw("en"), it.raw("ja")) + it.textArray("synonyms")
                    }.orEmpty(), media["main_picture"].objectOrNull()?.raw("large"), null,
                        "https://myanimelist.net/manga/$id", media.raw("media_type"))
                    TrackerRawRead.entry(service, owner.accountId, id, title, row.obj("list_status"))
                        .copy(total = media.raw("num_chapters"))
                }
            }
            ScrobblerService.KITSU -> {
                val root = response.objectValue()
                require((root["errors"] as? JsonArray).isNullOrEmpty()) { "Kitsu rejected the library query" }
                rows = root.array("data")
                require(rows.size <= size) { "Tracker page is too large" }
                val included = root["included"] as? JsonArray ?: JsonArray(emptyList())
                require(included.size <= size * 2) { "Tracker metadata page is too large" }
                val manga = linkedMapOf<String, JsonObject>()
                included.map { it.objectValue() }.filter { it.raw("type") == "manga" }.forEach {
                    val old = manga.put(identity(it, "id"), it)
                    require(old == null || old == it) { "Kitsu returned conflicting work metadata" }
                }
                hasNext = root["links"].objectOrNull()?.raw("next") != null
                entries = rows.map { row ->
                    require(row.raw("type") == "libraryEntries") { "Invalid Kitsu library resource" }
                    val relationships = row.obj("relationships")
                    val account = relationships.obj("user").obj("data")
                    require(account.raw("type") == "users" && account.raw("id") == owner.accountId) {
                        "Tracker returned another account"
                    }
                    val work = relationships.obj("manga").obj("data")
                    require(work.raw("type") == "manga") { "Tracker returned another adaptation" }
                    val id = identity(work, "id")
                    val media = manga[id] ?: error("Kitsu library is missing included manga metadata")
                    val attrs = media.obj("attributes")
                    val names = attrs["titles"].objectOrNull()?.values?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    }?.toSet().orEmpty()
                    val title = title(id, attrs.raw("canonicalTitle") ?: names.firstOrNull(), names,
                        attrs["posterImage"].objectOrNull()?.raw("medium"), attrs.raw("description"),
                        "https://kitsu.app/manga/$id", attrs.raw("subtype"))
                    TrackerRawRead.entry(service, owner.accountId, id, title, row).copy(total = attrs.raw("chapterCount"))
                }
            }
            ScrobblerService.SHIKIMORI -> {
                rows = (response as? JsonArray)?.map { it.objectValue() } ?: error("Invalid Shikimori library response")
                require(rows.size <= size + 1) { "Tracker page is too large" }
                // The public controller intentionally returns one lookahead; page offsets still use limit.
                hasNext = rows.size > size
                entries = rows.take(size).map { row ->
                    row.raw("user_id")?.let { require(it == owner.accountId) { "Tracker returned another account" } }
                    row["user"].objectOrNull()?.raw("id")?.let {
                        require(it == owner.accountId) { "Tracker returned another account" }
                    }
                    row.raw("target_type")?.let { require(it == "Manga") { "Tracker returned another adaptation" } }
                    val media = row.obj("manga")
                    val id = identity(media, "id")
                    row.raw("target_id")?.let { require(it == id) { "Tracker returned another title" } }
                    val title = title(id, media.raw("name"), setOfNotNull(media.raw("russian")),
                        absoluteShikimori(media["image"].objectOrNull()?.raw("original")), null,
                        "https://shikimori.io/mangas/$id", media.raw("kind"))
                    TrackerRawRead.entry(service, owner.accountId, id, title, row).copy(total = media.raw("chapters"))
                }
            }
            ScrobblerService.MANGABAKA -> {
                val root = response.objectValue()
                rows = root.array("data")
                require(rows.size <= size) { "Tracker page is too large" }
                val pagination = root.obj("pagination")
                require(pagination.raw("page") == position.page.toString() && pagination.raw("limit") == size.toString()) {
                    "MangaBaka returned another library page"
                }
                hasNext = pagination.raw("next") != null
                entries = rows.map { row ->
                    val entry = row.obj("entry")
                    require(entry.raw("user_id") == owner.accountId) { "Tracker returned another account" }
                    val media = row.obj("series")
                    val id = identity(media, "id")
                    require(entry.raw("series_id") == id) { "Tracker returned another work" }
                    identity(entry, "id")
                    val names = (media["titles"] as? JsonArray)?.map { it.objectValue() }.orEmpty()
                    require(names.size <= 512) { "Tracker returned too many aliases" }
                    val title = title(id, names.firstOrNull { it.raw("is_primary") == "true" }?.raw("title")
                        ?: names.firstOrNull()?.raw("title"), names.mapNotNull { it.raw("title") }.toSet(),
                        media["cover"].objectOrNull()?.raw("x250"), media.raw("description"),
                        "https://mangabaka.org/$id", media.raw("type"))
                    TrackerRawRead.entry(service, owner.accountId, id, title, entry).copy(total = media.raw("total_chapters"))
                }
            }
        }
        require(entries.all { it.reference.owner == owner }) { "Tracker returned another account" }
        val identities = entries.map { it.reference.mediaId to it.reference.listEntryId }
        require(identities.distinct().size == identities.size) { "Tracker page contains duplicate entries" }
        val digest = identityDigest(entries)
        require(position.previous == null || position.previous != digest) { "Tracker pagination did not advance" }
        require(!hasNext || entries.isNotEmpty()) { "Tracker returned an empty nonterminal page" }
        val next = if (!hasNext) null else {
            require(position.page < MAX_PAGES) { "Tracker library has too many pages" }
            encodeCursor(service, owner, revision, position.page + 1, digest)
        }
        return TrackerLibraryPage(entries, next)
    }

    private fun pageSize(service: ScrobblerService): Int = when (service) {
        ScrobblerService.ANILIST -> 50
        ScrobblerService.KITSU -> 20
        else -> 100
    }

    private fun cursor(service: ScrobblerService, owner: TrackerOwner, revision: Long, value: String?): Cursor {
        require(owner.provider == service.name && revision >= 0) { "Invalid tracker cursor owner" }
        if (value == null) return Cursor(1, null)
        require(value.length <= 256) { "Tracker library cursor is too large" }
        val fields = value.split(':')
        require(fields.size == 6 && fields[0] == "1" && fields[1] == service.name &&
            fields[2] == hash(owner.accountId) && fields[3] == revision.toString()) {
            "Tracker library cursor belongs to another account or session"
        }
        val page = fields[4].toIntOrNull()
        require(page != null && page in 2..MAX_PAGES && page.toString() == fields[4] && digestPattern.matches(fields[5])) {
            "Invalid tracker library cursor"
        }
        return Cursor(page, fields[5])
    }

    private fun encodeCursor(service: ScrobblerService, owner: TrackerOwner, revision: Long, page: Int, previous: String) =
        "1:${service.name}:${hash(owner.accountId)}:$revision:$page:$previous"

    private fun numericOwner(owner: TrackerOwner): String = owner.accountId.also {
        require(it.matches(Regex("[1-9][0-9]{0,18}")) && it.toLongOrNull() != null) { "Invalid numeric tracker account" }
    }

    private fun identity(row: JsonObject, field: String): String = requireNotNull(row.raw(field)).also {
        require(it.isNotBlank() && it.length <= 512) { "Invalid tracker identity" }
    }

    private fun title(id: String, name: String?, aliases: Set<String>, cover: String?, description: String?,
        url: String, format: String?): TrackerTitle {
        require(aliases.size <= 512 && aliases.all { it.length <= 1_000 }) { "Tracker returned too many aliases" }
        val readable = name?.takeIf(String::isNotBlank) ?: "#$id"
        require(readable.length <= 1_000) { "Tracker title is too large" }
        return TrackerTitle(id, readable, aliases.filter(String::isNotBlank).toSet(), publicUrl(cover),
            description, url, format)
    }

    private fun publicUrl(value: String?): String? = value?.takeIf { it.length <= 16_384 }?.toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()

    private fun absoluteShikimori(path: String?): String? = path?.let {
        if (it.startsWith('/')) "https://shikimori.io$it" else it
    }

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: error("Invalid tracker object")
    private fun JsonObject.textArray(key: String): Set<String> = (this[key] as? JsonArray)?.also {
        require(it.size <= 512) { "Tracker returned too many aliases" }
    }?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
    private fun String?.strictBoolean(): Boolean = when (this) {
        "true" -> true
        "false" -> false
        else -> error("Invalid tracker pagination flag")
    }

    internal fun identityDigest(entries: List<TrackerRemoteEntry>): String = hash(entries.joinToString("\n") {
        "${it.reference.mediaId.length}:${it.reference.mediaId}:${it.reference.listEntryId?.length}:${it.reference.listEntryId}"
    })

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** A completed explicit import snapshot, never a cache refresh or outgoing-progress trigger. */
internal suspend fun collectRawTrackerLibraryPages(
    owner: TrackerOwner,
    fetch: suspend (String?) -> TrackerLibraryPage,
): List<TrackerRemoteEntry> {
    val result = linkedMapOf<Pair<String, String?>, TrackerRemoteEntry>()
    val cursors = hashSetOf<String>()
    val pages = hashSetOf<String>()
    var cursor: String? = null
    repeat(TrackerRawLibrary.MAX_PAGES) {
        currentCoroutineContext().ensureActive()
        val page = fetch(cursor)
        currentCoroutineContext().ensureActive()
        require(page.entries.size <= 100) { "Tracker page is too large" }
        require(page.entries.all { it.reference.owner == owner }) { "Tracker library account changed" }
        require(page.entries.isNotEmpty() || page.nextCursor == null) { "Tracker returned an empty nonterminal page" }
        if (page.entries.isNotEmpty()) {
            require(pages.add(TrackerRawLibrary.identityDigest(page.entries))) { "Tracker repeated a library page" }
        }
        page.entries.forEach { entry ->
            val identity = entry.reference.mediaId to entry.reference.listEntryId
            val old = result[identity]
            require(old == null || old == entry) { "Tracker library changed during pagination; refresh to review it" }
            result.putIfAbsent(identity, entry)
        }
        require(result.size <= TrackerRawLibrary.MAX_ENTRIES) { "Tracker library is too large to review" }
        val next = page.nextCursor ?: return result.values.toList()
        require(next.length <= 256 && cursors.add(next)) { "Tracker library cursor did not advance" }
        cursor = next
    }
    error("Tracker library has too many pages")
}
