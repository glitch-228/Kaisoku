package org.koitharu.kotatsu.history.exact

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChapterReadDao {
    @Query("SELECT * FROM chapter_read_policy WHERE manga_id = :mangaId")
    abstract suspend fun policy(mangaId: Long): ChapterReadPolicyEntity?

    @Query("SELECT * FROM chapter_read_policy WHERE manga_id = :mangaId")
    abstract fun observePolicy(mangaId: Long): Flow<ChapterReadPolicyEntity?>

    @Query("SELECT * FROM chapter_read_policy")
    abstract suspend fun allPolicies(): List<ChapterReadPolicyEntity>

    @Query("SELECT p.* FROM chapter_read_policy p JOIN manga m ON m.manga_id=p.manga_id " +
        "WHERE p.manga_id IN (:ids) AND p.enabled=1 AND p.deleted=0 AND p.source=m.source " +
        "AND (p.edition_url=m.url OR p.edition_url=m.public_url)")
    abstract suspend fun activeRecommendationPolicies(ids: Collection<Long>): List<ChapterReadPolicyEntity>

    @Query("SELECT DISTINCT r.manga_id FROM chapter_read_record r JOIN chapter_read_policy p ON p.manga_id=r.manga_id " +
        "WHERE r.manga_id IN (:ids) AND r.generation=p.generation AND r.is_read=1 AND p.enabled=1 AND p.deleted=0")
    abstract suspend fun recommendationReadIds(ids: Collection<Long>): List<Long>

    @Query("SELECT * FROM chapter_read_record WHERE manga_id = :mangaId")
    abstract suspend fun records(mangaId: Long): List<ChapterReadRecordEntity>

    @Query("SELECT * FROM chapter_read_record WHERE manga_id = :mangaId")
    abstract fun observeRecords(mangaId: Long): Flow<List<ChapterReadRecordEntity>>

    @Query("SELECT * FROM chapter_read_alias WHERE manga_id = :mangaId")
    abstract suspend fun aliases(mangaId: Long): List<ChapterReadAliasEntity>

    @Query("SELECT * FROM chapter_read_alias WHERE manga_id = :mangaId")
    abstract fun observeAliases(mangaId: Long): Flow<List<ChapterReadAliasEntity>>

    @Query("SELECT * FROM chapter_read_clock WHERE id = 1")
    abstract suspend fun clock(): ChapterReadClockEntity?

    @Query("SELECT * FROM chapter_read_count WHERE manga_id = :mangaId")
    abstract suspend fun counts(mangaId: Long): List<ChapterReadCountEntity>

    @Query("SELECT c.* FROM chapter_read_count c JOIN chapter_read_policy p ON p.manga_id=c.manga_id AND p.progress_branch=c.branch_key JOIN manga m ON m.manga_id=p.manga_id AND m.source=p.source WHERE c.manga_id IN (:ids) AND p.enabled=1 AND p.deleted=0")
    abstract suspend fun activeCounts(ids: Collection<Long>): List<ChapterReadCountEntity>

    @Query("SELECT c.manga_id AS mangaId,c.branch_key AS branchKey,c.read_count AS readCount," +
        "c.total_count AS totalCount,c.list_revision AS listRevision," +
        "LENGTH(CAST(c.chapter_references AS BLOB)) AS manifestBytes FROM chapter_read_count c " +
        "JOIN chapter_read_policy p ON p.manga_id=c.manga_id AND p.progress_branch=c.branch_key " +
        "JOIN manga m ON m.manga_id=p.manga_id AND m.source=p.source " +
        "WHERE c.manga_id IN (:ids) AND p.enabled=1 AND p.deleted=0")
    abstract suspend fun recommendationCounts(ids: Collection<Long>): List<RecommendationReadCount>

    @Query("SELECT c.* FROM chapter_read_count c JOIN chapter_read_policy p ON p.manga_id=c.manga_id " +
        "AND p.progress_branch=c.branch_key WHERE c.manga_id=:mangaId AND c.branch_key=:branch " +
        "AND p.generation=:generation AND p.source=:source AND p.enabled=1 AND p.deleted=0 " +
        "AND LENGTH(CAST(c.chapter_references AS BLOB))<=:maxBytes LIMIT 1")
    abstract suspend fun recommendationManifest(mangaId: Long, generation: String, source: String,
        branch: String, maxBytes: Long): ChapterReadCountEntity?

    @Query("SELECT COUNT(*) AS rowCount,COALESCE(SUM(LENGTH(CAST(reference_id AS BLOB))+" +
        "LENGTH(CAST(generation AS BLOB))+LENGTH(CAST(source AS BLOB))+LENGTH(CAST(branch_key AS BLOB))+" +
        "LENGTH(CAST(original_url AS BLOB))+LENGTH(CAST(revision_actor AS BLOB))),0) AS textBytes " +
        "FROM chapter_read_record WHERE manga_id=:mangaId AND generation=:generation AND source=:source AND branch_key=:branch")
    abstract suspend fun recommendationRecordFootprint(mangaId: Long, generation: String, source: String,
        branch: String): RecommendationReadFootprint

    @Query("SELECT * FROM chapter_read_record WHERE manga_id=:mangaId AND generation=:generation " +
        "AND source=:source AND branch_key=:branch LIMIT :maxRows")
    abstract suspend fun recommendationRecords(mangaId: Long, generation: String, source: String,
        branch: String, maxRows: Int): List<ChapterReadRecordEntity>

    @Query("SELECT COUNT(*) AS rowCount,COALESCE(SUM(LENGTH(CAST(a.reference_id AS BLOB))+" +
        "LENGTH(CAST(a.source AS BLOB))+LENGTH(CAST(a.branch_key AS BLOB))+LENGTH(CAST(a.url AS BLOB))),0) AS textBytes " +
        "FROM chapter_read_alias a WHERE a.manga_id=:mangaId AND a.source=:source AND a.branch_key=:branch " +
        "AND EXISTS(SELECT 1 FROM chapter_read_record r WHERE r.manga_id=a.manga_id AND r.reference_id=a.reference_id " +
        "AND r.generation=:generation AND r.source=:source AND r.branch_key=:branch)")
    abstract suspend fun recommendationAliasFootprint(mangaId: Long, generation: String, source: String,
        branch: String): RecommendationReadFootprint

    @Query("SELECT a.* FROM chapter_read_alias a WHERE a.manga_id=:mangaId AND a.source=:source AND a.branch_key=:branch " +
        "AND EXISTS(SELECT 1 FROM chapter_read_record r WHERE r.manga_id=a.manga_id AND r.reference_id=a.reference_id " +
        "AND r.generation=:generation AND r.source=:source AND r.branch_key=:branch) LIMIT :maxRows")
    abstract suspend fun recommendationAliases(mangaId: Long, generation: String, source: String,
        branch: String, maxRows: Int): List<ChapterReadAliasEntity>

    @Query("SELECT manga_id FROM chapter_read_policy WHERE deleted=0")
    abstract suspend fun retainedMangaIds(): List<Long>

    @Query("DELETE FROM chapter_read_record WHERE manga_id=:mangaId AND generation!=:generation")
    abstract suspend fun pruneObsoleteRecords(mangaId: Long, generation: String)

    @Upsert abstract suspend fun put(policy: ChapterReadPolicyEntity)
    @Upsert abstract suspend fun put(records: List<ChapterReadRecordEntity>)
    @Upsert abstract suspend fun putAliases(aliases: List<ChapterReadAliasEntity>)
    @Upsert abstract suspend fun put(clock: ChapterReadClockEntity)
    @Upsert abstract suspend fun putCounts(counts: List<ChapterReadCountEntity>)
}
