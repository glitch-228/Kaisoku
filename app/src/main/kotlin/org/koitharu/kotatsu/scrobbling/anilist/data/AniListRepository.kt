package org.koitharu.kotatsu.scrobbling.anilist.data

import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerJson
import org.koitharu.kotatsu.scrobbling.common.data.rawTrackerElement
import org.koitharu.kotatsu.scrobbling.common.data.raw
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.array
import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.exception.GraphQLException
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toIntUp
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerTimestamp
import org.koitharu.kotatsu.scrobbling.common.domain.model.strings
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.accountSession
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
import org.koitharu.kotatsu.scrobbling.common.domain.model.AniListLibraryEntry
import org.koitharu.kotatsu.scrobbling.anilist.work.AniListProgressWorker
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

internal const val REDIRECT_URI = "kotatsu://anilist-auth"
private const val BASE_URL = "https://anilist.co/api/v2/"
private const val ENDPOINT = "https://graphql.anilist.co"
private const val MANGA_PAGE_SIZE = 10
private const val LIBRARY_CACHE_TTL = 5 * 60 * 1000L
private const val REQUEST_QUERY = "query"
private const val REQUEST_MUTATION = "mutation"
private const val KEY_SCORE_FORMAT = "score_format"

@Singleton
class AniListRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ScrobblerType(ScrobblerService.ANILIST) private val okHttp: OkHttpClient,
    @ScrobblerType(ScrobblerService.ANILIST) override val storage: ScrobblerStorage,
    private val db: MangaDatabase,
    private val workManager: WorkManager,
) : ScrobblerRepository {

    private val libraryPrefs = context.getSharedPreferences("anilist_library_cache", Context.MODE_PRIVATE)
    private val progressMutex = Mutex()
    private val pendingProgressMutex = Mutex()

    private val clientId = context.getString(R.string.anilist_clientId)
    private val clientSecret = context.getString(R.string.anilist_clientSecret)

    override val oauthUrl: String
        get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
            "redirect_uri=${REDIRECT_URI}&response_type=code"

    override val isAuthorized: Boolean
        get() = storage.accessToken != null

    private val shrinkRegex = Regex("\\t+")

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
        val response = doRequest(
            REQUEST_QUERY,
            """
            AniChartUser {
                user {
                    id
                    name
                    avatar {
                        medium
                    }
                    mediaListOptions {
                        scoreFormat
                    }
                }
            }
        """,
        )
        val jo = response.getJSONObject("data").getJSONObject("AniChartUser").getJSONObject("user")
        storage.checkSession(session)
        storage[KEY_SCORE_FORMAT] = jo.getJSONObject("mediaListOptions").getString("scoreFormat")
        return AniListUser(jo).also { storage.saveUser(session, it) }
    }

    override val cachedUser: ScrobblerUser?
        get() {
            return storage.user
        }

    override suspend fun unregister(mangaId: Long) {
        return db.getScrobblingDao().delete(ScrobblerService.ANILIST.id, mangaId, storage.requestSession().accountId)
    }

    override fun logout() {
        storage.user?.id?.let { userId ->
            val pendingKeys = libraryPrefs.all.keys.filter { it.startsWith(pendingPrefix(userId)) }
            libraryPrefs.edit {
                remove(cacheKey(userId))
                remove(cacheTimestampKey(userId))
                pendingKeys.forEach(::remove)
            }
        }
        storage.clear()
    }

    suspend fun cachedLibrary(userId: Long): List<AniListLibraryEntry> {
        val dao = db.getScrobblingDao()
        // Cached snapshots are for display only: tracker edits may be newer than this snapshot.
        return readCachedLibrary(userId).map { entry ->
            entry.copy(localMangaId = dao.findMangaIdByTarget(ScrobblerService.ANILIST.id, entry.mediaId, userId))
        }
    }

    suspend fun refreshLibrary(userId: Long, force: Boolean = false, syncLinks: Boolean = true): List<AniListLibraryEntry> {
        val session = storage.requestSession()
        check(session.accountId == userId) { "Tracker account changed" }
        val key = cacheKey(userId)
        val fetchedAt = libraryPrefs.getLong(cacheTimestampKey(userId), 0L)
        if (!force && isAniListLibraryCacheFresh(libraryPrefs.contains(key), fetchedAt, System.currentTimeMillis(), LIBRARY_CACHE_TTL)) {
            return cachedLibrary(userId)
        }
        val result = collectAniListLibraryChunks(userId) { chunk ->
            doRequest(
                REQUEST_QUERY,
                """
                MediaListCollection(userId: $userId, type: MANGA, sort: UPDATED_TIME_DESC,
                    forceSingleCompletedList: true, chunk: $chunk, perChunk: 500) {
                    user { id }
                    hasNextChunk
                    lists { entries {
                        id
                        status
                        score(format: POINT_100)
                        progress
                        notes
                        updatedAt
                        media {
                            id
                            chapters
                            title { userPreferred romaji english native }
                            synonyms
                            format
                            coverImage { large }
                            siteUrl
                        }
                    } }
                }
            """,
            )
        }
        storage.checkSession(session)
        val linkedResult = if (syncLinks) syncLinkedEntries(result) else result
        val retryEntries = if (syncLinks) linkedResult else result.map { entry ->
            entry.copy(localMangaId = db.getScrobblingDao().findMangaIdByTarget(ScrobblerService.ANILIST.id, entry.mediaId, userId))
        }
        schedulePendingProgressRetries(userId, retryEntries)
        libraryPrefs.edit {
            putString(key, JSONArray().also { array -> linkedResult.forEach { array.put(it.toJson()) } }.toString())
            putLong(cacheTimestampKey(userId), System.currentTimeMillis())
        }
        return linkedResult
    }

    private suspend fun syncLinkedEntries(entries: List<AniListLibraryEntry>): List<AniListLibraryEntry> {
        val session = storage.requestSession()
        val dao = db.getScrobblingDao()
        return entries.map { entry ->
            val mangaId = dao.findMangaIdByTarget(ScrobblerService.ANILIST.id, entry.mediaId, session.accountId)
            if (mangaId != null) {
                dao.updateLinked(
                    ScrobblingEntity(
                        accountId = requireNotNull(session.accountId),
                        scrobbler = ScrobblerService.ANILIST.id,
                        id = entry.listEntryId.toInt(),
                        mangaId = mangaId,
                        targetId = entry.mediaId,
                        status = entry.status,
                        chapter = entry.progress,
                        comment = entry.notes,
                        rating = ScoreFormat.POINT_100.normalize(entry.score),
                    ),
                )
            }
            entry.copy(localMangaId = mangaId)
        }
    }

    private fun readCachedLibrary(userId: Long): List<AniListLibraryEntry> {
        val json = libraryPrefs.getString(cacheKey(userId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { index -> AniListLibraryEntry(array.getJSONObject(index), cached = true) }
        }.getOrDefault(emptyList())
    }

    private fun cacheKey(userId: Long) = "library_$userId"
    private fun cacheTimestampKey(userId: Long) = "library_updated_$userId"
    private fun pendingPrefix(userId: Long) = "pending_progress_${userId}_"

    private suspend fun schedulePendingProgressRetries(userId: Long, entries: List<AniListLibraryEntry>) {
        entries.forEach { entry ->
            val mangaId = entry.localMangaId ?: return@forEach
            val targetId = entry.mediaId
            if (libraryPrefs.getInt(pendingPrefix(userId) + targetId, 0) > 0) {
                enqueueProgressRetry(userId, targetId, mangaId, entry.listEntryId.toInt())
            }
        }
    }

    override suspend fun readRawEntry(mediaId: Long): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry? {
        require(mediaId > 0)
        val session = storage.requestSession()
        val owner = requireNotNull(session.accountId)
        val query = "query { MediaList(mediaId: $mediaId, userId: $owner, type: MANGA) { id userId mediaId status progress progressVolumes score notes updatedAt } }"
        val body = JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())
        val json = okHttp.trackerCall(Request.Builder().url(ENDPOINT).post(body).build(), storage).rawTrackerJson()
        val errors = json["errors"] as? kotlinx.serialization.json.JsonArray
        if (!errors.isNullOrEmpty()) {
            val missing = errors.all { (it as? kotlinx.serialization.json.JsonObject)?.raw("status") == "404" }
            if (missing) return null
            throw java.io.IOException("AniList rejected the incoming-progress query")
        }
        val row = json.obj("data")["MediaList"] as? kotlinx.serialization.json.JsonObject ?: return null
        require(row.raw("userId") == owner.toString() && row.raw("mediaId") == mediaId.toString()) {
            "Tracker returned another entry or account"
        }
        val metadata = getMangaInfo(mediaId)
        require(metadata.id == mediaId) { "Tracker returned another title" }
        storage.checkSession(session)
        val title = org.koitharu.kotatsu.scrobbling.v2.TrackerTitle(mediaId.toString(), metadata.name,
            coverUrl = metadata.cover, description = metadata.descriptionHtml, url = metadata.url)
        return org.koitharu.kotatsu.scrobbling.common.data.TrackerRawRead.entry(ScrobblerService.ANILIST,
            owner.toString(), mediaId.toString(), title, row)
    }

    override val supportsRawWrites: Boolean get() = true

    override suspend fun mutateRawEntry(
        expected: org.koitharu.kotatsu.scrobbling.v2.TrackerReference,
        patch: org.koitharu.kotatsu.scrobbling.v2.TrackerPatch,
        allowCreate: Boolean,
    ): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry =
        org.koitharu.kotatsu.scrobbling.common.data.TrackerRawMutation.execute(
            this, ScrobblerService.ANILIST, okHttp, expected, patch, allowCreate,
        )

    override suspend fun readCataloguePage(query: String, cursor: String?):
        org.koitharu.kotatsu.search.unified.CataloguePage {
        val session = accountSession()
        return storage.withSession(session) {
            org.koitharu.kotatsu.search.unified.TrackerCatalogue.read(
                ScrobblerService.ANILIST, query, cursor,
            ) { request -> okHttp.trackerCall(request, storage).rawTrackerElement() }
        }
    }

    override suspend fun readRawLibraryPage(cursor: String?): org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage {
        val session = accountSession()
        return storage.withSession(session) {
            val owner = readRawOwner()
            org.koitharu.kotatsu.scrobbling.common.data.TrackerRawLibrary.read(
                ScrobblerService.ANILIST, owner, session.revision, cursor,
            ) { request -> okHttp.trackerCall(request, storage).rawTrackerElement() }
        }
    }

    override suspend fun fetchLibrary(): List<TrackerLibraryEntry> {
        val owner = requireNotNull(storage.requestSession().accountId)
        return refreshLibrary(owner, force = true, syncLinks = false).map { entry ->
            TrackerLibraryEntry(ScrobblerService.ANILIST, entry.mediaId, entry.listEntryId.toInt(), entry.title,
                entry.coverUrl, entry.url, entry.status, entry.progress, entry.chapters,
                ScoreFormat.POINT_100.normalize(entry.score) * 10f, entry.notes,
                entry.updatedAt * 1000, entry.aliases, entry.format)
        }
    }

    suspend fun resolveMalId(id: Long): Long? {
        require(id > 0)
        return doRequest(REQUEST_QUERY, "Media(idMal: $id, type: MANGA) { id }")
            .getJSONObject("data").optJSONObject("Media")?.getLong("id")
    }

    override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
        val page = (offset / MANGA_PAGE_SIZE.toFloat()).toIntUp() + 1
        val response = doRequest(
            REQUEST_QUERY,
            """
            Page(page: $page, perPage: ${MANGA_PAGE_SIZE}) {
                media(type: MANGA, sort: SEARCH_MATCH, search: ${JSONObject.quote(query)}) {
                    id
                    title {
                        userPreferred
                        native
                    }
                    coverImage {
                        medium
                    }
                    siteUrl
                }
            }
        """,
        )
        val data = response.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
        return data.mapJSON { ScrobblerManga(it, query) }
    }

    override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean): Boolean {
        val existing = doRequest(
            REQUEST_QUERY,
            """
                Media(id: $scrobblerMangaId) {
                    mediaListEntry {
                        id
                        mediaId
                        status
                        notes
                        score(format: POINT_100)
                        progress
                    }
                }
            """,
        ).getJSONObject("data").getJSONObject("Media").optJSONObject("mediaListEntry")
        if (existing != null) {
            saveRate(existing, mangaId)
            return true
        }
        if (!allowCreate) throw org.koitharu.kotatsu.scrobbling.common.data.TrackerEntryCreationRequired()
        val response = doRequest(
            REQUEST_MUTATION,
            """
                SaveMediaListEntry(mediaId: $scrobblerMangaId) {
                    id
                    mediaId
                    status
                    notes
                    score(format: POINT_100)
                    progress
                }
            """,
        )
        saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
        return false
    }

    override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
        pushProgress(rateId, mangaId, chapter)
    }

    private suspend fun pushProgress(rateId: Int, mangaId: Long, chapter: Int) {
        progressMutex.withLock {
            val owner = requireNotNull(storage.requestSession().accountId)
            val current = doRequest(
                REQUEST_QUERY,
                """
                    MediaList(id: $rateId, userId: $owner) {
                        id mediaId userId status notes score(format: POINT_100) progress
                    }
                """,
            ).getJSONObject("data").optJSONObject("MediaList") ?: throw TrackerRemoteEntryMissing()
            val linked = db.getScrobblingDao().find(ScrobblerService.ANILIST.id, mangaId, storage.requestSession().accountId)
            check(linked?.targetId == current.getLong("mediaId") && linked.id == rateId) { "Tracker link changed" }
            check(current.getLong("userId") == owner) { "Tracker list entry belongs to a different account" }
            val currentProgress = current.optInt("progress")
            if (nextTrackerProgress(chapter, currentProgress) == null) {
                saveRate(current, mangaId)
                return@withLock
            }
            val response = doRequest(
                REQUEST_MUTATION,
                """
                    SaveMediaListEntry(id: $rateId, progress: $chapter) {
                        id mediaId status notes score(format: POINT_100) progress
                    }
                """,
            )
            saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
        }
    }

    private suspend fun scheduleProgressRetry(rateId: Int, mangaId: Long, chapter: Int) {
        pendingProgressMutex.withLock {
            val userId = storage.requestSession().accountId ?: return
            val targetId = db.getScrobblingDao().find(ScrobblerService.ANILIST.id, mangaId, storage.requestSession().accountId)?.targetId ?: return
            val key = pendingPrefix(userId) + targetId
            val progress = maxOf(libraryPrefs.getInt(key, 0), chapter)
            libraryPrefs.edit { putInt(key, progress) }
            enqueueProgressRetry(userId, targetId, mangaId, rateId)
        }
    }

    private fun enqueueProgressRetry(userId: Long, targetId: Long, mangaId: Long, rateId: Int) {
        val request = OneTimeWorkRequestBuilder<AniListProgressWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(AniListProgressWorker.KEY_USER_ID, userId)
                    .putLong(AniListProgressWorker.KEY_TARGET_ID, targetId)
                    .putLong(AniListProgressWorker.KEY_MANGA_ID, mangaId)
                    .putInt(AniListProgressWorker.KEY_RATE_ID, rateId)
                    .build(),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "anilist_progress_${userId}_$targetId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun retryPendingProgress(userId: Long, targetId: Long, mangaId: Long, rateId: Int): Boolean {
        if (!isAuthorized || storage.user?.id != userId) return true
        if (accountSession().accountId != userId) return true
        val linked = db.getScrobblingDao().find(ScrobblerService.ANILIST.id, mangaId, userId)
        if (linked?.targetId != targetId || linked.id != rateId) return true
        val key = pendingPrefix(userId) + targetId
        return retryAniListProgress(
            mutex = pendingProgressMutex,
            readPending = { libraryPrefs.getInt(key, 0) },
            clearPending = { libraryPrefs.edit { remove(key) } },
            push = { chapter -> storage.withSession { storage.mutationMutex.withLock {
                val current = db.getScrobblingDao().find(ScrobblerService.ANILIST.id, mangaId, userId)
                if (current?.targetId == targetId && current.id == rateId) pushProgress(rateId, mangaId, chapter)
            } } },
        )
    }

    override suspend fun updateStatus(rateId: Int, mangaId: Long, status: String) {
        org.koitharu.kotatsu.scrobbling.v2.LegacyStatusPatch.fields(ScrobblerService.ANILIST, status)
        val response = doRequest(REQUEST_MUTATION, """
            SaveMediaListEntry(id: $rateId, status: $status) {
                id mediaId status notes score(format: POINT_100) progress
            }
        """)
        saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
    }

    override suspend fun updateRate(
        rateId: Int,
        mangaId: Long,
        rating: Float,
        status: String?,
        comment: String?,
        setStartDate: Boolean,
    ) {
        val scoreRaw = (rating * 100f).roundToInt()
        val statusString = status?.let { ", status: $it" }.orEmpty()
        val notesString = comment?.let { ", notes: ${JSONObject.quote(it)}" }.orEmpty()
        val startedAtString = if (setStartDate) {
            val today = LocalDate.now()
            ", startedAt: { year: ${today.year}, month: ${today.monthValue}, day: ${today.dayOfMonth} }"
        } else {
            ""
        }
        val response = doRequest(
            REQUEST_MUTATION,
            """
                SaveMediaListEntry(id: $rateId, scoreRaw: $scoreRaw$statusString$notesString$startedAtString) {
                    id
                    mediaId
                    status
                    notes
                    score(format: POINT_100)
                    progress
                }
            """,
        )
        saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
    }

    override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
        val response = doRequest(
            REQUEST_QUERY,
            """
            Media(id: $id) {
                id
                title {
                    userPreferred
                }
                coverImage {
                    large
                }
                description
                siteUrl
            }
            """,
        )
        return ScrobblerMangaInfo(response.getJSONObject("data").getJSONObject("Media"))
    }

    private suspend fun saveRate(json: JSONObject, mangaId: Long) {
        val scoreFormat = ScoreFormat.POINT_100
        val session = storage.requestSession()
        requireNotNull(session.accountId) { "Tracker account unavailable" }
        val entity = ScrobblingEntity(
            accountId = session.accountId,
            scrobbler = ScrobblerService.ANILIST.id,
            id = json.getInt("id"),
            mangaId = mangaId,
            targetId = json.getLong("mediaId"),
            status = json.getString("status"),
            chapter = json.getInt("progress"),
            comment = json.getString("notes"),
            rating = scoreFormat.normalize(json.getDouble("score").toFloat()),
        )
        db.getScrobblingDao().saveLinked(entity)
    }

    private fun ScrobblerManga(json: JSONObject, sourceTitle: String): ScrobblerManga {
        val title = json.getJSONObject("title")
        return ScrobblerManga(
            id = json.getLong("id"),
            name = title.getString("userPreferred"),
            altName = title.getStringOrNull("native"),
            cover = json.getJSONObject("coverImage").getString("medium"),
            url = json.getString("siteUrl"),
            isBestMatch = sourceTitle.let {
                title.keys().forEach { key ->
                    if (title.getStringOrNull(key)?.equals(it, ignoreCase = true) == true) {
                        return@let true
                    }
                }
                false
            },
        )
    }

    private fun ScrobblerMangaInfo(json: JSONObject) = ScrobblerMangaInfo(
        id = json.getLong("id"),
        name = json.getJSONObject("title").getString("userPreferred"),
        cover = json.getJSONObject("coverImage").getString("large"),
        url = json.getString("siteUrl"),
        descriptionHtml = json.getString("description"),
    )

    @Suppress("FunctionName")
    private fun AniListUser(json: JSONObject) = ScrobblerUser(
        id = json.getLong("id"),
        nickname = json.getString("name"),
        avatar = json.getJSONObject("avatar").getStringOrNull("medium"),
        service = ScrobblerService.ANILIST,
    )

    private suspend fun doRequest(type: String, payload: String): JSONObject {
        val body = JSONObject()
        body.put("query", "$type { ${payload.shrink()} }")
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .post(requestBody)
            .url(ENDPOINT)
        val json = okHttp.trackerCall(request.build(), storage).parseJson()
        json.optJSONArray("errors")?.let {
            if (it.length() != 0) {
                throw GraphQLException(it)
            }
        }
        return json
    }

    private fun String.shrink() = replace(shrinkRegex, " ")
}

internal fun isAniListLibraryCacheFresh(hasCache: Boolean, fetchedAt: Long, now: Long, ttl: Long): Boolean =
    hasCache && fetchedAt > 0L && now >= fetchedAt && now - fetchedAt < ttl
