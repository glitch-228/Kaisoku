package org.koitharu.kotatsu.suggestions.local

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.history.exact.ChapterReadResolver
import org.koitharu.kotatsu.history.exact.ChapterReadTransfer
import org.koitharu.kotatsu.history.exact.decodeReadBranch
import org.koitharu.kotatsu.history.exact.RecommendationReadFootprint

/** Local evidence only: imports neither create reading sessions nor earn release credit. */
internal class RecommendationExactProgress(private val library: MangaDatabase) {
    suspend fun read(ids: Collection<Long>, baselines: Map<String, ChapterBaseline?>): Map<Long, RecommendationExactReading> =
        library.withTransaction {
            val dao = library.getChapterReadDao()
            val chunks = ids.distinct().chunked(400)
            val policies = chunks.flatMap { dao.activeRecommendationPolicies(it) }
            val readIds = chunks.flatMap { dao.recommendationReadIds(it) }.toSet()
            val counts = chunks.flatMap { dao.recommendationCounts(it) }.associateBy { it.mangaId }
            val budget = RecommendationExactBudget()
            policies.associate { policy ->
                val count = counts[policy.mangaId]?.takeIf { it.branchKey == policy.progressBranch &&
                    it.totalCount >= 0 && it.readCount in 0..it.totalCount }
                val baseline = baselines["source:${policy.source}:${policy.mangaId}"]
                var unreadReleases: Set<Long>? = null
                if (count != null && budget.canInspect(count.totalCount, count.manifestBytes) &&
                    baseline?.confirmedReadingBoundary == true && baseline.releaseChapterIds.isNotEmpty() &&
                    baseline.releaseIdentityVersion == 1 && baseline.exactGeneration == policy.generation &&
                    baseline.branch == decodeReadBranch(policy.progressBranch) && baseline.total == count.totalCount &&
                    baseline.chapterListIdentity.isNotEmpty() && baseline.chapterListIdentity == count.listRevision) {
                    val recordsSize = dao.recommendationRecordFootprint(policy.mangaId, policy.generation, policy.source, policy.progressBranch)
                    val aliasesSize = dao.recommendationAliasFootprint(policy.mangaId, policy.generation, policy.source, policy.progressBranch)
                    val manifest = if (budget.admit(count.totalCount, count.manifestBytes, recordsSize, aliasesSize)) {
                        dao.recommendationManifest(policy.mangaId, policy.generation, policy.source, policy.progressBranch,
                            RecommendationExactBudget.TITLE_TEXT_BYTES)
                    } else null
                    val references = try { manifest?.let(ChapterReadTransfer::references) } catch (_: IllegalArgumentException) { null }
                    if (references != null && references.size == count.totalCount &&
                        references.all { it.source == policy.source && it.branchKey == policy.progressBranch } &&
                        references.distinctBy { it.chapterId }.size == references.size &&
                        ChapterReadTransfer.listRevision(references) == count.listRevision) {
                        val records = dao.recommendationRecords(policy.mangaId, policy.generation, policy.source,
                            policy.progressBranch, recordsSize.rowCount.toInt())
                        val aliases = dao.recommendationAliases(policy.mangaId, policy.generation, policy.source,
                            policy.progressBranch, aliasesSize.rowCount.toInt())
                        if (records.size.toLong() == recordsSize.rowCount && aliases.size.toLong() == aliasesSize.rowCount) {
                            val resolved = ChapterReadResolver.resolve(policy.generation, references,
                                records.map { it.toState() }, aliases.map { it.toAlias() })
                            unreadReleases = references.asSequence().map { it.chapterId }
                                .filter { it in baseline.releaseChapterIds && it !in resolved.readChapterIds }.toSet()
                        }
                    }
                }
                policy.mangaId to RecommendationExactReading(count?.readCount, count?.totalCount, policy.generation,
                    decodeReadBranch(policy.progressBranch), policy.mangaId in readIds, unreadReleases)
            }
        }
}

/** Admission precedes every large SELECT; byte counts are computed by SQLite, not after allocation. */
internal class RecommendationExactBudget {
    private var remainingReferences = 200_000
    private var remainingTextBytes = 8L * 1024 * 1024

    fun canInspect(references: Int, manifestBytes: Long): Boolean = references in 1..50_000 &&
        references <= remainingReferences && manifestBytes in 1..TITLE_TEXT_BYTES && manifestBytes <= remainingTextBytes

    fun admit(references: Int, manifestBytes: Long, records: RecommendationReadFootprint,
              aliases: RecommendationReadFootprint): Boolean {
        if (!canInspect(references, manifestBytes) || records.rowCount !in 0..MAX_OBJECTS ||
            aliases.rowCount !in 0..MAX_OBJECTS || records.textBytes !in 0..TITLE_TEXT_BYTES ||
            aliases.textBytes !in 0..TITLE_TEXT_BYTES) return false
        val objects = references.toLong() + records.rowCount + aliases.rowCount
        val bytes = manifestBytes + records.textBytes + aliases.textBytes
        if (objects > MAX_OBJECTS || bytes > TITLE_TEXT_BYTES || bytes > remainingTextBytes) return false
        remainingReferences -= references
        remainingTextBytes -= bytes
        return true
    }

    companion object {
        const val TITLE_TEXT_BYTES = 1024L * 1024
        private const val MAX_OBJECTS = 100_000L
    }
}
