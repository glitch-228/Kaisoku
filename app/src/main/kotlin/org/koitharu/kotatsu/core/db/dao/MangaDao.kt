package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaTagsEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.db.entity.TagEntity

data class LibrarySourceUsage(
    val source: String,
    val mangaCount: Int,
)

@Dao
abstract class MangaDao {

    @Transaction
    @Query("""
        SELECT manga.*,
            EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = manga.manga_id AND f.deleted_at = 0) AS favorite,
            COALESCE(h.updated_at, 0) AS lastRead,
            COALESCE((SELECT MIN(36000000, SUM(CAST(MAX(0, MIN(duration, 36000000)) AS REAL)))
                FROM stats s WHERE s.manga_id = manga.manga_id), 0) AS durationMillis,
            COALESCE(h.percent, 0) AS progress,
            COALESCE(h.chapters, 0) AS historyChapters
        FROM manga LEFT JOIN history h ON h.manga_id = manga.manga_id AND h.deleted_at = 0
        WHERE h.manga_id IS NOT NULL OR
            EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = manga.manga_id AND f.deleted_at = 0)
        ORDER BY favorite DESC, lastRead DESC, manga.manga_id LIMIT 1000
    """)
    abstract fun recommendationTaste(): kotlinx.coroutines.flow.Flow<List<org.koitharu.kotatsu.suggestions.local.RecommendationTasteRow>>

    @Transaction
    @Query("SELECT * FROM manga WHERE manga_id = :id")
    abstract suspend fun find(id: Long): MangaWithTags?

    @Transaction
    @Query("SELECT * FROM manga WHERE manga_id IN (:ids)")
    abstract suspend fun findAll(ids: LongArray): List<MangaWithTags>

    @Query("SELECT EXISTS(SELECT * FROM manga WHERE manga_id = :id)")
    abstract suspend operator fun contains(id: Long): Boolean

    @Transaction
    @Query("SELECT * FROM manga WHERE public_url = :publicUrl")
    abstract suspend fun findByPublicUrl(publicUrl: String): MangaWithTags?

    @Transaction
    @Query(
        """
        SELECT * FROM manga
        WHERE source = :source AND (url = :url OR public_url = :publicUrl)
        ORDER BY CASE WHEN url = :url THEN 0 ELSE 1 END
        LIMIT 1
        """,
    )
    abstract suspend fun findByIdentity(source: String, url: String, publicUrl: String): MangaWithTags?

    @Transaction
    @Query("SELECT * FROM manga WHERE source=:source AND (url=:url OR public_url=:url)")
    abstract suspend fun findForExactIdentity(source: String, url: String): List<MangaWithTags>

    @Transaction
    @Query("SELECT * FROM manga WHERE source = :source")
    abstract suspend fun findAllBySource(source: String): List<MangaWithTags>

    @Transaction
    @Query("SELECT * FROM manga WHERE source = :source AND manga_id IN (SELECT manga_id FROM favourites)")
    abstract suspend fun findLibraryBySource(source: String): List<MangaWithTags>

    @Query(
        """
        SELECT source, COUNT(*) AS mangaCount
        FROM manga
        WHERE source NOT IN ('LOCAL', 'UNKNOWN')
            AND manga_id IN (
                SELECT manga_id FROM favourites WHERE deleted_at = 0
                UNION SELECT manga_id FROM history WHERE deleted_at = 0
            )
        GROUP BY source
        ORDER BY source COLLATE NOCASE
        """,
    )
    abstract suspend fun findLibrarySourceUsage(): List<LibrarySourceUsage>

    @Query(
        """
        SELECT manga_id FROM manga
        WHERE source IN (:sources)
            AND manga_id IN (
                SELECT manga_id FROM favourites WHERE deleted_at = 0
                UNION SELECT manga_id FROM history WHERE deleted_at = 0
            )
        """,
    )
    abstract suspend fun findLibraryMangaIdsBySources(sources: Collection<String>): List<Long>

    @Query("SELECT author FROM manga WHERE author LIKE :query GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
    abstract suspend fun findAuthors(query: String, limit: Int): List<String>

    @Query("SELECT author FROM manga WHERE manga.source = :source AND author IS NOT NULL AND author != '' GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
    abstract suspend fun findAuthorsBySource(source: String, limit: Int): List<String>

    @Transaction
    @Query("SELECT * FROM manga WHERE (title LIKE :query OR alt_title LIKE :query) AND manga_id IN (SELECT manga_id FROM favourites WHERE deleted_at = 0 UNION SELECT manga_id FROM history WHERE deleted_at = 0) LIMIT :limit")
    abstract suspend fun searchByTitle(query: String, limit: Int): List<MangaWithTags>

    @Transaction
    @Query("SELECT * FROM manga WHERE (title LIKE :query OR alt_title LIKE :query) AND source = :source AND manga_id IN (SELECT manga_id FROM favourites WHERE deleted_at = 0 UNION SELECT manga_id FROM history WHERE deleted_at = 0) LIMIT :limit")
    abstract suspend fun searchByTitle(query: String, source: String, limit: Int): List<MangaWithTags>

    @Upsert
    protected abstract suspend fun upsert(manga: MangaEntity)

    @Update(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun update(manga: MangaEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTagRelation(tag: MangaTagsEntity): Long

    @Query("DELETE FROM manga_tags WHERE manga_id = :mangaId")
    abstract suspend fun clearTagRelation(mangaId: Long)

    @Transaction
    @Delete
    abstract suspend fun delete(subjects: Collection<MangaEntity>)

    @Query(
        """
        DELETE FROM manga WHERE NOT EXISTS(SELECT * FROM history WHERE history.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM favourites WHERE favourites.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM bookmarks WHERE bookmarks.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM suggestions WHERE suggestions.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM scrobblings WHERE scrobblings.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM local_index WHERE local_index.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM tracker_links_v2 WHERE tracker_links_v2.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM tracker_import_pending WHERE tracker_import_pending.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM foreign_import_receipt WHERE foreign_import_receipt.manga_id == manga.manga_id)
            AND NOT EXISTS(SELECT * FROM metadata_override_state WHERE metadata_override_state.manga_id == manga.manga_id)
            AND manga.manga_id NOT IN (:idsToKeep)
        """,
    )
    abstract suspend fun cleanup(idsToKeep: Set<Long>)

    @Transaction
    open suspend fun upsert(manga: MangaEntity, tags: Iterable<TagEntity>? = null) {
        upsert(manga)
        if (tags != null) {
            clearTagRelation(manga.id)
            tags.map {
                MangaTagsEntity(manga.id, it.id)
            }.forEach {
                insertTagRelation(it)
            }
        }
    }
}
