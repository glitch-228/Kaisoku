package org.koitharu.kotatsu.scrobbling.common.domain.model

import org.json.JSONObject
import org.koitharu.kotatsu.list.ui.model.ListModel

data class AniListLibraryEntry(
	val listEntryId: Long,
	val mediaId: Long,
	val title: String,
	val coverUrl: String,
	val url: String,
	val status: String,
	val progress: Int,
	val chapters: Int?,
	val score: Float,
	val notes: String?,
	val updatedAt: Long,
	val localMangaId: Long? = null,
) : ListModel {

	constructor(apiJson: JSONObject) : this(
		listEntryId = apiJson.getLong("id"),
		mediaId = apiJson.getJSONObject("media").getLong("id"),
		title = apiJson.getJSONObject("media").getJSONObject("title").getString("userPreferred"),
		coverUrl = apiJson.getJSONObject("media").getJSONObject("coverImage").optString("large"),
		url = apiJson.getJSONObject("media").optString("siteUrl"),
		status = apiJson.optString("status"),
		progress = apiJson.optInt("progress"),
		chapters = apiJson.getJSONObject("media").takeIf { !it.isNull("chapters") }
			?.optInt("chapters")?.takeIf { it > 0 },
		score = apiJson.optDouble("score").toFloat(),
		notes = apiJson.optString("notes").takeIf { it.isNotBlank() },
		updatedAt = apiJson.optLong("updatedAt"),
	)

	constructor(cacheJson: JSONObject, cached: Boolean) : this(
		listEntryId = cacheJson.getLong("listEntryId"),
		mediaId = cacheJson.getLong("mediaId"),
		title = cacheJson.getString("title"),
		coverUrl = cacheJson.optString("coverUrl"),
		url = cacheJson.optString("url"),
		status = cacheJson.optString("status"),
		progress = cacheJson.optInt("progress"),
		chapters = cacheJson.optInt("chapters").takeIf { it > 0 },
		score = cacheJson.optDouble("score").toFloat(),
		notes = cacheJson.optString("notes").takeIf { it.isNotBlank() },
		updatedAt = cacheJson.optLong("updatedAt"),
		localMangaId = cacheJson.optLong("localMangaId").takeIf { it > 0 },
	)

	fun toJson(): JSONObject = JSONObject().apply {
		put("listEntryId", listEntryId)
		put("mediaId", mediaId)
		put("title", title)
		put("coverUrl", coverUrl)
		put("url", url)
		put("status", status)
		put("progress", progress)
		put("chapters", chapters)
		put("score", score.toDouble())
		put("notes", notes)
		put("updatedAt", updatedAt)
		put("localMangaId", localMangaId)
	}

	override fun areItemsTheSame(other: ListModel): Boolean =
		other is AniListLibraryEntry && other.mediaId == mediaId

	override fun getChangePayload(previousState: ListModel): Any? = null
}

internal object AniListLibraryFilter {

	fun apply(
		entries: List<AniListLibraryEntry>,
		status: String?,
		query: String,
		sortByTitle: Boolean,
	): List<AniListLibraryEntry> = entries.asSequence()
		.filter { status == null || it.status == status }
		.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
		.let { filtered ->
			if (sortByTitle) filtered.sortedBy { it.title.lowercase() }
			else filtered.sortedByDescending { it.updatedAt }
		}
		.toList()

	fun count(entries: List<AniListLibraryEntry>, status: String?): Int =
		if (status == null) entries.size else entries.count { it.status == status }
}
