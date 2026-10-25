package org.koitharu.kotatsu.suggestions.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationStore internal constructor(val database: RecommendationDatabase, private val library: MangaDatabase) {
    @Inject constructor(@ApplicationContext context: Context, library: MangaDatabase) :
        this(Room.databaseBuilder(context, RecommendationDatabase::class.java, "recommendations.db").build(), library)
    val dao get() = database.dao()
    val changes = MutableStateFlow(0L)
    private val mutex = Mutex()

    fun changed() { changes.update { it + 1 } }

    suspend fun claimRefresh(key: String, force: Boolean, now: Long, versions: Map<String, String> = emptyMap()): Boolean = database.withTransaction {
        val row = dao.result(key)
        // Real adapter/source changes can invalidate cached URLs; ordinary filters cannot.
        val sourceChanged = if (row == null || versions.isEmpty()) false else {
            val keys = runCatching { org.json.JSONArray(row.payload).let { array ->
                (0 until array.length()).map { array.getString(it) }
            } }.getOrDefault(emptyList())
            dao.candidates(keys).any { candidate ->
                val title = runCatching { RecommendationCodec.decode(org.json.JSONObject(candidate.payload)) }.getOrNull()
                title?.takeIf { it.target is RecommendationTarget.SourceTitle }?.manga?.source?.name?.let { source ->
                    versions[source]?.let { it != candidate.sourceVersion }
                } == true
            }
        }
        val lastRefresh = if (sourceChanged) null else row?.refreshed ?: dao.cooldown("refreshed:$key")?.until
            ?.minus(RecommendationRefreshPolicy.INTERVAL)
        val retryKey = "refresh:$key"
        val retryUntil = dao.cooldown(retryKey)?.until ?: 0
        if (!RecommendationRefreshPolicy.shouldRefresh(lastRefresh, retryUntil, now, force)) false
        else {
            dao.cooldown(RecommendationCooldown(retryKey, now + RecommendationRefreshPolicy.RETRY_INTERVAL))
            true
        }
    }

    suspend fun complete(row: RecommendationResultEntity): Boolean {
        val keys = runCatching { org.json.JSONArray(row.payload).let { array ->
            (0 until array.length()).map { array.getString(it) }
        } }.getOrNull() ?: return false
        return dao.candidates(keys).size == keys.distinct().size
    }

    suspend fun results(row: RecommendationResultEntity?, versions: Map<String, String>? = null): List<Recommendation> {
        if (row == null) return emptyList()
        val keys = runCatching { org.json.JSONArray(row.payload).let { array ->
            (0 until array.length()).map { array.getString(it) }
        } }.getOrDefault(emptyList())
        val candidates = dao.candidates(keys).associateBy { it.key }
        return keys.mapNotNull { key -> candidates[key]?.let { row ->
            val value = runCatching { RecommendationCodec.decode(org.json.JSONObject(row.payload)) }.getOrNull()
            value?.takeIf { versions == null || it.target is RecommendationTarget.PublicTitle || versions[it.manga.source.name] == row.sourceVersion }
        } }
    }

    suspend fun cachedCandidates(versions: Map<String, String>, limit: Int = 500): List<Recommendation> =
        dao.candidates().asSequence().mapNotNull { row ->
            runCatching { RecommendationCodec.decode(org.json.JSONObject(row.payload)) }.getOrNull()
                ?.takeIf { it.target is RecommendationTarget.SourceTitle && versions[it.manga.source.name] == row.sourceVersion }
                ?.copy(provenance = RecommendationProvenance.CACHE, reason = RecommendationReason.DISCOVERY, because = null)
        }.take(limit).toList()

    suspend fun recordChapters(manga: Manga, reading: Boolean = false, chapterId: Long? = null) {
        if (manga.chapters.isNullOrEmpty()) return
        runCatchingCancellable {
            mutex.withLock {
                val history = library.getHistoryDao().find(manga.id)
                if (reading && (history == null || history.deletedAt != 0L)) return@withLock
                val anchor = chapterId ?: history?.chapterId
                val branch = manga.chapters?.find { it.id == anchor }?.branch
                // Multiple branches with a missing anchor are ambiguous, including branch removal.
                val knownBranch = manga.chapters?.map { it.branch }?.distinct().orEmpty()
                val key = "source:${manga.source.name}:${manga.id}"
                val old = dao.baseline(key)?.payload?.let(RecommendationCodec::decodeBaseline)
                val now = System.currentTimeMillis()
                val next = when {
                    anchor != null && manga.chapters?.none { it.id == anchor } == true && knownBranch.size > 1 ->
                        ChapterBaseline(null, 0, "", 0f, 0, now)
                    reading -> ChapterReleaseEvidence.reading(old, manga.chapters.orEmpty(), branch, now, manga.source.name).copy(
                        exactGeneration = library.getChapterReadDao().policy(manga.id)?.takeIf {
                            it.enabled && !it.deleted && it.source == manga.source.name &&
                                (it.editionUrl == manga.url || it.editionUrl == manga.publicUrl)
                        }?.generation)
                    history != null && old != null && history.updatedAt > old.lastReadingAt ->
                        ChapterReleaseEvidence.reading(old, manga.chapters.orEmpty(), branch, history.updatedAt, manga.source.name)
                            .copy(confirmedReadingBoundary = false)
                    else -> ChapterReleaseEvidence.update(old, manga.chapters.orEmpty(), branch, now, manga.source.name)
                        .let { if (old == null && history != null) it.copy(lastReadingAt = history.updatedAt) else it }
                }
                if (next != old) {
                    dao.baseline(RecommendationBaselineEntity(key, RecommendationCodec.encodeBaseline(next)))
                    changed()
                }
            }
        }.onFailure { it.printStackTraceDebug() }
    }

    suspend fun manualProgress(manga: Manga) {
        runCatchingCancellable { mutex.withLock {
            val key = "source:${manga.source.name}:${manga.id}"
            val old = dao.baseline(key)?.payload?.let(RecommendationCodec::decodeBaseline) ?: return@withLock
            dao.baseline(RecommendationBaselineEntity(key, RecommendationCodec.encodeBaseline(old.copy(releases = 0,
                releaseChapterIds = emptySet(), confirmedReadingBoundary = false, exactGeneration = null))))
            changed()
        } }.onFailure { it.printStackTraceDebug() }
    }
}
