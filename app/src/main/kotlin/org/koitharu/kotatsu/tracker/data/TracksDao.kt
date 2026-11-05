package org.koitharu.kotatsu.tracker.data

import android.database.DatabaseUtils
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.MangaQueryBuilder
import org.koitharu.kotatsu.list.domain.ListFilterOption

@Dao
abstract class TracksDao : MangaQueryBuilder.ConditionCallback {

    /**
     * Periodic tracker scope: skip tracks whose only history/favourite touch is older than
     * [minActivityTime] AND whose last known chapter is older too. Pass `Long.MIN_VALUE` to bypass
     * the filter (e.g. one-shot migrations that must see every row). `last_check_time = 0` keeps
     * brand-new tracks visible on the first run.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM tracks
        WHERE last_check_time = 0
            OR last_check_time <= :staleCheckTime
            OR last_chapter_date = 0
            OR last_chapter_date >= :minActivityTime
            OR EXISTS(
                SELECT 1 FROM history
                WHERE history.manga_id = tracks.manga_id
                    AND history.deleted_at = 0
                    AND history.updated_at >= :minActivityTime
            )
            OR EXISTS(
                SELECT 1 FROM favourites
                WHERE favourites.manga_id = tracks.manga_id
                    AND favourites.deleted_at = 0
                    AND favourites.created_at >= :minActivityTime
            )
        ORDER BY last_check_time ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    abstract suspend fun findAll(
        offset: Int,
        limit: Int,
        minActivityTime: Long,
        staleCheckTime: Long,
    ): List<TrackWithManga>

    @Transaction
    @Query("SELECT * FROM tracks ORDER BY last_check_time DESC")
    abstract fun observeAll(): Flow<List<TrackWithManga>>

    @Query("SELECT manga_id FROM tracks")
    abstract suspend fun findAllIds(): LongArray

    @Transaction
    @Query("SELECT * FROM tracks WHERE last_chapter_date BETWEEN :minChapterDate AND :maxChapterDate")
    abstract suspend fun findByLastChapterDate(minChapterDate: Long, maxChapterDate: Long): List<TrackWithManga>

    @Query("SELECT manga_id FROM tracks WHERE last_chapter_date > 0 AND last_chapter_date <= :maxChapterDate")
    abstract suspend fun findIdsWithLastChapterBefore(maxChapterDate: Long): LongArray

    @Query("SELECT * FROM tracks")
    abstract suspend fun dump(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE manga_id = :mangaId")
    abstract suspend fun find(mangaId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE manga_id IN (:mangaIds)")
    abstract suspend fun findAllByIds(mangaIds: LongArray): List<TrackEntity>

    @Query("SELECT IFNULL(chapters_new,0) FROM tracks WHERE manga_id = :mangaId")
    abstract suspend fun findNewChapters(mangaId: Long): Int

    @Query("SELECT COUNT(*) FROM tracks")
    abstract suspend fun getTracksCount(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE chapters_new > 0")
    abstract fun observeUpdateMangaCount(): Flow<Int>

    @Query("SELECT IFNULL(chapters_new, 0) FROM tracks WHERE manga_id = :mangaId")
    abstract fun observeNewChapters(mangaId: Long): Flow<Int>

    @Transaction
    @Query("SELECT * FROM tracks WHERE chapters_new > 0 ORDER BY last_chapter_date DESC")
    abstract fun observeUpdatedManga(): Flow<List<MangaWithTrack>>

    fun observeUpdatedManga(
        limit: Int,
        filterOptions: Set<ListFilterOption>,
        unreadOnly: Boolean = false,
    ): Flow<List<MangaWithTrack>> = observeMangaImpl(
        MangaQueryBuilder("tracks", this)
            .where("chapters_new > 0")
            .apply {
                if (unreadOnly) where("EXISTS(SELECT 1 FROM track_logs WHERE track_logs.manga_id = tracks.manga_id AND track_logs.unread = 1)")
            }
            .filters(filterOptions)
            .limit(limit)
            .orderBy("last_chapter_date DESC")
            .build(),
    )

    @Query("DELETE FROM tracks")
    abstract suspend fun clear()

    @Query(
        "UPDATE tracks SET chapters_new = 0, last_chapter_id = COALESCE(" +
            "(SELECT chapter_id FROM history WHERE history.manga_id = tracks.manga_id AND history.deleted_at = 0), 0)",
    )
    abstract suspend fun clearCounters()

    @Query("UPDATE tracks SET chapters_new = 0 WHERE manga_id = :mangaId")
    abstract suspend fun clearCounter(mangaId: Long)

    /** Zero the new-chapter badge on tracks whose last seen chapter is older than [minChapterDate]. */
    @Query("UPDATE tracks SET chapters_new = 0 WHERE chapters_new > 0 AND last_chapter_date > 0 AND last_chapter_date < :minChapterDate")
    abstract suspend fun clearStaleCounters(minChapterDate: Long)

    @Query("DELETE FROM tracks WHERE manga_id = :mangaId")
    abstract suspend fun delete(mangaId: Long)

    @Query("DELETE FROM tracks WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE history.deleted_at = 0 UNION SELECT manga_id FROM favourites WHERE favourites.deleted_at = 0 AND category_id IN (SELECT category_id FROM favourite_categories WHERE favourite_categories.deleted_at = 0 AND track = 1))")
    abstract suspend fun gc()

    @Upsert
    abstract suspend fun upsert(entity: TrackEntity)

    @Transaction
    @RawQuery(observedEntities = [TrackEntity::class, TrackLogEntity::class])
    protected abstract fun observeMangaImpl(query: SupportSQLiteQuery): Flow<List<MangaWithTrack>>

    override fun getCondition(option: ListFilterOption): String? = when (option) {
        ListFilterOption.Macro.FAVORITE -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = tracks.manga_id)"
        is ListFilterOption.Favorite -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = tracks.manga_id AND favourites.category_id = ${option.category.id})"
        is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE manga_tags.manga_id = tracks.manga_id AND tag_id = ${option.tagId})"
        ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga WHERE manga.manga_id = tracks.manga_id) = 1"
        is ListFilterOption.State -> option.state?.let {
            "(SELECT state FROM manga WHERE manga.manga_id = tracks.manga_id) = ${DatabaseUtils.sqlEscapeString(it.name)}"
        }
        else -> null
    }
}
