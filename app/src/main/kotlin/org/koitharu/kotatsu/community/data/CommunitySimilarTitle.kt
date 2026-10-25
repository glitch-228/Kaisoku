package org.koitharu.kotatsu.community.data

import org.json.JSONObject
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.suggestions.local.RecommendationRanker
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.remote.library.RemoteLibrarySource

data class CommunitySimilarTitle(
    val workId: String,
    val title: String,
    val isNovel: Boolean,
    val nsfw: Boolean?,
    val genres: Set<String>,
)

internal fun parseCommunitySimilarTitles(response: JSONObject): List<CommunitySimilarTitle> {
    val rows = response.optJSONArray("similar") ?: return emptyList()
    return (0 until minOf(rows.length(), 50)).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString("work_id").takeIf { it.isNotBlank() && it != "null" && it.length <= 256 } ?: return@mapNotNull null
        val title = row.optString("title").trim().takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val type = row.optString("content_type").lowercase(java.util.Locale.ROOT)
        if (type !in setOf("manga", "manhwa", "manhua", "comic", "novel", "light_novel", "web_novel")) return@mapNotNull null
        val genres = row.optJSONArray("genres")
        CommunitySimilarTitle(id, title.take(512), type in setOf("novel", "light_novel", "web_novel"),
            row.opt("nsfw") as? Boolean,
            (0 until minOf(genres?.length() ?: 0, 100)).map { genres!!.optString(it).take(128) }.toSet())
    }.distinctBy { it.workId }
}

internal fun CommunitySimilarTitle.allowed(settings: AppSettings, novel: Boolean): Boolean {
    if (isNovel != novel) return false
    if ((settings.isNsfwContentDisabled || settings.isSuggestionsExcludeNsfw) && nsfw != false) return false
    val blocked = settings.suggestionsTagsBlacklist.map(RecommendationRanker::normalize).toSet()
    return genres.none { RecommendationRanker.normalize(it) in blocked }
}

/** Private server/local identifiers have no public Community lookup contract. */
internal val Manga.isCommunitySimilarSeed: Boolean
    get() = source.name != LocalMangaSource.name && !source.name.startsWith(RemoteLibrarySource.PREFIX)
