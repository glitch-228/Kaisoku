package org.koitharu.kotatsu.search.unified

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.model.SourceContentClass
import org.koitharu.kotatsu.core.model.SourceContentPolicy
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.search.domain.SearchTrackerAccount
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry

@Serializable
enum class CatalogueContent { SAFE, MIXED, ADULT, UNKNOWN }

fun CatalogueContent.allowed(policy: SourceContentPolicy): Boolean = when (this) {
    CatalogueContent.UNKNOWN -> if (policy.separateClasses)
        policy.selectedClasses.containsAll(SourceContentClass.entries) else !policy.legacyRestricted
    else -> policy.allows(SourceContentClass.valueOf(name), this != CatalogueContent.SAFE)
}

@Serializable
data class CatalogueTitle(
    val id: String,
    val title: String,
    val aliases: Set<String> = emptySet(),
    val cover: String? = null,
    val url: String,
    val format: String? = null,
    val content: CatalogueContent = CatalogueContent.UNKNOWN,
    val chapters: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 512 && title.isNotBlank() && title.length <= 2048)
        require(aliases.size <= 512 && aliases.all { it.length <= 2048 })
        require(url.length <= 16_384 && (cover == null || cover.length <= 16_384))
    }
    val isNovel: Boolean? get() = format?.lowercase()?.let {
        when (it) {
            "novel", "light_novel", "lightnovel", "web_novel" -> true
            "manga", "manhwa", "manhua", "one_shot", "oneshot", "doujin", "doujinshi", "oel" -> false
            else -> null
        }
    }
}

@Serializable
data class CataloguePage(val titles: List<CatalogueTitle>, val nextCursor: String?)

@Serializable
data class SavedTrackerTitle(
    val work: CatalogueTitle,
    val entryId: String?,
    val status: String?,
    val progress: String?,
    val progressUnit: String = "chapter",
    val volume: String? = null,
    val score: String? = null,
    val updatedAt: String? = null,
) {
    companion object {
        fun from(entry: TrackerRemoteEntry) = SavedTrackerTitle(
            CatalogueTitle(entry.reference.mediaId, entry.title.title, entry.title.aliases,
                entry.title.coverUrl, entry.title.url, entry.title.format,
                entry.title.contentClass?.let { runCatching { CatalogueContent.valueOf(it) }.getOrNull() }
                    ?: CatalogueContent.UNKNOWN, entry.total),
            entry.reference.listEntryId, entry.status, entry.progress?.number,
            entry.progress?.unit ?: "chapter", entry.progress?.volume, entry.rating, entry.updatedAt,
        )
    }
}

data class TrackerSearchCard(
    val account: SearchTrackerAccount,
    val work: CatalogueTitle,
    val saved: SavedTrackerTitle? = null,
    val editions: List<Manga> = emptyList(),
) : ListModel {
    override fun areItemsTheSame(other: ListModel): Boolean = other is TrackerSearchCard &&
        account.provider == other.account.provider && account.account == other.account.account && work.id == other.work.id
}

data class TrackerSearchSection(val title: Int, val cards: List<TrackerSearchCard>) : ListModel {
    override fun areItemsTheSame(other: ListModel): Boolean = other is TrackerSearchSection && title == other.title
}

data object CatalogueMore : ListModel {
    override fun areItemsTheSame(other: ListModel) = other === this
}

internal fun trackerQueryMatches(title: CatalogueTitle, query: String): Boolean = query.isBlank() ||
    (title.aliases + title.title).any { it.contains(query.trim(), ignoreCase = true) }
