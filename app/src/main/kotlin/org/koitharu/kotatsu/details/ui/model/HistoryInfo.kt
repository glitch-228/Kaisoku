package org.koitharu.kotatsu.details.ui.model

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReadingTime

data class HistoryInfo(
	val totalChapters: Int,
	val currentChapter: Int,
	val history: MangaHistory?,
	val isIncognitoMode: Boolean,
	val isChapterMissing: Boolean,
	val canDownload: Boolean,
	val estimatedTime: ReadingTime?,
    val completionPercent: Float? = null,
    val readChapters: Int? = null,
) {
	val isValid: Boolean
		get() = totalChapters >= 0

	val canContinue
		get() = currentChapter >= 0

	val percent: Float
		get() = completionPercent ?: if (history != null && (canContinue || isChapterMissing)) {
			history.percent
		} else {
			0f
		}
}

fun HistoryInfo(
	manga: MangaDetails?,
	branch: String?,
	history: MangaHistory?,
	isIncognitoMode: Boolean,
	estimatedTime: ReadingTime?,
    exact: org.koitharu.kotatsu.history.exact.ChapterReadSnapshot? = null,
): HistoryInfo {
	val chapters = if (manga?.chapters?.isEmpty() == true) {
		emptyList()
	} else {
		manga?.chapters?.get(branch)
	}
	val currentChapter = if (history != null && !chapters.isNullOrEmpty()) {
		chapters.indexOfFirst { it.id == history.chapterId }
	} else {
		-2
	}
    val explicit = exact?.takeIf { it.isActive && it.policy?.mangaId == manga?.id }
    val cached = explicit?.counts?.singleOrNull { it.branchKey == org.koitharu.kotatsu.history.exact.encodeReadBranch(branch) }
    val savedCounterparts = explicit != null && explicit.policy?.source != "LOCAL" &&
        (manga?.toManga()?.source?.name == "LOCAL" || chapters?.any { it.source.name == "LOCAL" } == true)
    val confirmed = chapters?.takeIf { it.isNotEmpty() && !savedCounterparts }
    val read = if (explicit != null && confirmed != null) explicit.readIds(confirmed)?.size else cached?.readCount
    val total = if (savedCounterparts) cached?.totalCount ?: -1
        else confirmed?.size ?: cached?.totalCount ?: chapters?.size ?: -1
    val displayedChapter = if (savedCounterparts && cached != null && history != null) {
        org.koitharu.kotatsu.history.exact.ChapterReadTransfer.references(cached).indexOfFirst { it.chapterId == history.chapterId }
    } else currentChapter
	return HistoryInfo(
		totalChapters = total,
		currentChapter = displayedChapter,
		history = history,
		isIncognitoMode = isIncognitoMode,
		isChapterMissing = history != null && manga?.isLoaded == true && manga.allChapters.none { it.id == history.chapterId },
		canDownload = manga?.isLocal == false,
		estimatedTime = estimatedTime,
        completionPercent = if (explicit == null) null else if (total > 0) (read ?: 0).toFloat() / total else 0f,
        readChapters = read,
	)
}
