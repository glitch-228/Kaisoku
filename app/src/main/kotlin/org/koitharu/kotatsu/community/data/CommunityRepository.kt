package org.koitharu.kotatsu.community.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.parseJsonOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.await
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the Kotatsu-Redo community API.
 *
 * The server deliberately has no password or login endpoint.  A random 256-bit bearer secret is
 * generated on the device and is the pseudonymous account credential.  Nothing is sent until the
 * user enables Community features.  Aggregate source scores remain cacheable public data.
 */
@Singleton
class CommunityRepository @Inject constructor(
	@ApplicationContext context: Context,
	@BaseHttpClient private val client: OkHttpClient,
	private val settings: AppSettings,
) {
	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private val jsonType = "application/json; charset=utf-8".toMediaType()
	private val scoresLock = Any()
	private val telemetryLock = Any()
	private var scores: Map<String, CommunitySourceScore> = emptyMap()
	private var scoresLoadedAt = 0L
	private val deviceId: String = Settings.Secure.getString(
		context.contentResolver,
		Settings.Secure.ANDROID_ID,
	)?.takeIf { it.isNotBlank() } ?: "kaisoku-${Build.FINGERPRINT.hashCode()}"

	val isEnabled: Boolean
		get() = settings.isCommunityEnabled

	val isTelemetryEnabled: Boolean
		get() = settings.isCommunityTelemetryEnabled

	val serverUrl: String
		get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER)?.trimEnd('/').orEmpty().ifBlank { DEFAULT_SERVER }

	val hasIdentity: Boolean
		get() = prefs.getString(KEY_SECRET, null).orEmpty().isNotBlank()

	fun setEnabled(enabled: Boolean) {
		settings.isCommunityEnabled = enabled
		if (!enabled) settings.isCommunityTelemetryEnabled = false
	}

	fun setTelemetryEnabled(enabled: Boolean) {
		settings.isCommunityTelemetryEnabled = enabled && settings.isCommunityEnabled
	}

	suspend fun ensureIdentity(): CommunityIdentity = withContext(Dispatchers.IO) {
		check(isEnabled) { "Community features are disabled" }
		val secret = getOrCreateSecret()
		val body = JSONObject().put("ssaid", deviceId)
		val response = request("/v1/identity/hello", method = "POST", body = body, secret = secret)
		parseIdentity(response)
	}

	suspend fun identityOrNull(): CommunityIdentity? = withContext(Dispatchers.IO) {
		if (!isEnabled || !hasIdentity) return@withContext null
		runCatching {
			parseIdentity(request("/v1/identity/me", secret = requireSecret()))
		}.getOrNull()
	}

	suspend fun importRecoveryKey(value: String): CommunityIdentity = withContext(Dispatchers.IO) {
		val decoded = Base64.decode(value.trim(), Base64.DEFAULT)
		check(decoded.size == SECRET_BYTES) { "Invalid community recovery key" }
		val secret = Base64.encodeToString(decoded, Base64.NO_WRAP)
		prefs.edit().putString(KEY_SECRET, secret).apply()
		try {
			ensureIdentity()
		} catch (error: Throwable) {
			prefs.edit().remove(KEY_SECRET).apply()
			throw error
		}
	}

	fun exportRecoveryKey(): String? = prefs.getString(KEY_SECRET, null)

	suspend fun deleteIdentity() = withContext(Dispatchers.IO) {
		if (hasIdentity) {
			runCatching { request("/v1/identity/me", method = "DELETE", secret = requireSecret()) }
		}
		prefs.edit().remove(KEY_SECRET).apply()
		synchronized(scoresLock) {
			scores = emptyMap()
			scoresLoadedAt = 0L
		}
	}

	suspend fun resolveWork(manga: Manga): Long = withContext(Dispatchers.IO) {
		val cacheKey = "${manga.source.name}\u0000${manga.url.ifBlank { manga.publicUrl }}"
		prefs.getString(KEY_WORK_PREFIX + cacheKey.hashCode(), null)?.toLongOrNull()?.let { return@withContext it }
		val fingerprint = JSONObject()
			.put("source", manga.source.name)
			.put("key", manga.url.ifBlank { manga.publicUrl })
			.put("title", manga.title)
			.put("alt_titles", JSONArray(manga.altTitles.toList()))
			.put("content_type", "manga")
			.put("nsfw", manga.contentRating != null)
		val response = request(
			path = "/v1/works/resolve",
			method = "POST",
			body = JSONObject().put("works", JSONArray().put(fingerprint)),
			secret = requireSecret(),
		)
		val resolved = response.optJSONArray("resolved")?.optJSONObject(0)
			?: error("Community server returned no work identity")
		val workId = resolved.optString("work_id").toLongOrNull()
			?: error("Community server returned an invalid work identity")
		prefs.edit().putString(KEY_WORK_PREFIX + cacheKey.hashCode(), workId.toString()).apply()
		workId
	}

	suspend fun getRating(manga: Manga): CommunityRating = withContext(Dispatchers.IO) {
		val workId = resolveWork(manga)
		val response = request("/v1/works/$workId/rating", secret = requireSecret())
		CommunityRating(
			workId = workId,
			count = response.optInt("count"),
			average = response.optDouble("average", 0.0).toFloat(),
			mine = if (response.isNull("mine")) null else response.optDouble("mine").toFloat(),
		)
	}

	suspend fun setRating(manga: Manga, stars: Float): CommunityRating = withContext(Dispatchers.IO) {
		if (stars <= 0f) return@withContext clearRating(manga)
		val workId = resolveWork(manga)
		val value = (stars.coerceIn(0.5f, 5f) * 2f).toInt()
		val response = request(
			"/v1/works/$workId/rating",
			method = "PUT",
			body = JSONObject().put("value", value),
			secret = requireSecret(),
		)
		CommunityRating(workId, response.optInt("count"), response.optDouble("average", 0.0).toFloat(), response.optDouble("mine", stars.toDouble()).toFloat())
	}

	suspend fun clearRating(manga: Manga): CommunityRating = withContext(Dispatchers.IO) {
		val workId = resolveWork(manga)
		val response = request("/v1/works/$workId/rating", method = "DELETE", secret = requireSecret())
		CommunityRating(workId, response.optInt("count"), response.optDouble("average", 0.0).toFloat(), null)
	}

	suspend fun getComments(
		manga: Manga,
		chapter: Long? = null,
		sort: String = "top",
		lang: String? = null,
		offset: Int = 0,
		limit: Int = 100,
	): List<CommunityComment> = getCommentsPage(manga, chapter, sort, lang, offset, limit).comments

	suspend fun getCommentsPage(
		manga: Manga,
		chapter: Long? = null,
		sort: String = "top",
		lang: String? = null,
		offset: Int = 0,
		limit: Int = 100,
	): CommunityCommentPage = withContext(Dispatchers.IO) {
		val workId = resolveWork(manga)
		val url = serverUrl.toHttpUrl().newBuilder()
			.addPathSegments("v1/works/$workId/comments")
			.addQueryParameter("sort", if (sort == "new") "new" else "top")
			.addQueryParameter("limit", limit.coerceIn(1, 100).toString())
			.addQueryParameter("offset", offset.coerceAtLeast(0).toString())
			.apply { lang?.takeIf { it.isNotBlank() }?.let { addQueryParameter("lang", it) } }
			.apply { chapter?.let { addQueryParameter("chapter", it.toString()) } }
			.build()
		val response = request(url.toString(), secret = requireSecret())
		return@withContext CommunityCommentPage(
			comments = response.optJSONArray("comments")?.toComments().orEmpty(),
			total = response.optInt("total"),
			byLanguage = response.optJSONObject("by_language")?.toIntMap().orEmpty(),
			minimumLength = response.optJSONObject("rules")?.optInt("min_length", 20) ?: 20,
		)
	}

	suspend fun postComment(
		manga: Manga,
		body: String,
		chapter: Long? = null,
		parentId: Long? = null,
		spoiler: Boolean = false,
	): CommunityComment = withContext(Dispatchers.IO) {
		val workId = resolveWork(manga)
		val url = serverUrl.toHttpUrl().newBuilder()
			.addPathSegments("v1/works/$workId/comments")
			.apply { chapter?.let { addQueryParameter("chapter", it.toString()) } }
			.build()
		val payload = JSONObject()
			.put("body", body.trim())
			.apply { parentId?.let { put("parent_id", it.toString()) } }
			.put("is_spoiler", spoiler)
			.put("lang", java.util.Locale.getDefault().language)
		val response = request(url.toString(), method = "POST", body = payload, secret = requireSecret())
		response.toComment()
	}

	suspend fun editComment(commentId: Long, body: String): CommunityComment = withContext(Dispatchers.IO) {
		request(
			"/v1/comments/$commentId",
			method = "PATCH",
			body = JSONObject().put("body", body.trim()).put("lang", Locale.getDefault().language),
			secret = requireSecret(),
		).toComment()
	}

	suspend fun deleteComment(commentId: Long) = withContext(Dispatchers.IO) {
		request("/v1/comments/$commentId", method = "DELETE", secret = requireSecret())
		Unit
	}

	suspend fun vote(commentId: Long, value: Int): CommunityComment = withContext(Dispatchers.IO) {
		request(
			"/v1/comments/$commentId/vote",
			method = "PUT",
			body = JSONObject().put("value", value.coerceIn(-1, 1)),
			secret = requireSecret(),
		).toComment()
	}

	suspend fun setNickname(nickname: String): CommunityIdentity = withContext(Dispatchers.IO) {
		request(
			"/v1/identity/nickname",
			method = "POST",
			body = JSONObject().put("nickname", nickname.trim()),
			secret = requireSecret(),
		).let(::parseIdentity)
	}

	suspend fun getNotifications(limit: Int = 100): List<CommunityNotification> = withContext(Dispatchers.IO) {
		val url = serverUrl.toHttpUrl().newBuilder()
			.addPathSegments("v1/notifications")
			.addQueryParameter("limit", limit.coerceIn(1, 100).toString())
			.apply {
				prefs.getString(KEY_NOTIFICATION_CURSOR, null)?.takeIf { it.isNotBlank() }?.let {
					addQueryParameter("since", it)
				}
			}
			.build()
		val response = request(url.toString(), secret = requireSecret())
		response.optString("cursor").takeIf { it.isNotBlank() }?.let {
			prefs.edit().putString(KEY_NOTIFICATION_CURSOR, it).apply()
		}
		response.optJSONArray("replies")?.toNotifications().orEmpty()
	}

	suspend fun exportData(): String = withContext(Dispatchers.IO) {
		requestText("/v1/identity/export", secret = requireSecret())
	}

	suspend fun disputeFilterBlock(blockId: Long) = withContext(Dispatchers.IO) {
		request("/v1/filter/blocks/$blockId/dispute", method = "POST", secret = requireSecret())
		Unit
	}

	suspend fun refreshScores(force: Boolean = false): Map<String, CommunitySourceScore> = withContext(Dispatchers.IO) {
		if (!isEnabled) return@withContext emptyMap()
		synchronized(scoresLock) {
			if (!force && scores.isNotEmpty() && System.currentTimeMillis() - scoresLoadedAt < SCORE_CACHE_MS) return@synchronized scores
		}
		val response = runCatching {
			request("/v1/sources/scores?region=${java.util.Locale.getDefault().country}")
		}.getOrElse {
			return@withContext synchronized(scoresLock) { scores }
		}
		val loaded = (response.optJSONArray("sources") ?: response.optJSONArray("scores")).orEmpty().toScores()
		synchronized(scoresLock) {
			scores = loaded.associateBy { it.source }
			scoresLoadedAt = System.currentTimeMillis()
			return@synchronized scores
		}
	}

	fun scoreFor(source: String): CommunitySourceScore? = synchronized(scoresLock) { scores[source] }

	suspend fun rankSources(sources: List<org.koitharu.kotatsu.core.model.MangaSourceInfo>): List<org.koitharu.kotatsu.core.model.MangaSourceInfo> {
		if (!isEnabled || sources.size < 2) return sources
		val sourceScores = refreshScores()
		if (sourceScores.isEmpty() || sources.none { sourceScores.containsKey(it.name) }) return sources
		val order = sources.withIndex().associate { it.value.name to it.index }
		return sources.sortedWith(
			compareBy<org.koitharu.kotatsu.core.model.MangaSourceInfo> { !it.isPinned }
				.thenByDescending { sourceScores[it.name]?.composite ?: Double.NEGATIVE_INFINITY }
				.thenBy { order[it.name] ?: Int.MAX_VALUE },
		)
	}

	/** Record only coarse source health data; no titles, URLs, or reading history are retained. */
	suspend fun recordProbe(
		source: String,
		operation: String,
		ok: Boolean,
		empty: Boolean = false,
		latencyMs: Long = 0L,
		cfBlocked: Boolean = false,
	) = withContext(Dispatchers.IO) {
		if (!isEnabled || !isTelemetryEnabled || !hasIdentity || source.isBlank()) return@withContext
		val normalizedOperation = operation.uppercase(Locale.ROOT)
		if (normalizedOperation !in TELEMETRY_OPERATIONS) return@withContext
		val now = System.currentTimeMillis()
		val pending = synchronized(telemetryLock) {
			val rows = prefs.getString(KEY_TELEMETRY_PENDING, null).orEmpty()
				.let { runCatching { JSONArray(it) }.getOrElse { JSONArray() } }
			val existing = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
				.firstOrNull { it.optString("source") == source && it.optString("op") == normalizedOperation }
			if (existing == null) {
				rows.put(JSONObject().put("source", source).put("op", normalizedOperation)
					.put("ok", if (ok) 1 else 0).put("fail", if (ok) 0 else 1)
					.put("empty", if (empty) 1 else 0).put("cf_blocked", if (cfBlocked) 1 else 0)
					.put("p50_ms", latencyMs.coerceIn(0L, 120_000L)).put("p90_ms", latencyMs.coerceIn(0L, 120_000L)))
			} else {
				existing.put("ok", existing.optInt("ok") + if (ok) 1 else 0)
				existing.put("fail", existing.optInt("fail") + if (ok) 0 else 1)
				existing.put("empty", existing.optInt("empty") + if (empty) 1 else 0)
				existing.put("cf_blocked", existing.optInt("cf_blocked") + if (cfBlocked) 1 else 0)
				val bounded = latencyMs.coerceIn(0L, 120_000L).toInt()
				existing.put("p50_ms", maxOf(existing.optInt("p50_ms"), bounded))
				existing.put("p90_ms", maxOf(existing.optInt("p90_ms"), bounded))
			}
			prefs.edit().putString(KEY_TELEMETRY_PENDING, rows.toString()).apply()
			rows
		}
		if (pending.length() > 0 && now - prefs.getLong(KEY_TELEMETRY_SENT_AT, 0L) >= TELEMETRY_INTERVAL_MS) {
			flushTelemetry(pending)
		}
	}

	private suspend fun flushTelemetry(rows: JSONArray) {
		val probes = JSONArray()
		for (index in 0 until rows.length()) {
			val row = rows.optJSONObject(index) ?: continue
			probes.put(
				JSONObject().put("source", row.optString("source")).put("op", row.optString("op"))
					.put("ok", row.optInt("ok")).put("fail", row.optInt("fail"))
					.put("empty", row.optInt("empty")).put("cf_blocked", row.optInt("cf_blocked"))
					.put("p50_ms", row.optInt("p50_ms")).put("p90_ms", row.optInt("p90_ms")),
			)
		}
		if (probes.length() == 0) return
		runCatching {
			request(
				path = "/v1/telemetry/probes",
				method = "POST",
				body = JSONObject().put("region", regionCode()).put("probes", probes),
				secret = requireSecret(),
			)
		}.onSuccess {
			synchronized(telemetryLock) {
				prefs.edit().remove(KEY_TELEMETRY_PENDING).putLong(KEY_TELEMETRY_SENT_AT, System.currentTimeMillis()).apply()
			}
		}
	}

	private fun regionCode(): String = when (Locale.getDefault().country.uppercase(Locale.ROOT)) {
		"RU", "UA", "BY", "KZ", "AM", "AZ", "GE", "MD" -> "OTHER"
		"US", "CA" -> "NA"
		"MX", "BR", "AR", "CL", "CO", "PE" -> "LATAM"
		"JP", "KR", "CN", "TW", "HK", "AU", "NZ" -> "APAC"
		"IN", "PK", "BD", "LK", "NP" -> "SOUTH_ASIA"
		"AE", "SA", "IL", "TR", "IR" -> "MENA"
		"ZA", "NG", "EG", "MA" -> "AFRICA"
		else -> "EU"
	}

	private fun requireSecret(): String = prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }
		?: error("Community identity is not initialized")

	private fun getOrCreateSecret(): String {
		prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }?.let { return it }
		val bytes = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
		return Base64.encodeToString(bytes, Base64.NO_WRAP).also {
			prefs.edit().putString(KEY_SECRET, it).apply()
		}
	}

	private suspend fun request(
		path: String,
		method: String = "GET",
		body: JSONObject? = null,
		secret: String? = null,
	): JSONObject {
		val request = Request.Builder().url(if (path.startsWith("http")) path else serverUrl + path)
			.header("Accept", "application/json")
			.apply { secret?.let { header("Authorization", "Bearer $it") } }
			.apply {
				when (method) {
					"POST" -> post((body ?: JSONObject()).toString().toRequestBody(jsonType))
					"PUT" -> put((body ?: JSONObject()).toString().toRequestBody(jsonType))
					"PATCH" -> patch((body ?: JSONObject()).toString().toRequestBody(jsonType))
					"DELETE" -> delete()
				}
			}
			.build()
		return client.newCall(request).await().parseJsonOrNull() ?: JSONObject()
	}

	private suspend fun requestText(path: String, secret: String? = null): String {
		val request = Request.Builder().url(if (path.startsWith("http")) path else serverUrl + path)
			.header("Accept", "application/json")
			.apply { secret?.let { header("Authorization", "Bearer $it") } }
			.get()
			.build()
		return client.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw java.io.IOException("Community server HTTP ${response.code}: ${response.body?.string()?.take(300)}")
			}
			response.body?.string().orEmpty()
		}
	}

	private fun parseIdentity(json: JSONObject) = CommunityIdentity(
		userId = json.optString("user_id"),
		displayName = json.optString("display_name"),
		nickname = json.optString("nickname").takeIf { it.isNotBlank() },
		tier = json.optInt("tier"),
	)

	private fun JSONObject.toComment() = CommunityComment(
		id = optString("id").toLongOrNull() ?: 0L,
		workId = optString("work_id").toLongOrNull() ?: 0L,
		chapterId = optString("chapter_id").toLongOrNull(),
		parentId = optString("parent_id").toLongOrNull(),
		depth = optInt("depth"),
		author = optString("author"),
		body = optString("body"),
		createdAt = optString("created_at"),
		score = optDouble("score", 0.0),
		up = optInt("up"),
		down = optInt("down"),
		myVote = optInt("my_vote"),
		isMine = optBoolean("is_mine"),
		isSpoiler = optBoolean("is_spoiler"),
		lang = optString("lang").takeIf { it.isNotBlank() },
		deleted = optBoolean("deleted"),
	)

	private fun JSONArray.toComments(): List<CommunityComment> = (0 until length()).mapNotNull { optJSONObject(it)?.toComment() }

	private fun JSONArray.toNotifications(): List<CommunityNotification> = (0 until length()).mapNotNull { index ->
		val json = optJSONObject(index) ?: return@mapNotNull null
		val commentId = json.optString("comment_id").toLongOrNull() ?: return@mapNotNull null
		val workId = json.optString("work_id").toLongOrNull() ?: return@mapNotNull null
		CommunityNotification(
			commentId = commentId,
			workId = workId,
			chapterId = json.optString("chapter_id").toLongOrNull(),
			parentId = json.optString("parent_id").toLongOrNull(),
			author = json.optString("author"),
			preview = json.optString("preview"),
			createdAt = json.optString("created_at"),
		)
	}

	private fun JSONObject.toIntMap(): Map<String, Int> = keys().asSequence().associateWith { optInt(it) }

	private fun JSONArray.toScores(): List<CommunitySourceScore> = (0 until length()).mapNotNull { item ->
		val json = optJSONObject(item) ?: return@mapNotNull null
		CommunitySourceScore(
			source = json.optString("source"),
			composite = json.optDouble("composite", 0.0),
			stability = json.optDouble("stability", 0.0),
			popularity = json.optDouble("popularity", 0.0),
			samples = json.optInt("samples"),
		)
	}

	private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

	companion object {
		const val DEFAULT_SERVER = "https://community.kotatsuredo.app"
		private const val PREFS = "community_identity"
		private const val KEY_SECRET = "secret"
		private const val KEY_SERVER = "server"
		private const val KEY_WORK_PREFIX = "work_"
		private const val KEY_TELEMETRY_PENDING = "telemetry_pending"
		private const val KEY_TELEMETRY_SENT_AT = "telemetry_sent_at"
		private const val KEY_NOTIFICATION_CURSOR = "notification_cursor"
		private const val SECRET_BYTES = 32
		private const val SCORE_CACHE_MS = 24 * 60 * 60 * 1000L
		private const val TELEMETRY_INTERVAL_MS = 24 * 60 * 60 * 1000L
		private val TELEMETRY_OPERATIONS = setOf("SEARCH", "DETAILS", "PAGES")
	}
}
