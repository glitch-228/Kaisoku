package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.history.exact.ReadRevision
import org.koitharu.kotatsu.core.util.BoundedJson

/** Wire identity is the exact provider/account/source edition, never the receiving device's row ID. */
@Serializable
data class TrackerLinkReplica(
    val mangaId: Long,
    val provider: String,
    val accountId: String,
    val source: String,
    val editionUrl: String,
    val mediaId: String,
    val listEntryId: String? = null,
    val progressNumber: String? = null,
    val progressVolume: String? = null,
    val progressEntryId: String? = null,
    val progressUnit: String? = null,
    val status: String? = null,
    val remoteUpdatedAt: String? = null,
    val deleted: Boolean = false,
    val wall: Long,
    val logical: Long,
    val actor: String,
    val manga: MangaBackup? = null,
) {
    val causalRevision: ReadRevision get() = ReadRevision(wall, logical, actor)
    val key: List<String> get() = listOf(provider, accountId, source, editionUrl)

    fun validate() {
        require(mangaId != 0L && source.isNotBlank() && source.length <= 1024 &&
            editionUrl.isNotBlank() && editionUrl.length <= 8192)
        TrackerReference(TrackerOwner(provider, accountId), mediaId, listEntryId)
        require(progressUnit != null || listOf(progressNumber, progressVolume, progressEntryId).all { it == null })
        progressUnit?.let { TrackerRawProgress(progressNumber, progressVolume, progressEntryId, it) }
        require(status == null || status.length <= 512)
        require(remoteUpdatedAt == null || remoteUpdatedAt.length <= 512)
        causalRevision
        manga?.let {
            require(it.id == mangaId && it.source == source && (it.url == editionUrl || it.publicUrl == editionUrl)) {
                "Tracker title metadata belongs to a different source edition"
            }
            require(it.title.length <= 2048 && (it.altTitles?.length ?: 0) <= 16_384 &&
                (it.authors?.length ?: 0) <= 16_384 && it.url.length <= 8192 && it.publicUrl.length <= 8192 &&
                it.coverUrl.length <= 8192 && (it.largeCoverUrl?.length ?: 0) <= 8192 && it.tags.size <= 256)
            require(it.tags.all { tag -> tag.source == source && tag.title.length <= 2048 && tag.key.length <= 4096 })
        }
    }

    /** Row numbers and display metadata are not part of a causal link value. */
    fun sameValue(other: TrackerLinkReplica): Boolean = copy(mangaId = 1, manga = null) ==
        other.copy(mangaId = 1, manga = null)

    fun entity(targetId: Long, localRevision: Long, confirmed: Boolean) = TrackerLinkV2Entity(
        provider, accountId, targetId, mediaId, listEntryId, progressNumber, progressVolume, progressEntryId,
        progressUnit, status, remoteUpdatedAt, localRevision, deleted, source, editionUrl,
        wall, logical, actor, confirmed,
    )
}

@Serializable
data class TrackerLinkEnvelope(val version: Int = 1, val links: List<TrackerLinkReplica>) {
    fun validate() {
        require(version == 1) { "Unsupported tracker-link format $version" }
        require(links.size <= TrackerLinkTransfer.MAX_LINKS) { "Too many tracker links" }
        val identities = hashSetOf<List<String>>()
        for (link in links) {
            link.validate()
            require(identities.add(link.key)) { "Duplicate tracker source edition" }
        }
    }
}

object TrackerLinkTransfer {
    const val MAX_LINKS = 20_000
    const val MAX_BYTES = 16 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }

    fun encode(value: TrackerLinkEnvelope): String {
        value.validate()
        return json.encodeToString(value.copy(links = value.links.sortedWith(
            compareBy({ it.provider }, { it.accountId }, { it.source }, { it.editionUrl }),
        ))).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Tracker links are too large" } }
    }

    fun decode(bytes: ByteArray): TrackerLinkEnvelope {
        require(bytes.size <= MAX_BYTES) { "Tracker links are too large" }
        return json.decodeFromString<TrackerLinkEnvelope>(BoundedJson.validateObject(bytes, MAX_BYTES))
            .also { it.validate() }
    }

    fun replica(value: TrackerLinkV2Entity, manga: MangaBackup? = null) = TrackerLinkReplica(
        value.mangaId, value.provider, value.accountId, value.source, value.editionUrl, value.mediaId,
        value.listEntryId, value.progressNumber, value.progressVolume, value.progressEntryId, value.progressUnit,
        value.remoteStatus, value.remoteUpdatedAt, value.deleted,
        value.revisionWall, value.revisionLogical, value.revisionActor, manga,
    ).also { it.validate() }

    fun newer(local: TrackerLinkReplica?, incoming: TrackerLinkReplica): TrackerLinkReplica {
        incoming.validate()
        if (local == null) return incoming
        local.validate()
        require(local.key == incoming.key) { "Tracker link belongs to another source edition" }
        return when {
            local.causalRevision > incoming.causalRevision -> local
            incoming.causalRevision > local.causalRevision -> incoming
            local.sameValue(incoming) -> local
            else -> error("Conflicting tracker link versions; review this transfer")
        }
    }
}
