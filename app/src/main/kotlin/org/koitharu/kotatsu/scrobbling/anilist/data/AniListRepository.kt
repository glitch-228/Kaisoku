package org.koitharu.kotatsu.scrobbling.anilist.data

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
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
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
private const val LIBRARY_PAGE_SIZE = 50
private const val LIBRARY_CACHE_TTL = 5 * 60 * 1000L
private const val REQUEST_QUERY = "query"
private const val REQUEST_MUTATION = "mutation"
private const val KEY_SCORE_FORMAT = "score_format"

@Singleton
class AniListRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.ANILIST) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.ANILIST) private val storage: ScrobblerStorage,
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
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
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
		storage[KEY_SCORE_FORMAT] = jo.getJSONObject("mediaListOptions").getString("scoreFormat")
		return AniListUser(jo).also { storage.user = it }
	}

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun unregister(mangaId: Long) {
		return db.getScrobblingDao().delete(ScrobblerService.ANILIST.id, mangaId)
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
			entry.copy(localMangaId = dao.findMangaIdByTarget(ScrobblerService.ANILIST.id, entry.mediaId))
		}
	}

	suspend fun refreshLibrary(userId: Long, force: Boolean = false): List<AniListLibraryEntry> {
		val key = cacheKey(userId)
		val fetchedAt = libraryPrefs.getLong(cacheTimestampKey(userId), 0L)
		if (!force && isAniListLibraryCacheFresh(libraryPrefs.contains(key), fetchedAt, System.currentTimeMillis(), LIBRARY_CACHE_TTL)) {
			return cachedLibrary(userId)
		}
		val result = ArrayList<AniListLibraryEntry>()
		var page = 1
		var hasNext = true
		while (hasNext) {
			val response = doRequest(
				REQUEST_QUERY,
				"""
				Page(page: $page, perPage: $LIBRARY_PAGE_SIZE) {
					pageInfo { hasNextPage }
					mediaList(userId: $userId, type: MANGA, sort: UPDATED_TIME_DESC) {
						id
						status
						score
						progress
						notes
						updatedAt
						media {
							id
							chapters
							title { userPreferred }
							coverImage { large }
							siteUrl
						}
					}
				}
			""",
			)
			val data = response.getJSONObject("data").getJSONObject("Page")
			val entries = data.getJSONArray("mediaList")
			if (entries.length() == 0) break
			for (index in 0 until entries.length()) {
				result += AniListLibraryEntry(entries.getJSONObject(index))
			}
			hasNext = data.getJSONObject("pageInfo").getBoolean("hasNextPage")
			page++
			if (hasNext) delay(700L)
		}
		val linkedResult = syncLinkedEntries(result)
		schedulePendingProgressRetries(userId, linkedResult)
		libraryPrefs.edit {
			putString(key, JSONArray().also { array -> linkedResult.forEach { array.put(it.toJson()) } }.toString())
			putLong(cacheTimestampKey(userId), System.currentTimeMillis())
		}
		return linkedResult
	}

	private suspend fun syncLinkedEntries(entries: List<AniListLibraryEntry>): List<AniListLibraryEntry> {
		val dao = db.getScrobblingDao()
		return entries.map { entry ->
			val mangaId = dao.findMangaIdByTarget(ScrobblerService.ANILIST.id, entry.mediaId)
			if (mangaId != null) {
				dao.upsert(
					ScrobblingEntity(
						scrobbler = ScrobblerService.ANILIST.id,
						id = entry.listEntryId.toInt(),
						mangaId = mangaId,
						targetId = entry.mediaId,
						status = entry.status,
						chapter = entry.progress,
						comment = entry.notes,
						rating = ScoreFormat.of(storage[KEY_SCORE_FORMAT]).normalize(entry.score),
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
			List(array.length()) { index -> AniListLibraryEntry(array.getJSONObject(index)) }
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

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		val existing = doRequest(
			REQUEST_QUERY,
			"""
				Media(id: $scrobblerMangaId) {
					mediaListEntry {
						id
						mediaId
						status
						notes
						score
						progress
					}
				}
			""",
		).getJSONObject("data").getJSONObject("Media").optJSONObject("mediaListEntry")
		if (existing != null) {
			saveRate(existing, mangaId)
			return true
		}
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(mediaId: $scrobblerMangaId) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
		return false
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		try {
			pushProgress(rateId, mangaId, chapter)
		} catch (error: kotlinx.coroutines.CancellationException) {
			throw error
		} catch (error: Throwable) {
			scheduleProgressRetry(rateId, mangaId, chapter)
			throw error
		}
	}

	private suspend fun pushProgress(rateId: Int, mangaId: Long, chapter: Int) {
		progressMutex.withLock {
			val current = doRequest(
				REQUEST_QUERY,
				"""
					MediaList(id: $rateId) {
						id mediaId status notes score progress
					}
				""",
			).getJSONObject("data").optJSONObject("MediaList") ?: return@withLock
			val currentProgress = current.optInt("progress")
			if (chapter <= currentProgress) {
				saveRate(current, mangaId)
				return@withLock
			}
			val response = doRequest(
				REQUEST_MUTATION,
				"""
					SaveMediaListEntry(id: $rateId, progress: $chapter) {
						id mediaId status notes score progress
					}
				""",
			)
			saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
		}
	}

	private suspend fun scheduleProgressRetry(rateId: Int, mangaId: Long, chapter: Int) {
		pendingProgressMutex.withLock {
			val userId = storage.user?.id ?: return
			val targetId = db.getScrobblingDao().find(ScrobblerService.ANILIST.id, mangaId)?.targetId ?: return
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
		val key = pendingPrefix(userId) + targetId
		return retryAniListProgress(
			mutex = pendingProgressMutex,
			readPending = { libraryPrefs.getInt(key, 0) },
			clearPending = { libraryPrefs.edit { remove(key) } },
			push = { chapter -> pushProgress(rateId, mangaId, chapter) },
		)
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
					score
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
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.ANILIST.id,
			id = json.getInt("id"),
			mangaId = mangaId,
			targetId = json.getLong("mediaId"),
			status = json.getString("status"),
			chapter = json.getInt("progress"),
			comment = json.getString("notes"),
			rating = scoreFormat.normalize(json.getDouble("score").toFloat()),
		)
		db.getScrobblingDao().upsert(entity)
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
		val json = okHttp.newCall(request.build()).await().parseJson()
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
