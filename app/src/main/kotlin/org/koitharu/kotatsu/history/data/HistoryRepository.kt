package org.koitharu.kotatsu.history.data

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.db.entity.toMangaTagsList
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import kotlin.math.abs
import kotlin.math.ceil
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.tracker.domain.CheckNewChaptersUseCase
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class HistoryRepository @Inject constructor(
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val trackerSync: org.koitharu.kotatsu.scrobbling.common.data.TrackerSyncCoordinator,
    private val mangaRepository: MangaDataRepository,
    private val localObserver: HistoryLocalObserver,
    private val newChaptersUseCaseProvider: Provider<CheckNewChaptersUseCase>,
    private val recommendationStore: org.koitharu.kotatsu.suggestions.local.RecommendationStore,
    val chapterReads: org.koitharu.kotatsu.history.exact.ChapterReadRepository,
    private val libraryContent: org.koitharu.kotatsu.remote.library.LibraryListContentPolicies,
) {

    suspend fun getList(offset: Int, limit: Int): List<Manga> {
        val entities = db.getHistoryDao().findAll(offset, limit)
        return entities.map { it.toManga() }
    }

    suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
        val dao = db.getHistoryDao()
        val q = "%$query%"
        val entities = when (kind) {
            SearchKind.SIMPLE,
            SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

            SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
            SearchKind.TAG -> dao.searchByTag(q, limit)
        }
        return entities.toMangaList()
    }

    suspend fun getLastOrNull(): Manga? {
        val entity = db.getHistoryDao().findAll(0, 1).firstOrNull() ?: return null
        return entity.toManga()
    }

    fun observeLast(): Flow<Manga?> {
        return db.getHistoryDao().observeAll(1).map {
            val first = it.firstOrNull()
            first?.toManga()
        }
    }

    fun observeAll(): Flow<List<Manga>> {
        return db.getHistoryDao().observeAll().mapItems {
            it.toManga()
        }
    }

    fun observeAll(limit: Int): Flow<List<Manga>> {
        return db.getHistoryDao().observeAll(limit).mapItems {
            it.toManga()
        }
    }

    fun observeAllWithHistory(
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        limit: Int
    ): Flow<List<MangaWithHistory>> = libraryContent.observe().flatMapLatest { policy ->
        if (ListFilterOption.Downloaded in filterOptions) localObserver.observeAll(order, filterOptions, limit, policy)
        else db.getHistoryDao().observeAll(order, filterOptions, limit, policy).mapItems {
            MangaWithHistory(it.toManga(), it.history.toMangaHistory())
        }
    }

    fun observeOne(id: Long): Flow<MangaHistory?> {
        return db.getHistoryDao().observe(id).map {
            it?.toMangaHistory()
        }
    }

    suspend fun addOrUpdate(manga: Manga, chapterId: Long, page: Int, scroll: Int, percent: Float, force: Boolean, chapterCompleted: Boolean = false) {
        if (!force && shouldSkip(manga)) {
            return
        }
        assert(manga.chapters != null)
        db.withTransaction {
            mangaRepository.storeManga(manga, replaceExisting = true)
            val branch = manga.chapters?.findById(chapterId)?.branch
            db.getHistoryDao().upsert(
                HistoryEntity(
                    mangaId = manga.id,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chapterId = chapterId,
                    page = page,
                    scroll = scroll.toFloat(), // we migrate to int, but decide to not update database
                    percent = percent,
                    chaptersCount = manga.chapters?.count { it.branch == branch } ?: 0,
                    deletedAt = 0L,
                ),
            )
            if (chapterCompleted) chapterReads.confirmed(manga, chapterId)
        }
        newChaptersUseCaseProvider.get()(manga, chapterId)
        if (force) recommendationStore.manualProgress(manga)
        if (chapterCompleted) trackerSync.onChapterCompleted(manga, chapterId)
    }

    suspend fun getOne(manga: Manga): MangaHistory? {
        return db.getHistoryDao().find(manga.id)?.recoverIfNeeded(manga)?.toMangaHistory()
    }

    suspend fun updateNovelPosition(
        manga: Manga, chapterId: Long, page: Int, scroll: Int, percent: Float, chapterCompleted: Boolean,
    ) {
        if (shouldSkip(manga)) return
        val chapter = manga.chapters?.findById(chapterId)
        val count = manga.chapters?.count { it.branch == chapter?.branch } ?: 0
        val updated = if (chapter != null) db.getHistoryDao().updatePosition(
            manga.id, chapterId, count, page, scroll.toFloat(), percent, System.currentTimeMillis(),
        ) else 0
        if (updated == 0) {
            addOrUpdate(manga, chapterId, page, scroll, percent, force = false, chapterCompleted = chapterCompleted)
        } else if (chapterCompleted) {
            chapterReads.confirmed(manga, chapterId)
            trackerSync.onChapterCompleted(manga, chapterId)
        }
    }

    /**
     * Fuzzy-find histories for *other* manga whose title (or any alt-title) is close to [manga]'s.
     * Used by the tracker to estimate how far the user has read the series via a parallel source
     * (re-import, different language, etc.) so already-read chapters aren't flagged as new.
     */
    suspend fun findSimilarByTitle(manga: Manga, limit: Int): List<MangaHistory> {
        val title = manga.title.trim()
        if (title.length < MIN_TITLE_MATCH_LENGTH) {
            return emptyList()
        }
        val result = ArrayList<MangaHistory>()
        for (candidate in db.getMangaDao().searchByTitle("%$title%", limit)) {
            if (candidate.manga.id == manga.id || !candidate.manga.title.matchesTitle(manga)) {
                continue
            }
            db.getHistoryDao().find(candidate.manga.id)?.toMangaHistory()?.let(result::add)
        }
        return result
    }

    suspend fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
        val exact = chapterReads.activeCounts(listOf(mangaId))
        if (mangaId in exact) {
            val count = exact[mangaId]
            return ReadingProgress(count?.percent ?: 0f, count?.totalCount ?: 0, mode, count?.readCount ?: 0)
                .takeIf { it.isValid() }
        }
        val entity = db.getHistoryDao().find(mangaId) ?: return null
        val fixedPercent = if (ReadingProgress.isCompleted(entity.percent)) 1f else entity.percent
        return ReadingProgress(
            percent = fixedPercent,
            totalChapters = entity.chaptersCount,
            mode = mode,
        ).takeIf { it.isValid() }
    }

    suspend fun getProgressMap(mangaIds: Collection<Long>, mode: ProgressIndicatorMode): Map<Long, ReadingProgress> {
        if (mangaIds.isEmpty()) {
            return emptyMap()
        }
        val entities = db.getHistoryDao().findAllByIds(mangaIds.toLongArray())
        val exact = chapterReads.activeCounts(mangaIds)
        val result = HashMap<Long, ReadingProgress>(entities.size)
        for (entity in entities) {
            if (entity.mangaId in exact) continue
            val fixedPercent = if (ReadingProgress.isCompleted(entity.percent)) 1f else entity.percent
            val progress = ReadingProgress(
                percent = fixedPercent,
                totalChapters = entity.chaptersCount,
                mode = mode,
            ).takeIf { it.isValid() } ?: continue
            result[entity.mangaId] = progress
        }
        for ((mangaId, count) in exact) {
            val progress = ReadingProgress(count?.percent ?: 0f, count?.totalCount ?: 0, mode, count?.readCount ?: 0)
                .takeIf { it.isValid() } ?: continue
            result[mangaId] = progress
        }
        return result
    }

    suspend fun clear() {
        db.withTransaction {
            chapterReads.reset(db.getChapterReadDao().allPolicies().map { it.mangaId })
            db.getHistoryDao().clear()
        }
    }

    suspend fun delete(manga: Manga) = db.withTransaction {
        chapterReads.reset(listOf(manga.id))
        db.getHistoryDao().delete(manga.id)
        mangaRepository.gcChaptersCache()
    }

    suspend fun deleteAfter(minDate: Long) = db.withTransaction {
        val ids = db.getHistoryDao().idsAfter(minDate)
        chapterReads.reset(ids)
        db.getHistoryDao().deleteAfter(minDate)
        mangaRepository.gcChaptersCache()
    }

    suspend fun deleteNotFavorite() = db.withTransaction {
        val ids = db.getHistoryDao().idsNotFavorite()
        chapterReads.reset(ids)
        db.getHistoryDao().deleteNotFavorite()
        mangaRepository.gcChaptersCache()
    }

    suspend fun delete(ids: Collection<Long>): ReversibleHandle {
        var exactBefore: Map<Long, org.koitharu.kotatsu.history.exact.ChapterReadSnapshot> = emptyMap()
        db.withTransaction {
            exactBefore = chapterReads.reset(ids)
            for (id in ids) {
                db.getHistoryDao().delete(id)
            }
            mangaRepository.gcChaptersCache()
        }
        return ReversibleHandle {
            recover(ids)
            chapterReads.restoreResetUndo(exactBefore)
        }
    }

    /**
     * Try to replace one manga with another one
     * Useful for replacing saved manga on deleting it with remote source
     */
    suspend fun deleteOrSwap(manga: Manga, alternative: Manga?) {
        if (alternative == null || db.getMangaDao().update(alternative.toEntity()) <= 0) {
            delete(manga)
        }
    }

    suspend fun getPopularTags(limit: Int): List<MangaTag> {
        return db.getHistoryDao().findPopularTags(limit).toMangaTagsList()
    }

    suspend fun getPopularSources(limit: Int): List<MangaSource> {
        return db.getHistoryDao().findPopularSources(limit).toMangaSources()
    }

    fun shouldSkip(manga: Manga): Boolean = settings.isIncognitoModeEnabled(manga.isNsfw())

    fun observeShouldSkip(manga: Manga): Flow<Boolean> {
        return settings.observe(AppSettings.KEY_INCOGNITO_MODE, AppSettings.KEY_INCOGNITO_NSFW)
            .map { shouldSkip(manga) }
            .distinctUntilChanged()
    }

    private suspend fun recover(ids: Collection<Long>) {
        db.withTransaction {
            for (id in ids) {
                db.getHistoryDao().recover(id)
            }
        }
    }

    private suspend fun HistoryEntity.recoverIfNeeded(manga: Manga): HistoryEntity {
        val chapters = manga.findRecoveryChapters(this)
        if (manga.isLocal || chapters.isNullOrEmpty() || chapters.findById(chapterId) != null) {
            return this
        }
        val newChapterIndex = estimateRecoveredChapterIndex(chapters.size) ?: return this
        val newChapterId = chapters.getOrNull(newChapterIndex)?.id ?: return this
        val newEntity = copy(
            chapterId = newChapterId,
            percent = estimateRecoveredPercent(chapters.size),
            chaptersCount = chapters.size,
        )
        // This is only a best-effort position for opening the reader. Persist it after the reader
        // confirms a visible page; otherwise a failed load can replace valid history with a guess.
        return newEntity
    }

    /**
     * Pick the branch that most closely matches the chapter count stored on the existing history
     * row — that way "recovered" reads land on the same translation/language the user was reading,
     * even if other branches have since grown.
     */
    private fun Manga.findRecoveryChapters(history: HistoryEntity): List<MangaChapter>? {
        val allChapters = chapters?.takeUnless { it.isEmpty() } ?: return null
        if (history.chaptersCount > 0) {
            return allChapters.groupBy { it.branch }
                .values
                .minByOrNull { abs(it.size - history.chaptersCount) }
        }
        return getChapters(getPreferredBranch(null)).takeUnless { it.isEmpty() } ?: allChapters
    }

    private fun HistoryEntity.estimateRecoveredChapterIndex(newChaptersCount: Int): Int? {
        if (newChaptersCount <= 0 || !ReadingProgress.isValid(percent)) {
            return null
        }
        val referenceCount = chaptersCount.takeIf { it > 0 } ?: newChaptersCount
        val referenceIndex = if (ReadingProgress.isCompleted(percent)) {
            referenceCount - 1
        } else {
            ceil(percent.toDouble() * referenceCount).toInt() - 1
        }
        return referenceIndex.coerceIn(0, newChaptersCount - 1)
    }

    private fun HistoryEntity.estimateRecoveredPercent(newChaptersCount: Int): Float {
        if (chaptersCount <= 0 || newChaptersCount <= 0 || !ReadingProgress.isValid(percent)) {
            return percent
        }
        return (percent * chaptersCount / newChaptersCount).coerceIn(0f, 1f)
    }

    private fun String.matchesTitle(manga: Manga): Boolean {
        if (almostEquals(manga.title, TITLE_MATCH_THRESHOLD)) {
            return true
        }
        return manga.altTitles.any { altTitle ->
            altTitle.isNotBlank() && almostEquals(altTitle, TITLE_MATCH_THRESHOLD)
        }
    }

    private fun HistoryWithManga.toManga() = manga.toManga(tags.toMangaTags(), null)

    private companion object {
        const val MIN_TITLE_MATCH_LENGTH = 4
        const val TITLE_MATCH_THRESHOLD = 0.12f
    }
}
