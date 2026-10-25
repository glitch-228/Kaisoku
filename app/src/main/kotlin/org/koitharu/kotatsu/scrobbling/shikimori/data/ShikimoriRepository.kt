package org.koitharu.kotatsu.scrobbling.shikimori.data

import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerJson
import org.koitharu.kotatsu.scrobbling.common.data.accountSession
import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerElement
import org.koitharu.kotatsu.scrobbling.common.data.raw
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.array
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.ext.toRequestBody
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerTimestamp
import org.koitharu.kotatsu.scrobbling.common.domain.model.strings
import org.koitharu.kotatsu.scrobbling.common.data.collectTrackerLibraryPages
import org.koitharu.kotatsu.scrobbling.common.data.shikimoriLibraryPage
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
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import javax.inject.Inject
import javax.inject.Singleton

private const val DOMAIN = "shikimori.io"
internal const val REDIRECT_URI = "kotatsu://shikimori-auth"
private const val BASE_URL = "https://$DOMAIN/"
private const val MANGA_PAGE_SIZE = 10

@Singleton
class ShikimoriRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ScrobblerType(ScrobblerService.SHIKIMORI) private val okHttp: OkHttpClient,
    @ScrobblerType(ScrobblerService.SHIKIMORI) override val storage: ScrobblerStorage,
    private val db: MangaDatabase,
) : ScrobblerRepository {

    private val clientId = context.getString(R.string.shikimori_clientId)
    private val clientSecret = context.getString(R.string.shikimori_clientSecret)

    override val oauthUrl: String
        get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
            "redirect_uri=$REDIRECT_URI&response_type=code&scope="

    override val isAuthorized: Boolean
        get() = storage.accessToken != null

    override suspend fun authorize(code: String?) {
        if (code != null) storage.beginAuthorization()
        val authVersion = storage.sessionVersion
        val body = FormBody.Builder()
        body.add("client_id", clientId)
        body.add("client_secret", clientSecret)
        if (code != null) {
            body.add("grant_type", "authorization_code")
            body.add("redirect_uri", REDIRECT_URI)
            body.add("code", code)
        } else {
            body.add("grant_type", "refresh_token")
            body.add("refresh_token", checkNotNull(storage.refreshToken))
        }
        val request = Request.Builder()
            .post(body.build())
            .url("${BASE_URL}oauth/token")
        val response = okHttp.trackerCall(request.build(), storage).parseJson()
        storage.saveTokens(authVersion, response.getString("access_token"), response.getStringOrNull("refresh_token"))
    }

    override suspend fun loadUser(): ScrobblerUser {
        val session = storage.requestSession()
        val request = Request.Builder()
            .get()
            .url("${BASE_URL}api/users/whoami")
        val response = okHttp.trackerCall(request.build(), storage).parseJson()
        return ShikimoriUser(response).also { storage.saveUser(session, it) }
    }

    override val cachedUser: ScrobblerUser?
        get() {
            return storage.user
        }

    override suspend fun unregister(mangaId: Long) {
        return db.getScrobblingDao().delete(ScrobblerService.SHIKIMORI.id, mangaId, storage.requestSession().accountId)
    }

    override fun logout() {
        storage.clear()
    }

    override suspend fun readRawEntry(mediaId: Long): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry? {
        require(mediaId > 0)
        val session = storage.requestSession()
        val owner = requireNotNull(session.accountId)
        val url = "${BASE_URL}api/v2/user_rates".toHttpUrl().newBuilder()
            .addQueryParameter("user_id", owner.toString()).addQueryParameter("target_id", mediaId.toString())
            .addQueryParameter("target_type", "Manga").build()
        val rows = okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).rawTrackerElement()
            as? kotlinx.serialization.json.JsonArray ?: error("Invalid Shikimori list response")
        check(rows.size <= 1) { "Several Shikimori entries match; review the account" }
        val row = rows.singleOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
        require(row.raw("target_id") == mediaId.toString() && row.raw("user_id") == owner.toString() &&
            row.raw("target_type") == "Manga") { "Tracker returned another entry or account" }
        val metadata = getMangaInfo(mediaId)
        require(metadata.id == mediaId) { "Tracker returned another title" }
        storage.checkSession(session)
        val title = org.koitharu.kotatsu.scrobbling.v2.TrackerTitle(mediaId.toString(), metadata.name,
            coverUrl = metadata.cover, description = metadata.descriptionHtml, url = metadata.url)
        return org.koitharu.kotatsu.scrobbling.common.data.TrackerRawRead.entry(ScrobblerService.SHIKIMORI,
            owner.toString(), mediaId.toString(), title, row)
    }

    override val supportsRawWrites: Boolean get() = true

    override suspend fun mutateRawEntry(
        expected: org.koitharu.kotatsu.scrobbling.v2.TrackerReference,
        patch: org.koitharu.kotatsu.scrobbling.v2.TrackerPatch,
        allowCreate: Boolean,
    ): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry =
        org.koitharu.kotatsu.scrobbling.common.data.TrackerRawMutation.execute(
            this, ScrobblerService.SHIKIMORI, okHttp, expected, patch, allowCreate,
        )

    override suspend fun readRawLibraryPage(cursor: String?): org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage {
        val session = accountSession()
        return storage.withSession(session) {
            val owner = readRawOwner()
            org.koitharu.kotatsu.scrobbling.common.data.TrackerRawLibrary.read(
                ScrobblerService.SHIKIMORI, owner, session.revision, cursor,
            ) { request -> okHttp.trackerCall(request, storage).rawTrackerElement() }
        }
    }

    override suspend fun fetchLibrary(): List<TrackerLibraryEntry> {
        val owner = requireNotNull(storage.requestSession().accountId)
        return collectTrackerLibraryPages { page, _ ->
            val url = "${BASE_URL}api/users/$owner/manga_rates".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString()).addQueryParameter("limit", "100")
                .addQueryParameter("censored", "false").build()
            shikimoriLibraryPage(okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).parseJsonArray())
        }
    }

    override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
        val page = offset / MANGA_PAGE_SIZE
        val pageOffset = offset % MANGA_PAGE_SIZE
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("mangas")
            .addEncodedQueryParameter("page", (page + 1).toString())
            .addEncodedQueryParameter("limit", MANGA_PAGE_SIZE.toString())
            .addEncodedQueryParameter("censored", false.toString())
            .addQueryParameter("search", query)
            .build()
        val request = Request.Builder().url(url).get().build()
        val response = okHttp.trackerCall(request, storage).parseJsonArray()
        val list = response.mapJSON { ScrobblerManga(it, query) }
        return if (pageOffset != 0) list.drop(pageOffset) else list
    }

    override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean): Boolean {
        val user = cachedUser ?: loadUser()
        findExistingRate(user.id, scrobblerMangaId)?.let {
            saveRate(it, mangaId)
            return true
        }
        if (!allowCreate) throw org.koitharu.kotatsu.scrobbling.common.data.TrackerEntryCreationRequired()
        val payload = JSONObject()
        payload.put(
            "user_rate",
            JSONObject().apply {
                put("target_id", scrobblerMangaId)
                put("target_type", "Manga")
                put("user_id", user.id)
            },
        )
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("user_rates")
            .build()
        val request = Request.Builder().url(url).post(payload.toRequestBody()).build()
        val response = okHttp.trackerCall(request, storage).parseJson()
        saveRate(response, mangaId)
        return false
    }

    private suspend fun findExistingRate(userId: Long, scrobblerMangaId: Long): JSONObject? {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("user_rates")
            .addQueryParameter("user_id", userId.toString())
            .addQueryParameter("target_id", scrobblerMangaId.toString())
            .addQueryParameter("target_type", "Manga")
            .build()
        val response = okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).parseJsonArray()
        return response.optJSONObject(0)
    }

    override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
        val current = okHttp.trackerCall(Request.Builder().url("${BASE_URL}api/v2/user_rates/$rateId").get().build(), storage).parseJson()
        val linked = db.getScrobblingDao().find(ScrobblerService.SHIKIMORI.id, mangaId, storage.requestSession().accountId)
        check(current.getLong("target_id") == linked?.targetId) { "Tracker link changed" }
        if (nextTrackerProgress(chapter, current.optInt("chapters")) == null) {
            saveRate(current, mangaId)
            return
        }
        val payload = JSONObject()
        payload.put(
            "user_rate",
            JSONObject().apply {
                put("chapters", chapter)
            },
        )
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("user_rates")
            .addPathSegment(rateId.toString())
            .build()
        val request = Request.Builder().url(url).patch(payload.toRequestBody()).build()
        val response = okHttp.trackerCall(request, storage).parseJson()
        saveRate(response, mangaId)
    }

    override suspend fun updateStatus(rateId: Int, mangaId: Long, status: String) {
        val payload = JSONObject().put("user_rate",
            org.koitharu.kotatsu.scrobbling.v2.LegacyStatusPatch.json(ScrobblerService.SHIKIMORI, status))
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("v2")
            .addPathSegment("user_rates").addPathSegment(rateId.toString()).build()
        val response = okHttp.trackerCall(Request.Builder().url(url).patch(payload.toRequestBody()).build(), storage).parseJson()
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
        payload.put(
            "user_rate",
            JSONObject().apply {
                put("score", rating.toString())
                if (comment != null) {
                    put("text", comment)
                }
                if (status != null) {
                    put("status", status)
                }
            },
        )
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("user_rates")
            .addPathSegment(rateId.toString())
            .build()
        val request = Request.Builder().url(url).patch(payload.toRequestBody()).build()
        val response = okHttp.trackerCall(request, storage).parseJson()
        saveRate(response, mangaId)
    }

    override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
        val request = Request.Builder()
            .get()
            .url("${BASE_URL}api/mangas/$id")
        val response = okHttp.trackerCall(request.build(), storage).parseJson()
        return ScrobblerMangaInfo(response)
    }

    private suspend fun saveRate(json: JSONObject, mangaId: Long) {
        val session = storage.requestSession()
        requireNotNull(session.accountId) { "Tracker account unavailable" }
        val entity = ScrobblingEntity(
            accountId = session.accountId,
            scrobbler = ScrobblerService.SHIKIMORI.id,
            id = json.getInt("id"),
            mangaId = mangaId,
            targetId = json.getLong("target_id"),
            status = json.getString("status"),
            chapter = json.getInt("chapters"),
            comment = json.getString("text"),
            rating = (json.getDouble("score").toFloat() / 10f).coerceIn(0f, 1f),
        )
        db.getScrobblingDao().saveLinked(entity)
    }

    private fun ScrobblerManga(json: JSONObject, sourceTitle: String) = ScrobblerManga(
        id = json.getLong("id"),
        name = json.getString("name"),
        altName = json.getStringOrNull("russian"),
        cover = json.getJSONObject("image").getString("preview").toAbsoluteUrl(DOMAIN),
        url = json.getString("url").toAbsoluteUrl(DOMAIN),
        isBestMatch = sourceTitle.equals(json.getString("name"), ignoreCase = true)
            || json.getStringOrNull("russian")?.equals(sourceTitle, ignoreCase = true) == true
    )

    private fun ScrobblerMangaInfo(json: JSONObject) = ScrobblerMangaInfo(
        id = json.getLong("id"),
        name = json.getString("name"),
        cover = json.getJSONObject("image").getString("preview").toAbsoluteUrl(DOMAIN),
        url = json.getString("url").toAbsoluteUrl(DOMAIN),
        descriptionHtml = json.getString("description_html"),
    )

    @Suppress("FunctionName")
    private fun ShikimoriUser(json: JSONObject) = ScrobblerUser(
        id = json.getLong("id"),
        nickname = json.getString("nickname"),
        avatar = json.getStringOrNull("avatar"),
        service = ScrobblerService.SHIKIMORI,
    )
}
