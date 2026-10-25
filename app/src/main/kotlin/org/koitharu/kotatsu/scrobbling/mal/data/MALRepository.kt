package org.koitharu.kotatsu.scrobbling.mal.data

import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerJson
import org.koitharu.kotatsu.scrobbling.common.data.accountSession
import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerElement
import org.koitharu.kotatsu.scrobbling.common.data.raw
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.array
import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerTimestamp
import org.koitharu.kotatsu.scrobbling.common.domain.model.strings
import org.koitharu.kotatsu.scrobbling.common.data.collectTrackerLibraryPages
import org.koitharu.kotatsu.scrobbling.common.data.malLibraryPage
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
import java.security.SecureRandom
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

internal const val REDIRECT_URI = "kotatsu://mal-auth"
private const val BASE_WEB_URL = "https://myanimelist.net"
private const val BASE_API_URL = "https://api.myanimelist.net/v2"

@Singleton
class MALRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ScrobblerType(ScrobblerService.MAL) private val okHttp: OkHttpClient,
    @ScrobblerType(ScrobblerService.MAL) override val storage: ScrobblerStorage,
    private val db: MangaDatabase,
) : ScrobblerRepository {

    private val clientId = context.getString(R.string.mal_clientId)
    private val codeVerifier: String by lazy(::generateCodeVerifier)

    override val oauthUrl: String
        get() = "$BASE_WEB_URL/v1/oauth2/authorize?" +
            "response_type=code" +
            "&client_id=$clientId" +
            "&redirect_uri=$REDIRECT_URI" +
            "&code_challenge=$codeVerifier" +
            "&code_challenge_method=plain"

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
            body.add("client_id", clientId)
            body.add("grant_type", "authorization_code")
            body.add("code", code)
            body.add("redirect_uri", REDIRECT_URI)
            body.add("code_verifier", codeVerifier)
        }
        val request = Request.Builder()
            .post(body.build())
            .url("${BASE_WEB_URL}/v1/oauth2/token")

        val response = okHttp.trackerCall(request.build(), storage).parseJson()
        storage.saveTokens(authVersion, response.getString("access_token"), response.getStringOrNull("refresh_token"))
    }

    override suspend fun loadUser(): ScrobblerUser {
        val session = storage.requestSession()
        val request = Request.Builder()
            .get()
            .url("${BASE_API_URL}/users/@me")
        val response = okHttp.trackerCall(request.build(), storage).parseJson()
        return MALUser(response).also { storage.saveUser(session, it) }
    }

    override suspend fun unregister(mangaId: Long) {
        return db.getScrobblingDao().delete(ScrobblerService.MAL.id, mangaId, storage.requestSession().accountId)
    }

    override suspend fun readRawEntry(mediaId: Long): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry? {
        require(mediaId > 0)
        val session = storage.requestSession()
        val url = "$BASE_API_URL/manga/$mediaId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "my_list_status{status,score,num_chapters_read,num_volumes_read,comments,updated_at,is_rereading},synopsis")
            .build()
        val json = okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).rawTrackerJson()
        require(json.raw("id") == mediaId.toString()) { "Tracker returned another title" }
        val row = json["my_list_status"] as? kotlinx.serialization.json.JsonObject ?: return null
        val title = org.koitharu.kotatsu.scrobbling.v2.TrackerTitle(mediaId.toString(), requireNotNull(json.raw("title")),
            coverUrl = (json["main_picture"] as? kotlinx.serialization.json.JsonObject)?.raw("large"),
            description = json.raw("synopsis"), url = "https://myanimelist.net/manga/$mediaId")
        storage.checkSession(session)
        return org.koitharu.kotatsu.scrobbling.common.data.TrackerRawRead.entry(ScrobblerService.MAL,
            requireNotNull(session.accountId).toString(), mediaId.toString(), title, row)
    }

    override val supportsRawWrites: Boolean get() = true

    override suspend fun mutateRawEntry(
        expected: org.koitharu.kotatsu.scrobbling.v2.TrackerReference,
        patch: org.koitharu.kotatsu.scrobbling.v2.TrackerPatch,
        allowCreate: Boolean,
    ): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry =
        org.koitharu.kotatsu.scrobbling.common.data.TrackerRawMutation.execute(
            this, ScrobblerService.MAL, okHttp, expected, patch, allowCreate,
        )

    override suspend fun readRawLibraryPage(cursor: String?): org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage {
        val session = accountSession()
        return storage.withSession(session) {
            val owner = readRawOwner()
            org.koitharu.kotatsu.scrobbling.common.data.TrackerRawLibrary.read(
                ScrobblerService.MAL, owner, session.revision, cursor,
            ) { request -> okHttp.trackerCall(request, storage).rawTrackerElement() }
        }
    }

    override suspend fun fetchLibrary(): List<TrackerLibraryEntry> = collectTrackerLibraryPages { _, offset ->
        val url = "$BASE_API_URL/users/@me/mangalist".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "100").addQueryParameter("offset", offset.toString()).addQueryParameter("nsfw", "true")
            .addQueryParameter("fields", "list_status{status,score,num_chapters_read,comments,updated_at,is_rereading},num_chapters,alternative_titles,media_type")
            .build()
        malLibraryPage(okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).parseJson())
    }

    override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
        val url = BASE_API_URL.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("nsfw", "true")
            // WARNING! MAL API throws a 400 when the query is over 64 characters
            .addQueryParameter("q", query.take(64))
            .build()
        val request = Request.Builder().url(url).get().build()
        val response = okHttp.trackerCall(request, storage).parseJson()
        check(response.has("data")) { "Invalid response: \"$response\"" }
        val data = response.getJSONArray("data")
        return data.mapJSONNotNull { jsonToManga(it, query) }
    }

    override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
        val url = BASE_API_URL.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(id.toString())
            .addQueryParameter("fields", "synopsis")
            .build()
        val request = Request.Builder().url(url)
        val response = okHttp.trackerCall(request.build(), storage).parseJson()
        return ScrobblerMangaInfo(response)
    }

    override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean): Boolean {
        findExistingRate(scrobblerMangaId)?.let {
            saveRate(it, mangaId, scrobblerMangaId)
            return true
        }
        val body = FormBody.Builder()
            .add("status", "reading")
            .add("score", "0")
        if (!allowCreate) throw org.koitharu.kotatsu.scrobbling.common.data.TrackerEntryCreationRequired()
        val url = BASE_API_URL.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(scrobblerMangaId.toString())
            .addPathSegment("my_list_status")
            .addQueryParameter("fields", "synopsis")
            .build()
        val request = Request.Builder()
            .url(url)
            .put(body.build())
            .build()
        val response = okHttp.trackerCall(request, storage).parseJson()
        saveRate(response, mangaId, scrobblerMangaId)
        return false
    }

    override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
        val current = findExistingRate(rateId.toLong()) ?: throw TrackerRemoteEntryMissing()
        if (nextTrackerProgress(chapter, current.optInt("num_chapters_read")) == null) {
            saveRate(current, mangaId, rateId.toLong())
            return
        }
        val body = FormBody.Builder()
            .add("num_chapters_read", chapter.toString())
        val url = BASE_API_URL.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(rateId.toString())
            .addPathSegment("my_list_status")
            .build()
        val request = Request.Builder()
            .url(url)
            .put(body.build())
            .build()
        val response = okHttp.trackerCall(request, storage).parseJson()
        saveRate(response, mangaId, rateId.toLong())
    }

    override suspend fun updateStatus(rateId: Int, mangaId: Long, status: String) {
        val fields = org.koitharu.kotatsu.scrobbling.v2.LegacyStatusPatch.fields(ScrobblerService.MAL, status)
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value.toString()) } }.build()
        val url = BASE_API_URL.toHttpUrl().newBuilder().addPathSegment("manga")
            .addPathSegment(rateId.toString()).addPathSegment("my_list_status").build()
        val response = okHttp.trackerCall(Request.Builder().url(url).put(body).build(), storage).parseJson()
        saveRate(response, mangaId, rateId.toLong())
    }

    override suspend fun updateRate(
        rateId: Int,
        mangaId: Long,
        rating: Float,
        status: String?,
        comment: String?,
        setStartDate: Boolean,
    ) {
        val body = FormBody.Builder()
            .add("status", if (status == "rereading") "reading" else status.toString())
            .add("is_rereading", (status == "rereading").toString())
            .add("score", rating.toInt().toString())
            .add("comments", comment.orEmpty())
        if (setStartDate) {
            body.add("start_date", LocalDate.now().toString())
        }
        val url = BASE_API_URL.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(rateId.toString())
            .addPathSegment("my_list_status")
            .build()
        val request = Request.Builder()
            .url(url)
            .put(body.build())
            .build()
        val response = okHttp.trackerCall(request, storage).parseJson()
        saveRate(response, mangaId, rateId.toLong())
    }

    private suspend fun findExistingRate(scrobblerMangaId: Long): JSONObject? {
        val url = BASE_API_URL.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(scrobblerMangaId.toString())
            .addQueryParameter("fields", "my_list_status")
            .build()
        val response = okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).parseJson()
        check(response.getLong("id") == scrobblerMangaId) { "Tracker returned a different title" }
        return response.optJSONObject("my_list_status")
    }

    private suspend fun saveRate(json: JSONObject, mangaId: Long, scrobblerMangaId: Long) {
        val session = storage.requestSession()
        requireNotNull(session.accountId) { "Tracker account unavailable" }
        val entity = ScrobblingEntity(
            accountId = session.accountId,
            scrobbler = ScrobblerService.MAL.id,
            id = scrobblerMangaId.toInt(),
            mangaId = mangaId,
            targetId = scrobblerMangaId,
            status = if (json.optBoolean("is_rereading")) "rereading" else json.getString("status"),
            chapter = json.getInt("num_chapters_read"),
            comment = json.getStringOrNull("comments"),
            rating = (json.getDouble("score").toFloat() / 10f).coerceIn(0f, 1f),
        )
        db.getScrobblingDao().saveLinked(entity)
    }

    override fun logout() {
        storage.clear()
    }

    private fun jsonToManga(json: JSONObject, sourceTitle: String): ScrobblerManga {
        val node = json.getJSONObject("node")
        val title = node.getString("title")
        return ScrobblerManga(
            id = node.getLong("id"),
            name = title,
            altName = null,
            cover = node.optJSONObject("main_picture")?.getStringOrNull("large"),
            url = "$BASE_WEB_URL/manga/${node.getLong("id")}",
            isBestMatch = title.equals(sourceTitle, ignoreCase = true),
        )
    }

    private fun ScrobblerMangaInfo(json: JSONObject) = ScrobblerMangaInfo(
        id = json.getLong("id"),
        name = json.getString("title"),
        cover = json.getJSONObject("main_picture").getString("large"),
        url = "$BASE_WEB_URL/manga/${json.getLong("id")}",
        descriptionHtml = json.getString("synopsis"),
    )

    @Suppress("FunctionName")
    private fun MALUser(json: JSONObject) = ScrobblerUser(
        id = json.getLong("id"),
        nickname = json.getString("name"),
        avatar = json.getStringOrNull("picture"),
        service = ScrobblerService.MAL,
    )

    private fun generateCodeVerifier(): String {
        val codeVerifier = ByteArray(50)
        SecureRandom().nextBytes(codeVerifier)
        return Base64.encodeToString(codeVerifier, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }
}
