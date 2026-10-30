package org.koitharu.kotatsu.scrobbling.v2.bulk

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.scrobbling.v2.TrackerConnection
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import org.koitharu.kotatsu.scrobbling.v2.TrackerTitle

@Serializable
data class BulkTrackerAccount(
    val provider: String,
    val account: String,
    val revision: Long,
    val scopes: Set<String> = emptySet(),
    val nickname: String? = null,
) {
    fun connection() = TrackerConnection(TrackerOwner(provider, account), revision, scopes, nickname)
    fun sameOwner(other: BulkTrackerAccount) = provider == other.provider && account == other.account
    companion object {
        fun from(value: TrackerConnection) = BulkTrackerAccount(value.owner.provider, value.owner.accountId,
            value.revision, value.scopes, value.nickname)
    }
}

/** A real favorite or a real result explicitly chosen from an enabled readable source. No source is invented. */
@Serializable
data class BulkTrackerLocal(
    val id: Long,
    val title: String,
    val aliases: Set<String>,
    val source: String,
    val url: String,
    val publicUrl: String,
    val format: String? = null,
    val language: String? = null,
    val contentRating: String? = null,
    val fromSearch: Boolean = false,
) {
    init {
        require(id != 0L && title.length <= 2048 && source.isNotBlank() && source.length <= 1024)
        require(url.length <= 8192 && publicUrl.length <= 8192 && aliases.size <= 32 && aliases.all { it.length <= 2048 })
        require(format == null || format.length <= 64)
        require(language == null || language.length <= 64)
        require(contentRating == null || contentRating.length <= 64)
    }
    fun sameEdition(other: BulkTrackerLocal) = id == other.id && source == other.source && url == other.url
}

@Serializable
data class BulkTrackerCandidate(val mediaId: String, val title: String, val aliases: Set<String>,
    val url: String, val format: String?) {
    init {
        require(mediaId.isNotBlank() && mediaId.length <= 4096 && title.length <= 2048 && url.length <= 8192)
        require(aliases.size <= 32 && aliases.all { it.length <= 2048 } && (format == null || format.length <= 128))
    }
    fun title() = TrackerTitle(mediaId, title, aliases, url = url, format = format)
    companion object {
        fun from(value: TrackerTitle) = BulkTrackerCandidate(value.mediaId, value.title, value.aliases,
            value.url, value.format)
    }
}

@Serializable
data class BulkTrackerRemote(val candidate: BulkTrackerCandidate, val entryId: String?, val status: String?,
    val chapter: String?, val volume: String?, val unit: String?, val progressEntry: String?) {
    init {
        require(listOf(entryId, status, chapter, volume, unit, progressEntry).all { it == null || it.length <= 4096 })
    }
    companion object {
        fun from(value: TrackerRemoteEntry) = BulkTrackerRemote(BulkTrackerCandidate.from(value.title),
            value.reference.listEntryId, value.status, value.progress?.number, value.progress?.volume,
            value.progress?.unit, value.progress?.entryId)
    }
}

@Serializable
data class BulkTrackerLink(val mediaId: String, val entryId: String?, val revision: String, val v2Revision: Long?,
    val confirmed: Boolean = true)

@Serializable
data class BulkTrackerCategory(val id: Long, val createdAt: Long, val title: String) {
    init { require(id > 0 && createdAt >= 0 && title.length <= 1024) }
}

@Serializable
enum class BulkTrackerItemState {
    UNRESOLVED, SUGGESTED, RETAINED, CONFIRMED, CREATING, READBACK_REQUIRED, READY_TO_LINK, APPLIED, FAILED, SKIPPED,
}

@Serializable
enum class BulkTrackerDirection { MATCH_FAVORITES, IMPORT_LIST }

@Serializable
data class BulkTrackerItem(
    val id: String,
    val local: BulkTrackerLocal?,
    val remote: BulkTrackerRemote? = null,
    val candidates: List<BulkTrackerCandidate> = emptyList(),
    val chosen: BulkTrackerCandidate? = null,
    val expectedLink: BulkTrackerLink? = null,
    val category: BulkTrackerCategory? = null,
    val state: BulkTrackerItemState = BulkTrackerItemState.UNRESOLVED,
    val allowCreate: Boolean = false,
    val error: String? = null,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && candidates.size <= 300)
        require(error == null || error.length <= 2048)
        require(!allowCreate || chosen != null)
    }
    val uncertain get() = state in setOf(BulkTrackerItemState.CREATING, BulkTrackerItemState.READBACK_REQUIRED)
    fun reference(owner: TrackerOwner): TrackerReference? = chosen?.let { TrackerReference(owner, it.mediaId, remote?.entryId) }
}

@Serializable
data class BulkTrackerDraft(
    val id: String,
    val account: BulkTrackerAccount,
    val direction: BulkTrackerDirection,
    val createdAt: Long,
    val items: List<BulkTrackerItem>,
    val error: String? = null,
    val retryAt: Long = 0,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && createdAt >= 0 && retryAt >= 0)
        require(items.size in 1..100 && items.map { it.id }.distinct().size == items.size)
        require(items.mapNotNull { it.local?.id }.distinct().size == items.count { it.local != null })
        require(error == null || error.length <= 2048)
    }
}

@Serializable
data class BulkTrackerLibrary(val account: BulkTrackerAccount, val entries: List<BulkTrackerRemote> = emptyList(),
    val nextCursor: String? = null, val loaded: Boolean = false, val complete: Boolean = false, val error: String? = null) {
    init {
        require(entries.size <= 20_000 && entries.map { it.candidate.mediaId }.distinct().size == entries.size)
        require(nextCursor == null || nextCursor.length <= 4096)
        require(error == null || error.length <= 2048)
    }
}

internal data class BulkTrackerPage(val entries: List<BulkTrackerRemote>, val nextCursor: String?)

internal interface BulkTrackerAccess {
    fun check(account: BulkTrackerAccount)
    suspend fun checkReadable(local: BulkTrackerLocal) = Unit
    suspend fun local(id: Long): BulkTrackerLocal?
    suspend fun link(account: BulkTrackerAccount, id: Long): BulkTrackerLink?
    suspend fun search(account: BulkTrackerAccount, query: String): List<BulkTrackerCandidate>
    suspend fun entry(account: BulkTrackerAccount, mediaId: String, beforeRequest: suspend () -> Unit): TrackerRemoteEntry?
    suspend fun createStatus(account: BulkTrackerAccount): String?
    suspend fun create(account: BulkTrackerAccount, mediaId: String, status: String, beforeSend: suspend () -> Unit)
    suspend fun commit(account: BulkTrackerAccount, item: BulkTrackerItem, entry: TrackerRemoteEntry)
    suspend fun page(account: BulkTrackerAccount, cursor: String?): BulkTrackerPage
}
