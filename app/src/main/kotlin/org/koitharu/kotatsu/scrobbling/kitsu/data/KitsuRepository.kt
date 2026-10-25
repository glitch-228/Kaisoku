package org.koitharu.kotatsu.scrobbling.kitsu.data

import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerJson
import org.koitharu.kotatsu.scrobbling.common.data.accountSession
import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerElement
import org.koitharu.kotatsu.scrobbling.common.data.raw
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.array
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerTimestamp
import org.koitharu.kotatsu.scrobbling.common.domain.model.strings
import org.koitharu.kotatsu.scrobbling.common.data.collectTrackerLibraryPages
import org.koitharu.kotatsu.scrobbling.common.data.kitsuLibraryPage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.saveLinked
import org.koitharu.kotatsu.scrobbling.common.data.TrackerRemoteEntryMissing
import org.koitharu.kotatsu.scrobbling.common.data.nextTrackerProgress
import org.koitharu.kotatsu.scrobbling.common.data.requestSession
import org.koitharu.kotatsu.scrobbling.common.data.trackerCall
import org.koitharu.kotatsu.scrobbling.common.data.withSession
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.kitsu.data.KitsuInterceptor.Companion.VND_JSON
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val BASE_WEB_URL = "https://kitsu.app"
private val KITSU_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)

class KitsuRepository(
    @ApplicationContext context: Context,
    private val okHttp: OkHttpClient,
    override val storage: ScrobblerStorage,
    private val db: MangaDatabase,
) : ScrobblerRepository {

    // not in use yet
    private val clientId = context.getString(R.string.kitsu_clientId)
    private val clientSecret = context.getString(R.string.kitsu_clientSecret)

    override val oauthUrl: String = "kaisoku+kitsu://auth"

    override val isAuthorized: Boolean
        get() = storage.accessToken != null

    override val cachedUser: ScrobblerUser?
        get() {
            return storage.user
        }

    override suspend fun authorize(code: String?) {
        if (code != null) storage.beginAuthorization()
        val authVersion = storage.sessionVersion
        val body = FormBody.Builder()
        if (code != null) {
            body.add("grant_type", "password")
            body.add("username", code.substringBefore(';'))
            body.add("password", code.substringAfter(';'))
        } else {
            body.add("grant_type", "refresh_token")
            body.add("refresh_token", checkNotNull(storage.refreshToken))
        }
        val request = Request.Builder()
            .post(body.build())
            .url("$BASE_WEB_URL/api/oauth/token")
        val response = okHttp.trackerCall(request.build(), storage).parseJson()
        storage.saveTokens(authVersion, response.getString("access_token"), response.getStringOrNull("refresh_token"))
    }

    override suspend fun loadUser(): ScrobblerUser {
        val session = storage.requestSession()
        val request = Request.Builder()
            .get()
            .url("$BASE_WEB_URL/api/edge/users?filter[self]=true")
        val response = okHttp.trackerCall(request.build(), storage).parseJson()
            .getJSONArray("data")
            .getJSONObject(0)
        return ScrobblerUser(
            id = response.getAsLong("id"),
            nickname = response.getJSONObject("attributes").getString("name"),
            avatar = response.getJSONObject("attributes").optJSONObject("avatar")?.getStringOrNull("small"),
            service = ScrobblerService.KITSU,
        ).also { storage.saveUser(session, it) }
    }

    override fun logout() {
        storage.clear()
    }

    override suspend fun unregister(mangaId: Long) {
        return db.getScrobblingDao().delete(ScrobblerService.KITSU.id, mangaId, storage.requestSession().accountId)
    }

    override suspend fun readRawEntry(mediaId: Long): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry? {
        require(mediaId > 0)
        val session = storage.requestSession()
        val owner = requireNotNull(session.accountId)
        val url = "$BASE_WEB_URL/api/edge/library-entries?filter[manga_id]=$mediaId&filter[user_id]=$owner&include=manga"
        val json = okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).rawTrackerJson()
        val rows = json.array("data")
        check(rows.size <= 1) { "Several Kitsu entries match; review the account" }
        val row = rows.singleOrNull() ?: return null
        val actual = row.obj("relationships").obj("manga").obj("data").raw("id")
        require(actual == mediaId.toString()) { "Tracker returned another title" }
        val metadata = getMangaInfo(mediaId)
        require(metadata.id == mediaId) { "Tracker returned another title" }
        storage.checkSession(session)
        val title = org.koitharu.kotatsu.scrobbling.v2.TrackerTitle(mediaId.toString(), metadata.name,
            coverUrl = metadata.cover, description = metadata.descriptionHtml, url = metadata.url)
        return org.koitharu.kotatsu.scrobbling.common.data.TrackerRawRead.entry(ScrobblerService.KITSU,
            owner.toString(), mediaId.toString(), title, row)
    }

    override val supportsRawWrites: Boolean get() = true

    override suspend fun mutateRawEntry(
        expected: org.koitharu.kotatsu.scrobbling.v2.TrackerReference,
        patch: org.koitharu.kotatsu.scrobbling.v2.TrackerPatch,
        allowCreate: Boolean,
    ): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry =
        org.koitharu.kotatsu.scrobbling.common.data.TrackerRawMutation.execute(
            this, ScrobblerService.KITSU, okHttp, expected, patch, allowCreate,
        )

    override suspend fun readRawLibraryPage(cursor: String?): org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage {
        val session = accountSession()
        return storage.withSession(session) {
            val owner = readRawOwner()
            org.koitharu.kotatsu.scrobbling.common.data.TrackerRawLibrary.read(
                ScrobblerService.KITSU, owner, session.revision, cursor,
            ) { request -> okHttp.trackerCall(request, storage).rawTrackerElement() }
        }
    }

    override suspend fun fetchLibrary(): List<TrackerLibraryEntry> {
        val owner = requireNotNull(storage.requestSession().accountId)
        return collectTrackerLibraryPages { _, offset ->
            val url = "$BASE_WEB_URL/api/edge/library-entries?filter[user_id]=$owner&filter[media_type]=Manga&include=manga&page[limit]=20&page[offset]=$offset"
            kitsuLibraryPage(okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).parseJson().ensureSuccess())
        }
    }

    override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
        val request = Request.Builder()
            .get()
            .url("$BASE_WEB_URL/api/edge/manga?page[limit]=20&page[offset]=$offset&filter[text]=${query.urlEncoded()}")
        val response = okHttp.trackerCall(request.build(), storage).parseJson().ensureSuccess()
        return response.getJSONArray("data").mapJSON { jo ->
            val attrs = jo.getJSONObject("attributes")
            val titles = attrs.getJSONObject("titles").valuesToStringList()
            ScrobblerManga(
                id = jo.getAsLong("id"),
                name = titles.first(),
                altName = titles.drop(1).joinToString(),
                cover = attrs.getJSONObject("posterImage").getStringOrNull("small").orEmpty(),
                url = "$BASE_WEB_URL/manga/${attrs.getString("slug")}",
                isBestMatch = titles.any {
                    it.equals(query, ignoreCase = true)
                }
            )
        }
    }

    override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
        val request = Request.Builder()
            .get()
            .url("$BASE_WEB_URL/api/edge/manga/$id")
        val data = okHttp.trackerCall(request.build(), storage).parseJson().ensureSuccess().getJSONObject("data")
        val attrs = data.getJSONObject("attributes")
        return ScrobblerMangaInfo(
            id = data.getAsLong("id"),
            name = attrs.getString("canonicalTitle"),
            cover = attrs.getJSONObject("posterImage").getString("medium"),
            url = "$BASE_WEB_URL/manga/${attrs.getString("slug")}",
            descriptionHtml = attrs.getString("description").replace("\\n", "<br>"),
        )
    }

    override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean): Boolean {
        findExistingRate(scrobblerMangaId)?.let {
            saveRate(it, mangaId)
            return true
        }
        val user = cachedUser ?: loadUser()
        if (!allowCreate) throw org.koitharu.kotatsu.scrobbling.common.data.TrackerEntryCreationRequired()
        val payload = JSONObject()
        payload.putJO("data") {
            put("type", "libraryEntries")
            putJO("attributes") {
                put("status", "planned") // will be updated by next call
                put("progress", 0)
            }
            putJO("relationships") {
                putJO("manga") {
                    putJO("data") {
                        put("type", "manga")
                        put("id", scrobblerMangaId)
                    }
                }
                putJO("user") {
                    putJO("data") {
                        put("type", "users")
                        put("id", user.id)
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("$BASE_WEB_URL/api/edge/library-entries?include=manga")
            .post(payload.toKitsuRequestBody())
        val response = okHttp.trackerCall(request.build(), storage).parseJson().ensureSuccess().getJSONObject("data")
        saveRate(response, mangaId)
        return false
    }

    override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
        val current = okHttp.trackerCall(Request.Builder()
            .url("$BASE_WEB_URL/api/edge/library-entries/$rateId?include=manga").get().build(), storage)
            .parseJson().ensureSuccess().getJSONObject("data")
        val linked = db.getScrobblingDao().find(ScrobblerService.KITSU.id, mangaId, storage.requestSession().accountId)
        check(current.getJSONObject("relationships").getJSONObject("manga").getJSONObject("data").getLong("id") == linked?.targetId) {
            "Tracker link changed"
        }
        if (nextTrackerProgress(chapter, current.getJSONObject("attributes").optInt("progress")) == null) {
            saveRate(current, mangaId)
            return
        }
        val payload = JSONObject()
        payload.putJO("data") {
            put("type", "libraryEntries")
            put("id", rateId)
            putJO("attributes") {
                put("progress", chapter)
            }
        }
        val request = Request.Builder()
            .url("$BASE_WEB_URL/api/edge/library-entries/$rateId?include=manga")
            .patch(payload.toKitsuRequestBody())
        val response = okHttp.trackerCall(request.build(), storage).parseJson().ensureSuccess().getJSONObject("data")
        saveRate(response, mangaId)
    }

    override suspend fun updateStatus(rateId: Int, mangaId: Long, status: String) {
        val payload = JSONObject().put("data", JSONObject().put("type", "libraryEntries").put("id", rateId)
            .put("attributes", org.koitharu.kotatsu.scrobbling.v2.LegacyStatusPatch.json(ScrobblerService.KITSU, status)))
        val response = okHttp.trackerCall(Request.Builder().url("$BASE_WEB_URL/api/edge/library-entries/$rateId?include=manga")
            .patch(payload.toKitsuRequestBody()).build(), storage).parseJson().ensureSuccess().getJSONObject("data")
        saveRate(response, mangaId)
    }

    override suspend fun updateRate(
        rateId: Int,
        mangaId: Long,
        rating: Float,
        status: String?,
        comment: String?,
        setStartDate: Boolean,
    ) {
        val payload = JSONObject()
        payload.putJO("data") {
            put("type", "libraryEntries")
            put("id", rateId)
            putJO("attributes") {
                put("status", if (status == "reconsuming") "current" else status)
                if (status != null) put("reconsuming", status == "reconsuming")
                put("ratingTwenty", (rating * 20).toInt().coerceIn(2, 20))
                put("notes", comment)
                if (setStartDate) {
                    put("startedAt", KITSU_DATE_FORMAT.format(Instant.now()))
                }
            }
        }
        val request = Request.Builder()
            .url("$BASE_WEB_URL/api/edge/library-entries/$rateId?include=manga")
            .patch(payload.toKitsuRequestBody())
        val response = okHttp.trackerCall(request.build(), storage).parseJson().ensureSuccess().getJSONObject("data")
        saveRate(response, mangaId)
    }

    private fun JSONObject.valuesToStringList(): List<String> {
        val result = ArrayList<String>(length())
        for (key in keys()) {
            result.add(getStringOrNull(key) ?: continue)
        }
        return result
    }

    private inline fun JSONObject.putJO(name: String, init: JSONObject.() -> Unit) {
        put(name, JSONObject().apply(init))
    }

    private fun JSONObject.toKitsuRequestBody() = toString().toRequestBody(VND_JSON.toMediaType())

    private suspend fun findExistingRate(scrobblerMangaId: Long): JSONObject? {
        val userId = (cachedUser ?: loadUser()).id
        val request = Request.Builder()
            .get()
            .url("$BASE_WEB_URL/api/edge/library-entries?filter[manga_id]=$scrobblerMangaId&filter[user_id]=$userId&include=manga")
        val data = okHttp.trackerCall(request.build(), storage).parseJson().ensureSuccess().getJSONArray("data")
        return data.optJSONObject(0)
    }

    private suspend fun saveRate(json: JSONObject, mangaId: Long) {
        val attrs = json.getJSONObject("attributes")
        val manga = json.getJSONObject("relationships").getJSONObject("manga").getJSONObject("data")
        val session = storage.requestSession()
        requireNotNull(session.accountId) { "Tracker account unavailable" }
        val entity = ScrobblingEntity(
            accountId = session.accountId,
            scrobbler = ScrobblerService.KITSU.id,
            id = json.getInt("id"),
            mangaId = mangaId,
            targetId = manga.getAsLong("id"),
            status = if (attrs.optBoolean("reconsuming")) "reconsuming" else attrs.getString("status"),
            chapter = attrs.getIntOrDefault("progress", 0),
            comment = attrs.getStringOrNull("notes"),
            rating = (attrs.getFloatOrDefault("ratingTwenty", 0f) / 20f).coerceIn(0f, 1f),
        )
        db.getScrobblingDao().saveLinked(entity)
    }

    private fun JSONObject.ensureSuccess(): JSONObject {
        val error = optJSONArray("errors")?.optJSONObject(0) ?: return this
        val title = error.getString("title")
        val detail = error.getStringOrNull("detail")
        throw IOException("$title: $detail")
    }

    private fun JSONObject.getAsLong(name: String): Long = when (val rawValue = opt(name)) {
        is Long -> rawValue
        is Number -> rawValue.toLong()
        is String -> rawValue.toLong()
        else -> throw IllegalArgumentException("Value $rawValue at \"$name\" is not of type long")
    }
}
