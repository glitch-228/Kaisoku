package org.koitharu.kotatsu.details.domain

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import org.koitharu.kotatsu.reader.ui.calculateReaderPercent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.SourceSettings

class ProgressUpdateUseCase @Inject constructor(
	@ApplicationContext private val context: Context,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val database: MangaDatabase,
	private val localMangaRepository: LocalMangaRepository,
	private val networkState: NetworkState,
) {

	suspend operator fun invoke(manga: Manga): Float {
		val history = database.getHistoryDao().find(manga.id) ?: return PROGRESS_NONE
		val seed = if (manga.isLocal) {
			localMangaRepository.getRemoteManga(manga) ?: manga
		} else {
			manga
		}
		if (!seed.isLocal && !networkState.value) {
			return PROGRESS_NONE
		}
		val repo = mangaRepositoryFactory.create(seed.source)
		val details = if (manga.source != seed.source || seed.chapters.isNullOrEmpty()) {
			repo.getDetails(seed)
		} else {
			seed
		}
		val chapter = details.findChapterById(history.chapterId)
			?: return estimateFromCounts(details, history)
		val chapters = details.getChapters(chapter.branch)
		val chapterRepo = if (repo.source == chapter.source) {
			repo
		} else {
			mangaRepositoryFactory.create(chapter.source)
		}
		val chaptersCount = chapters.size
		if (chaptersCount == 0) {
			return PROGRESS_NONE
		}
		val chapterIndex = chapters.indexOfFirst { x -> x.id == history.chapterId }
		// Novel scroll stores a character ratio, not an image-page number.
		val result = if (seed.source.name.startsWith(LnReaderMangaSource.NAME_PREFIX)) {
			val ratio = org.koitharu.kotatsu.reader.ui.novel.novelProgressRatio(history.scroll.toInt())
			val index = if (SourceSettings(context, seed.source).isNovelReadingReversed) chaptersCount - chapterIndex - 1 else chapterIndex
			(index + ratio) / chaptersCount
		} else {
			val pagesCount = chapterRepo.getPages(chapter).size
			if (pagesCount == 0) return PROGRESS_NONE
			calculateReaderPercent(chapterIndex, chaptersCount, history.page, pagesCount)
		}
		if (result != history.percent || history.chaptersCount != chaptersCount) {
			updateHistoryIfUnchanged(
				history,
				history.copy(
					chapterId = chapter.id,
					percent = result,
					chaptersCount = chaptersCount,
				),
			)
		}
		return result
	}

	/**
	 * Fallback when the stored chapterId is no longer present in the fresh details (e.g. the
	 * source rotated chapter URLs, which changes derived ids). We can't pinpoint the reading
	 * position anymore, so we estimate from previously stored counts: roughly
	 * `percent * oldTotal` chapters were read, rescaled to the new total. The estimate keeps the
	 * progress indicator honest (no longer stuck at "completed") without touching the read position.
	 */
	private suspend fun estimateFromCounts(details: Manga, history: HistoryEntity): Float {
		val newTotal = details.getChapters(details.getPreferredBranch(null)).size
			.takeIf { it > 0 } ?: details.chapters?.size ?: 0
		if (newTotal == 0 || history.chaptersCount <= 0 || !ReadingProgress.isValid(history.percent)) {
			return PROGRESS_NONE
		}
		val estimated = (history.percent * history.chaptersCount / newTotal).coerceIn(0f, 1f)
		if (estimated != history.percent || history.chaptersCount != newTotal) {
			updateHistoryIfUnchanged(
				history,
				history.copy(percent = estimated, chaptersCount = newTotal),
			)
		}
		return estimated
	}

	/** Metadata refreshes may finish after a reader has already stored a newer position. */
	private suspend fun updateHistoryIfUnchanged(expected: HistoryEntity, replacement: HistoryEntity) {
		database.withTransaction {
			val historyDao = database.getHistoryDao()
			if (historyDao.find(expected.mangaId) == expected) {
				historyDao.update(replacement)
			}
		}
	}
}
