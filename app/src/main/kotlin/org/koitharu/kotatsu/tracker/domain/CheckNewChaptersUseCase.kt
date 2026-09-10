package org.koitharu.kotatsu.tracker.domain

import android.os.DeadObjectException
import android.util.Log
import coil3.request.CachePolicy
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckNewChaptersUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
) {

	private val mutex = MultiMutex<Long>()

	suspend operator fun invoke(manga: Manga): MangaUpdates = mutex.withLock(manga.id) {
		repository.updateTracks()
		val tracking = repository.getTrackOrNull(manga) ?: return@withLock MangaUpdates.Failure(
			manga = manga,
			error = null,
		)
		invokeImpl(tracking)
	}

	suspend operator fun invoke(track: MangaTracking): MangaUpdates = mutex.withLock(track.manga.id) {
		// Re-fetch from the repository so we work with the latest stored state — protects against
		// drift if the track was modified between the batch snapshot and this invocation.
		val fresh = repository.getTrackOrNull(track.manga)?.takeUnless { it.isEmpty() } ?: track
		invokeImpl(fresh)
	}

	suspend operator fun invoke(mangaList: Collection<Manga>) =
		mangaList.forEach { manga ->
			runCatchingCancellable { invoke(repository.getTrack(manga)) }
		}

	suspend operator fun invoke(manga: Manga, currentChapterId: Long) = mutex.withLock(manga.id) {
		runCatchingCancellable {
			repository.updateTracks()
			val details = getFullManga(manga)
			val track = repository.getTrackOrNull(manga) ?: return@withLock
			val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
			val chapters = details.getChapters(branch)
			val chapterIndex = chapters.indexOfFirst { x -> x.id == currentChapterId }
			val lastChapter = chapters.lastOrNull()
			val prevLastIndex = chapters.indexOfFirst { it.id == track.lastChapterId }
			val addedSinceLastTrack = if (prevLastIndex >= 0) chapters.lastIndex - prevLastIndex else 0
			val effectiveNew = track.newChapters + addedSinceLastTrack
			val lastNewChapterIndex = chapters.size - effectiveNew
			val tracking = MangaTracking(
				manga = details,
				lastChapterId = lastChapter?.id ?: 0L,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull() ?: track.lastChapterDate,
				newChapters = when {
					effectiveNew == 0 -> 0
					chapterIndex < 0 -> effectiveNew
					chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
					else -> effectiveNew
				},
			)
			repository.mergeWith(tracking)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.isSuccess
	}

	private suspend fun invokeImpl(track: MangaTracking): MangaUpdates = runCatchingCancellable {
		val details = getFullManga(track.manga)
		val history = historyRepository.getOne(track.manga)
		val historyChapterId = history?.chapterId ?: 0L
		val branch = getBranch(details, track.lastChapterId, historyChapterId)
		val updates = compare(track, details, branch, historyChapterId, history?.chaptersCount ?: 0)
		// Post-filter: drop chapters the user has already read via a parallel import of the same
		// series, and drop very old chapters that are likely catalogue noise rather than real
		// releases. Cheap to skip when there's nothing to filter.
		if (updates.isValid && updates.newChapters.isNotEmpty()) {
			updates.filterReadAndStaleChapters(
				chapters = details.getChapters(branch).orEmpty(),
				history = history,
				similarHistories = historyRepository.findSimilarByTitle(details, SIMILAR_HISTORY_LIMIT),
			)
		} else {
			updates
		}
	}.getOrElse { error ->
		MangaUpdates.Failure(
			manga = track.manga,
			error = error,
		)
	}.also { updates ->
		repository.saveUpdates(updates)
	}

	private fun getBranch(manga: Manga, trackChapterId: Long, historyChapterId: Long): String? {
		manga.chapters?.findById(historyChapterId)?.let {
			return it.branch
		}
		manga.chapters?.findById(trackChapterId)?.let {
			return it.branch
		}
		// fallback
		return manga.getPreferredBranch(null)
	}

	private suspend fun getFullManga(manga: Manga): Manga = retryOnTransient {
		when {
			manga.isLocal -> fetchDetails(
				requireNotNull(localMangaRepository.getRemoteManga(manga)) {
					"Local manga is not supported"
				},
			)

			manga.chapters.isNullOrEmpty() -> fetchDetails(manga)
			else -> manga
		}
	}

	/**
	 * Retries [block] on transient IO failures (network blips, dropped sockets, dead binder). Bounded
	 * to [TRANSIENT_RETRY_ATTEMPTS] tries with a short exponential backoff so a flaky source can't
	 * burn extra wall-clock time on permanent errors.
	 */
	private suspend fun <T> retryOnTransient(block: suspend () -> T): T {
		var currentDelay = TRANSIENT_RETRY_INITIAL_DELAY_MS
		repeat(TRANSIENT_RETRY_ATTEMPTS - 1) {
			try {
				return block()
			} catch (e: IOException) {
				delay(currentDelay)
				currentDelay = (currentDelay * 2).coerceAtMost(TRANSIENT_RETRY_MAX_DELAY_MS)
			} catch (e: DeadObjectException) {
				delay(currentDelay)
				currentDelay = (currentDelay * 2).coerceAtMost(TRANSIENT_RETRY_MAX_DELAY_MS)
			}
		}
		return block()
	}

	private suspend fun fetchDetails(manga: Manga): Manga {
		val repo = mangaRepositoryFactory.create(manga.source)
		return if (repo is CachingMangaRepository) {
			repo.getDetails(manga, CachePolicy.WRITE_ONLY)
		} else {
			repo.getDetails(manga)
		}
	}

	/**
	 * The main functionality of tracker: check new chapters in [manga] comparing to the [track].
	 *
	 * Comparison anchors, tried in order:
	 *  1. [MangaTracking.lastChapterId] — the tracker's own baseline.
	 *  2. [MangaTracking.lastChapterDate] — upload date of the last known chapter; robust to id
	 *     churn (some sources rotate chapter URLs, changing derived ids on every fetch). This is
	 *     the proper baseline, advanced by the caller on every successful check, so it does not
	 *     re-flag the same chapters on subsequent runs.
	 *  3. [historyChapterId] — the user's reading position; a last resort when the track has no
	 *     usable id or date (e.g. a stale backup). May surface a large batch once; the caller then
	 *     records a fresh date baseline so it does not repeat.
	 *
	 * If none of the anchors are usable we re-baseline silently (no notification).
	 */
	private fun compare(
		track: MangaTracking,
		manga: Manga,
		branch: String?,
		historyChapterId: Long,
		historyCount: Int,
	): MangaUpdates.Success {
		if (track.isEmpty()) {
			// First check or manga was empty on last check. If the user has reading history that
			// already counted N chapters, treat those as the baseline so anything beyond N can be
			// flagged on this very first run instead of silently re-baselining.
			if (historyCount > 0) {
				val chapters = manga.getChapters(branch)
				if (chapters.size > historyCount) {
					val newCount = chapters.size - historyCount
					return MangaUpdates.Success(manga, branch, chapters.takeLast(newCount), isValid = true)
				}
				return MangaUpdates.Success(manga, branch, emptyList(), isValid = true)
			}
			return MangaUpdates.Success(manga, branch, emptyList(), isValid = false)
		}
		val chapters = requireNotNull(manga.getChapters(branch))
		if (BuildConfig.DEBUG && chapters.findById(track.lastChapterId) == null) {
			Log.e("Tracker", "Chapter ${track.lastChapterId} not found")
		}
		compareAgainst(manga, branch, chapters, track.lastChapterId)?.let { return it }
		// lastChapterId is stale (not in the fresh list) — prefer the date baseline.
		compareByDate(manga, branch, chapters, track.lastChapterDate?.toEpochMilli() ?: 0L)?.let { return it }
		// No usable id or date — last resort: the user's reading position.
		if (historyChapterId != 0L && historyChapterId != track.lastChapterId) {
			compareAgainst(manga, branch, chapters, historyChapterId)?.let { return it }
		}
		// Nothing usable; can't tell what's new. Re-baseline silently.
		return MangaUpdates.Success(manga, branch, emptyList(), isValid = false)
	}

	/**
	 * Returns a result if [anchorChapterId] is a usable anchor in [chapters] (either it is the
	 * last chapter, or there are some chapters after it), or `null` if the anchor is absent.
	 */
	private fun compareAgainst(
		manga: Manga,
		branch: String?,
		chapters: List<MangaChapter>,
		anchorChapterId: Long,
	): MangaUpdates.Success? {
		val newChapters = chapters.takeLastWhile { x -> x.id != anchorChapterId }
		return when {
			newChapters.isEmpty() -> MangaUpdates.Success(
				manga = manga,
				branch = branch,
				newChapters = emptyList(),
				isValid = chapters.lastOrNull()?.id == anchorChapterId,
			)

			newChapters.size == chapters.size -> null // anchor not found in the list

			else -> MangaUpdates.Success(manga, branch, newChapters, isValid = true)
		}
	}

	/**
	 * Date-based fallback: chapters uploaded strictly after [lastChapterDateMillis] are considered
	 * new. Returns `null` when the date is unusable (zero, or older than every chapter — which
	 * would flag the whole list as new and is more likely a data glitch than a real update).
	 */
	private fun compareByDate(
		manga: Manga,
		branch: String?,
		chapters: List<MangaChapter>,
		lastChapterDateMillis: Long,
	): MangaUpdates.Success? {
		if (lastChapterDateMillis <= 0L) return null
		val newChapters = chapters.filter { it.uploadDate > lastChapterDateMillis }
		return when {
			newChapters.isEmpty() -> MangaUpdates.Success(manga, branch, emptyList(), isValid = true)
			newChapters.size == chapters.size -> null
			else -> MangaUpdates.Success(manga, branch, newChapters, isValid = true)
		}
	}

	private fun MangaUpdates.Success.filterReadAndStaleChapters(
		chapters: List<MangaChapter>,
		history: MangaHistory?,
		similarHistories: List<MangaHistory>,
	): MangaUpdates.Success {
		val readChapters = maxOf(
			history.getReadChaptersCount(chapters),
			similarHistories.maxOfOrNull { it.estimatedReadChaptersCount() } ?: 0,
		)
		val minChapterDate = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_STALE_CHAPTER_DAYS)
		val filtered = newChapters.filter { chapter ->
			chapter.isAfterReadPosition(chapters, readChapters) && chapter.isRecentEnough(minChapterDate)
		}
		return if (filtered.size == newChapters.size) this else copy(newChapters = filtered)
	}

	private fun MangaChapter.isAfterReadPosition(chapters: List<MangaChapter>, readChapters: Int): Boolean {
		if (readChapters <= 0) return true
		val index = chapters.indexOfFirst { it.id == id }
		return index < 0 || index >= readChapters
	}

	private fun MangaChapter.isRecentEnough(minChapterDate: Long): Boolean {
		return uploadDate == 0L || uploadDate >= minChapterDate
	}

	private fun MangaHistory?.getReadChaptersCount(chapters: List<MangaChapter>): Int {
		if (this == null) return 0
		val index = chapters.indexOfFirst { it.id == chapterId }
		return when {
			index >= 0 -> index + 1
			ReadingProgress.isCompleted(percent) -> chapters.size
			else -> estimatedReadChaptersCount().coerceAtMost(chapters.size)
		}
	}

	private fun MangaHistory.estimatedReadChaptersCount(): Int {
		if (chaptersCount <= 0) return 0
		return if (ReadingProgress.isCompleted(percent)) {
			chaptersCount
		} else {
			(percent * chaptersCount).toInt().coerceIn(0, chaptersCount)
		}
	}

	private companion object {
		const val TRANSIENT_RETRY_ATTEMPTS = 3
		const val TRANSIENT_RETRY_INITIAL_DELAY_MS = 1_000L
		const val TRANSIENT_RETRY_MAX_DELAY_MS = 4_000L
		const val SIMILAR_HISTORY_LIMIT = 8
		const val MAX_STALE_CHAPTER_DAYS = 90L
	}
}
