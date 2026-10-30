package org.koitharu.kotatsu.scrobbling.mangabaka.data

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import org.koitharu.kotatsu.scrobbling.common.data.mangaBakaLibraryPage
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val REDIRECT_URI = "kotatsu://mangabaka-auth"
private const val BASE_WEB_URL = "https://mangabaka.org"
private const val BASE_API_URL = "https://api.mangabaka.org/v1"
private const val OAUTH_URL = "$BASE_WEB_URL/auth/oauth2"
private const val SCOPES = "library.read library.write offline_access openid"
private const val MANGA_PAGE_SIZE = 20
private const val RATING_MAX = 100

@Singleton
class MangaBakaRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ScrobblerType(ScrobblerService.MANGABAKA) private val okHttp: OkHttpClient,
    @ScrobblerType(ScrobblerService.MANGABAKA) override val storage: ScrobblerStorage,
    private val db: MangaDatabase,
) : ScrobblerRepository {

    private val clientId = context.getString(R.string.mangabaka_clientId)
    private var codeVerifier: String? = null
    @Volatile private var rawOwnerSnapshot: Pair<Long, org.koitharu.kotatsu.scrobbling.v2.TrackerOwner>? = null

    override val oauthUrl: String
        get() {
            val verifier = generateCodeVerifier().also { codeVerifier = it }
            return "$OAUTH_URL/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("scope", SCOPES)
                .addQueryParameter("code_challenge", verifier.toS256Challenge())
                .addQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()
        }

    override val isAuthorized: Boolean
        get() = storage.accessToken != null

    override val cachedUser: ScrobblerUser?
        get() = storage.user

    override suspend fun authorize(code: String?) {
        if (code != null) storage.beginAuthorization()
        val authVersion = storage.sessionVersion
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("redirect_uri", REDIRECT_URI)
        if (code != null) {
            body.add("grant_type", "authorization_code")
            body.add("code", code)
            body.add("code_verifier", checkNotNull(codeVerifier) { "Authorization session expired" })
            body.add("code_challenge_method", "S256")
            body.add("scope", SCOPES)
        } else {
            body.add("grant_type", "refresh_token")
            body.add("refresh_token", checkNotNull(storage.refreshToken) { "Not authorized" })
        }
        val response = okHttp.trackerCall(
            Request.Builder().post(body.build()).url("$OAUTH_URL/token").build(), storage).parseJson()
        storage.saveTokens(authVersion, response.getString("access_token"), response.getStringOrNull("refresh_token"))
        if (code != null) {
            codeVerifier = null
        }
    }

    override suspend fun loadUser(): ScrobblerUser {
        val session = storage.requestSession()
        val response = okHttp.trackerCall(Request.Builder().get().url("$BASE_API_URL/my/profile").build(), storage).parseJson().getJSONObject("data")
        val id = response.getString("id")
        return ScrobblerUser(
            id = id.hashCode().toLong(),
            nickname = response.getStringOrNull("nickname")
                ?: response.getStringOrNull("preferred_username")
                ?: id,
            avatar = response.getStringOrNull("avatar") ?: response.getStringOrNull("picture"),
            service = ScrobblerService.MANGABAKA,
        ).also { storage.saveUser(session, it) }
    }

    override fun logout() = storage.clear()

    override suspend fun unregister(mangaId: Long) {
        db.getScrobblingDao().delete(ScrobblerService.MANGABAKA.id, mangaId, storage.requestSession().accountId)
    }

    override suspend fun readRawOwner(): org.koitharu.kotatsu.scrobbling.v2.TrackerOwner {
        val session = accountSession()
        rawOwnerSnapshot?.takeIf { it.first == session.revision }?.let {
            storage.checkSession(session)
            return it.second
        }
        return storage.withSession(session) {
            val profile = okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/my/profile").get().build(), storage)
                .rawTrackerJson().obj("data")
            val actual = requireNotNull(profile.raw("id")) { "MangaBaka did not return an account identity" }
            require(actual.hashCode().toLong() == session.accountId) { "Tracker returned another account" }
            val owner = org.koitharu.kotatsu.scrobbling.v2.TrackerOwner(ScrobblerService.MANGABAKA.name, actual)
            storage.checkSession(session)
            rawOwnerSnapshot = session.revision to owner
            owner
        }
    }

    override suspend fun readRawEntry(mediaId: Long): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry? {
        require(mediaId > 0)
        val session = accountSession()
        return storage.withSession(session) {
            val owner = readRawOwner()
            val json = try {
                okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/my/library/$mediaId").get().build(), storage).rawTrackerJson()
            } catch (e: org.koitharu.kotatsu.scrobbling.common.data.TrackerHttpException) {
                if (e.code == 404) return@withSession null else throw e
            }
            val row = json.obj("data")
            row.raw("user_id")?.let { require(it == owner.accountId) { "Tracker returned another account" } }
            val actual = row.raw("series_id") ?: (row["series"] as? kotlinx.serialization.json.JsonObject)?.raw("id")
            require(actual == mediaId.toString()) { "Tracker returned another title" }
            val metadata = getMangaInfo(mediaId)
            require(metadata.id == mediaId) { "Tracker returned another title" }
            storage.checkSession(session)
            val title = org.koitharu.kotatsu.scrobbling.v2.TrackerTitle(mediaId.toString(), metadata.name,
                coverUrl = metadata.cover, description = metadata.descriptionHtml, url = metadata.url)
            org.koitharu.kotatsu.scrobbling.common.data.TrackerRawRead.entry(ScrobblerService.MANGABAKA,
                owner.accountId, mediaId.toString(), title, row)
        }
    }

    override val supportsRawWrites: Boolean get() = true

    override suspend fun mutateRawEntry(
        expected: org.koitharu.kotatsu.scrobbling.v2.TrackerReference,
        patch: org.koitharu.kotatsu.scrobbling.v2.TrackerPatch,
        allowCreate: Boolean,
    ): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry =
        org.koitharu.kotatsu.scrobbling.common.data.TrackerRawMutation.execute(
            this, ScrobblerService.MANGABAKA, okHttp, expected, patch, allowCreate,
        )

    override suspend fun readCataloguePage(query: String, cursor: String?):
        org.koitharu.kotatsu.search.unified.CataloguePage {
        val session = accountSession()
        return storage.withSession(session) {
            org.koitharu.kotatsu.search.unified.TrackerCatalogue.read(
                ScrobblerService.MANGABAKA, query, cursor,
            ) { request -> okHttp.trackerCall(request, storage).rawTrackerElement() }
        }
    }

    override suspend fun readRawLibraryPage(cursor: String?): org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage {
        val session = accountSession()
        return storage.withSession(session) {
            val owner = readRawOwner()
            org.koitharu.kotatsu.scrobbling.common.data.TrackerRawLibrary.read(
                ScrobblerService.MANGABAKA, owner, session.revision, cursor,
            ) { request -> okHttp.trackerCall(request, storage).rawTrackerElement() }
        }
    }

    override suspend fun fetchLibrary(): List<TrackerLibraryEntry> = collectTrackerLibraryPages { page, _ ->
        val url = "$BASE_API_URL/my/library?page=$page&limit=100&sort_by=updated_at_desc"
        val response = okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).parseJson()
        val rows = response.getJSONArray("data")
        val missing = (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val media = row.optJSONObject("series") ?: row.optJSONObject("Series") ?: row
            if (media.getStringOrNull("title") != null || media.getStringOrNull("romanized_title") != null) null
            else row.optLong("series_id", media.optLong("id", 0)).takeIf { it > 0 }
        }.distinct()
        val metadata = linkedMapOf<Long, JSONObject>()
        for (ids in missing.chunked(50)) {
            val requestUrl = "$BASE_API_URL/series/batch".toHttpUrl().newBuilder()
            ids.forEach { requestUrl.addQueryParameter("id", it.toString()) }
            val data = okHttp.trackerCall(Request.Builder().url(requestUrl.build()).get().build(), storage)
                .parseJson().getJSONArray("data")
            for (i in 0 until data.length()) data.getJSONObject(i).let { metadata[it.getLong("id")] = it }
        }
        mangaBakaLibraryPage(response, metadata)
    }

    override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
        val url = "$BASE_API_URL/series/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", MANGA_PAGE_SIZE.toString())
            .addQueryParameter("page", (offset / MANGA_PAGE_SIZE + 1).toString())
            .build()
        val response = okHttp.trackerCall(Request.Builder().url(url).get().build(), storage).parseJson()
        check(response.has("data")) { "Invalid response: $response" }
        return response.getJSONArray("data").mapJSONNotNull { jsonToManga(it, query) }
    }

    override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
        val json = okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/series/$id").get().build(), storage).parseJson().getJSONObject("data")
        return ScrobblerMangaInfo(
            id = json.getLong("id"),
            name = json.title(),
            cover = json.coverUrl().orEmpty(),
            url = "$BASE_WEB_URL/${json.getLong("id")}",
            descriptionHtml = json.getStringOrNull("description").orEmpty(),
        )
    }

    override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean): Boolean {
        libraryEntry(scrobblerMangaId)?.let {
            saveLibraryEntry(mangaId, scrobblerMangaId, it, comment = null)
            return true
        }
        if (!allowCreate) throw org.koitharu.kotatsu.scrobbling.common.data.TrackerEntryCreationRequired()
        val body = JSONObject().put("state", "reading").toString().toJsonBody()
        okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/my/library/$scrobblerMangaId").post(body).build(), storage).parseJson()
        fetchRate(mangaId, scrobblerMangaId, null)
        return false
    }

    override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
        val targetId = requireEntity(mangaId).targetId
        val current = libraryEntry(targetId) ?: throw TrackerRemoteEntryMissing()
        if (nextTrackerProgress(chapter, current.optDouble("progress_chapter", 0.0).toInt()) == null) {
            saveLibraryEntry(mangaId, targetId, current, current.getStringOrNull("note"))
            return
        }
        val body = JSONObject().put("progress_chapter", chapter).toString().toJsonBody()
        okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/my/library/$targetId").put(body).build(), storage).parseJson()
        fetchRate(mangaId, targetId, null)
    }

    override suspend fun updateStatus(rateId: Int, mangaId: Long, status: String) {
        val target = requireEntity(mangaId).targetId
        val existing = libraryEntry(target) ?: throw TrackerRemoteEntryMissing()
        val payload = org.koitharu.kotatsu.scrobbling.v2.LegacyStatusPatch.json(ScrobblerService.MANGABAKA, status)
        okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/my/library/$target")
            .put(payload.toString().toJsonBody()).build(), storage).parseJson()
        fetchRate(mangaId, target, existing.getStringOrNull("note"))
    }

    override suspend fun updateRate(
        rateId: Int,
        mangaId: Long,
        rating: Float,
        status: String?,
        comment: String?,
        setStartDate: Boolean,
    ) {
        val current = requireEntity(mangaId)
        pushRate(
            mangaId,
            rateId.toLong(),
            status ?: current.status,
            current.chapter,
            rating / RATING_MAX,
            comment,
            setStartDate,
        )
    }

    private suspend fun requireEntity(mangaId: Long) = requireNotNull(
        db.getScrobblingDao().find(ScrobblerService.MANGABAKA.id, mangaId, storage.requestSession().accountId),
    ) { "Scrobbling info for manga $mangaId not found" }

    private suspend fun pushRate(
        mangaId: Long,
        targetId: Long,
        status: String?,
        chapter: Int,
        rating: Float,
        comment: String?,
        setStartDate: Boolean,
    ): ScrobblingEntity {
        val json = JSONObject()
            .put("state", status ?: "reading")
            .put("note", comment ?: JSONObject.NULL)
            .put(
                "rating",
                (rating * RATING_MAX).toInt().coerceIn(0, RATING_MAX).takeIf { it > 0 } ?: JSONObject.NULL,
            )
        if (setStartDate) {
            json.put("start_date", LocalDate.now().toString())
        }
        val body = json.toString().toJsonBody()
        okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/my/library/$targetId").put(body).build(), storage).parseJson()
        return fetchRate(mangaId, targetId, comment)
    }

    private suspend fun fetchRate(mangaId: Long, targetId: Long, comment: String?): ScrobblingEntity {
        val json = checkNotNull(libraryEntry(targetId)) { "Series $targetId is not in the library" }
        return saveLibraryEntry(mangaId, targetId, json, comment)
    }

    private suspend fun libraryEntry(targetId: Long): JSONObject? {
        val response = try {
            okHttp.trackerCall(Request.Builder().url("$BASE_API_URL/my/library/$targetId").get().build(), storage)
        } catch (error: org.koitharu.kotatsu.scrobbling.common.data.TrackerHttpException) {
            if (error.code == 404) return null
            throw error
        }
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            response.close()
            return null
        }
        return response.parseJson().getJSONObject("data")
    }

    private suspend fun saveLibraryEntry(
        mangaId: Long,
        targetId: Long,
        json: JSONObject,
        comment: String?,
    ): ScrobblingEntity = saveRate(
            mangaId = mangaId,
            targetId = targetId,
            status = json.getStringOrNull("state"),
            chapter = json.optDouble("progress_chapter", 0.0).takeUnless { it.isNaN() }?.toInt() ?: 0,
            rating = (json.optInt("rating", 0).toFloat() / RATING_MAX).coerceIn(0f, 1f),
            comment = json.getStringOrNull("note") ?: comment,
        )

    private suspend fun saveRate(
        mangaId: Long,
        targetId: Long,
        status: String?,
        chapter: Int,
        rating: Float,
        comment: String?,
    ): ScrobblingEntity {
        val session = storage.requestSession()
        requireNotNull(session.accountId) { "Tracker account unavailable" }
        val entity = ScrobblingEntity(
            accountId = session.accountId,
            scrobbler = ScrobblerService.MANGABAKA.id,
            id = targetId.toInt(),
            mangaId = mangaId,
            targetId = targetId,
            status = status,
            chapter = chapter,
            comment = comment,
            rating = rating.coerceIn(0f, 1f),
        )
        db.getScrobblingDao().saveLinked(entity)
        return entity
    }

    private fun jsonToManga(json: JSONObject, sourceTitle: String): ScrobblerManga? {
        if (json.getStringOrNull("type")?.contains("novel", ignoreCase = true) == true) {
            return null
        }
        val title = json.title()
        return ScrobblerManga(
            id = json.getLong("id"),
            name = title,
            altName = json.getStringOrNull("native_title"),
            cover = json.coverUrl(),
            url = "$BASE_WEB_URL/${json.getLong("id")}",
            isBestMatch = title.equals(sourceTitle, ignoreCase = true),
        )
    }

    private fun JSONObject.title(): String = getStringOrNull("title")
        ?: getStringOrNull("romanized_title")
        ?: getStringOrNull("native_title")
        ?: "#${getLong("id")}"

    private fun JSONObject.coverUrl(): String? = optJSONObject("cover")
        ?.optJSONObject("x250")
        ?.getStringOrNull("x1")

    private fun String.toJsonBody() = toRequestBody("application/json".toMediaType())

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(50)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    private fun String.toS256Challenge(): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.US_ASCII)),
        Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
    )
}
