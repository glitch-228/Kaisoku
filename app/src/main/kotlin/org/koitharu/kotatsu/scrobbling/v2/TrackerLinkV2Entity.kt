package org.koitharu.kotatsu.scrobbling.v2

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Root registers this additive table after the exact-completion migration. No legacy ID projection. */
@Entity(
    tableName = "tracker_links_v2",
    primaryKeys = ["provider_key", "account_id", "manga_id"],
    indices = [Index(value = ["provider_key", "account_id", "media_id"])],
)
data class TrackerLinkV2Entity(
    @ColumnInfo(name = "provider_key") val provider: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "list_entry_id") val listEntryId: String?,
    @ColumnInfo(name = "progress_number") val progressNumber: String?,
    @ColumnInfo(name = "progress_volume") val progressVolume: String?,
    @ColumnInfo(name = "progress_entry_id") val progressEntryId: String?,
    @ColumnInfo(name = "progress_unit") val progressUnit: String?,
    @ColumnInfo(name = "remote_status") val remoteStatus: String?,
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: String?,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "deleted", defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(name = "owner_source", defaultValue = "''") val source: String = "",
    @ColumnInfo(name = "edition_url", defaultValue = "''") val editionUrl: String = "",
    @ColumnInfo(name = "revision_wall", defaultValue = "0") val revisionWall: Long = 0,
    @ColumnInfo(name = "revision_logical", defaultValue = "0") val revisionLogical: Long = 0,
    @ColumnInfo(name = "revision_actor", defaultValue = "''") val revisionActor: String = "",
    /** Local approval is deliberately absent from every transfer format. */
    @ColumnInfo(name = "confirmed", defaultValue = "1") val confirmed: Boolean = true,
) {
    val causalRevision: org.koitharu.kotatsu.history.exact.ReadRevision?
        get() = revisionActor.takeIf { it.isNotEmpty() }?.let {
            org.koitharu.kotatsu.history.exact.ReadRevision(revisionWall, revisionLogical, it)
        }

    fun reference() = TrackerReference(TrackerOwner(provider, accountId), mediaId, listEntryId)

    fun progress(): TrackerRawProgress? = progressUnit?.let {
        TrackerRawProgress(progressNumber, progressVolume, progressEntryId, it)
    }

    companion object {
        fun linked(mangaId: Long, entry: TrackerRemoteEntry, revision: Long) = TrackerLinkV2Entity(
            provider = entry.reference.owner.provider,
            accountId = entry.reference.owner.accountId,
            mangaId = mangaId,
            mediaId = entry.reference.mediaId,
            listEntryId = entry.reference.listEntryId,
            progressNumber = entry.progress?.number,
            progressVolume = entry.progress?.volume,
            progressEntryId = entry.progress?.entryId,
            progressUnit = entry.progress?.unit,
            remoteStatus = entry.status,
            remoteUpdatedAt = entry.updatedAt,
            revision = revision,
        )
    }
}
