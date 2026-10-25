package org.koitharu.kotatsu.backups.mihon

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Index
import kotlinx.serialization.Serializable

/** Account ownership is deliberately absent: these records cannot enter any outgoing tracker queue. */
@Serializable
@Entity(tableName = "tracker_import_pending", primaryKeys = ["import_id"], indices = [Index(value = ["manga_id"])])
data class PendingTrackerImportEntity(
    @ColumnInfo(name = "import_id") val importId: String,
    @ColumnInfo(name = "import_digest") val importDigest: String,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "edition_url") val editionUrl: String,
    @ColumnInfo(name = "provider_key") val provider: String,
    @ColumnInfo(name = "foreign_provider_id") val foreignProviderId: Int,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "library_id") val libraryId: String,
    @ColumnInfo(name = "raw_progress") val rawProgress: String,
    @ColumnInfo(name = "raw_status") val rawStatus: String,
    @ColumnInfo(name = "raw_score") val rawScore: String,
    @ColumnInfo(name = "accepted_at") val acceptedAt: Long,
    @ColumnInfo(name = "raw_payload", defaultValue = "'{}'") val rawPayload: String = "{}",
)

@Serializable
@Entity(tableName = "foreign_import_receipt", primaryKeys = ["import_digest", "manga_id"], indices = [Index(value = ["manga_id"])])
data class ForeignImportReceiptEntity(
    @ColumnInfo(name = "import_digest") val importDigest: String,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "edition_url") val editionUrl: String,
    @ColumnInfo(name = "accepted_at") val acceptedAt: Long,
)

@Dao
interface MihonImportDao {
    @Query("SELECT * FROM foreign_import_receipt WHERE import_digest=:digest AND manga_id=:mangaId")
    suspend fun receipt(digest: String, mangaId: Long): ForeignImportReceiptEntity?

    @Query("SELECT * FROM foreign_import_receipt ORDER BY import_digest,manga_id")
    suspend fun receipts(): List<ForeignImportReceiptEntity>

    @Query("SELECT * FROM tracker_import_pending ORDER BY import_id")
    suspend fun pending(): List<PendingTrackerImportEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(receipt: ForeignImportReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(records: List<PendingTrackerImportEntity>)

    @Upsert suspend fun restorePending(record: PendingTrackerImportEntity)
    @Upsert suspend fun restoreReceipt(receipt: ForeignImportReceiptEntity)
}
