package org.koitharu.kotatsu.scrobbling.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TrackerLinkV2Dao {

    @Query("SELECT * FROM tracker_links_v2 WHERE provider_key = :provider AND account_id = :account AND manga_id = :manga")
    abstract suspend fun find(provider: String, account: String, manga: Long): TrackerLinkV2Entity?

    @Query("SELECT * FROM tracker_links_v2 WHERE provider_key = :provider AND account_id = :account AND deleted = 0 AND confirmed = 1")
    abstract fun observeActive(provider: String, account: String): Flow<List<TrackerLinkV2Entity>>

    @Query("SELECT * FROM tracker_links_v2 WHERE provider_key = :provider AND account_id = :account")
    abstract suspend fun records(provider: String, account: String): List<TrackerLinkV2Entity>

    @Query("SELECT * FROM tracker_links_v2 WHERE provider_key = :provider AND account_id = :account AND media_id = :media AND deleted = 0 AND confirmed = 1")
    abstract suspend fun linksForMedia(provider: String, account: String, media: String): List<TrackerLinkV2Entity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun upsert(record: TrackerLinkV2Entity)

    @Query("SELECT * FROM tracker_links_v2")
    internal abstract suspend fun dump(): List<TrackerLinkV2Entity>

    @Query("SELECT * FROM tracker_links_v2 WHERE provider_key=:provider AND manga_id=:manga")
    internal abstract suspend fun providerManga(provider: String, manga: Long): List<TrackerLinkV2Entity>

    /** Explicit local compare-and-set; remote replica conflict rules remain owned by the sync layer. */
    @Transaction
    open suspend fun saveIfCurrent(record: TrackerLinkV2Entity, expectedRevision: Long?): Boolean {
        record.reference() // Validate exact external identities before persistence.
        require(record.mangaId != 0L && record.revision > 0)
        val current = find(record.provider, record.accountId, record.mangaId)
        if (current?.revision != expectedRevision) return false
        require(record.revision > (current?.revision ?: 0)) { "Tracker link revision must advance" }
        upsert(record)
        return true
    }

}
