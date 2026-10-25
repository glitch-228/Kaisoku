package org.koitharu.kotatsu.sync.extended

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import org.koitharu.kotatsu.history.exact.ReadRevision
import javax.inject.Inject

internal val METADATA_FIELDS = setOf("title", "description", "cover", "content_rating")

/** No foreign key: a deliberate title removal must not erase its field-reset evidence. */
@Entity(tableName = "metadata_override_state", primaryKeys = ["manga_id", "source", "edition_url", "field_key"])
data class MetadataOverrideState(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "edition_url") val editionUrl: String,
    @ColumnInfo(name = "field_key") val field: String,
    @ColumnInfo(name = "field_value") val value: String?,
    @ColumnInfo(name = "asset_sha256") val assetSha256: String? = null,
    @ColumnInfo(name = "revision_wall") val revisionWall: Long,
    @ColumnInfo(name = "revision_logical") val revisionLogical: Long,
    @ColumnInfo(name = "revision_actor") val revisionActor: String,
) {
    val revision: ReadRevision get() = ReadRevision(revisionWall, revisionLogical, revisionActor)
}

@Dao
abstract class MetadataOverrideDao {
    @Query("SELECT * FROM metadata_override_state ORDER BY source,edition_url,field_key")
    abstract suspend fun dump(): List<MetadataOverrideState>

    @Query("SELECT * FROM metadata_override_state WHERE source=:source AND edition_url=:edition")
    abstract suspend fun edition(source: String, edition: String): List<MetadataOverrideState>

    @Upsert internal abstract suspend fun put(rows: List<MetadataOverrideState>)
}

/** Local metadata edits and legacy overlays share the main transaction; no reader or remote writes. */
class MetadataOverrideJournal @Inject constructor(private val db: MangaDatabase) {
    private val clock = ChapterReadRepository(db)

    suspend fun record(manga: MangaEntity, preferences: MangaPrefsEntity, explicitReset: Boolean = false) =
        db.withTransaction {
            require(manga.id == preferences.mangaId && manga.source.isNotBlank() && manga.url.isNotBlank())
            val dao = db.getMetadataOverrideDao()
            val old = dao.edition(manga.source, manga.url).filter { it.mangaId == manga.id }.associateBy { it.field }
            val changed = preferences.metadataFields().filter { (field, value) ->
                old[field]?.value?.let { it != value } ?: when {
                    old.containsKey(field) -> value != null
                    else -> value != null || explicitReset
                }
            }
            if (changed.isEmpty()) return@withTransaction
            val revision = clock.nextRevision(old.values.maxOfOrNull { it.revision })
            dao.put(changed.map { (field, value) -> MetadataOverrideState(manga.id, manga.source, manga.url, field,
                value, null, revision.wall, revision.logical, revision.actor) })
        }

    /** Old clients can still sync mode/color settings without resurrecting superseded metadata. */
    suspend fun overlayOwned() = db.withTransaction {
        val dao = db.getPreferencesDao()
        for ((identity, fields) in db.getMetadataOverrideDao().dump().groupBy { it.source to it.editionUrl }) {
            val matches = db.getMangaDao().findForExactIdentity(identity.first, identity.second)
            if (matches.size != 1 || fields.map { it.field }.distinct().size != fields.size) continue
            val manga = matches.single().manga
            val before = dao.find(manga.id) ?: emptyMetadataPreferences(manga.id)
            val after = fields.fold(before) { value, field -> value.withMetadataField(field.field, field.value) }
            if (after != before) dao.upsert(after)
        }
    }
}

internal fun MangaPrefsEntity.metadataFields(): Map<String, String?> = linkedMapOf(
    "title" to titleOverride, "description" to descriptionOverride, "cover" to coverUrlOverride,
    "content_rating" to contentRatingOverride,
)

internal fun MangaPrefsEntity.withMetadataField(field: String, value: String?): MangaPrefsEntity = when (field) {
    "title" -> copy(titleOverride = value)
    "description" -> copy(descriptionOverride = value)
    "cover" -> copy(coverUrlOverride = value)
    "content_rating" -> copy(contentRatingOverride = value)
    else -> error("Unsupported metadata field")
}

internal fun emptyMetadataPreferences(id: Long) = MangaPrefsEntity(id, -1, 0f, 0f, false, false, false, null, null, null)
