package org.koitharu.kotatsu.reader.ui.novel

import org.koitharu.kotatsu.reader.ui.ReaderState
import kotlin.math.ceil

internal fun novelHistoryPosition(chapterId: Long, ratio: Float): ReaderState = ReaderState(
	chapterId = chapterId,
	page = 0,
	// Flooring can move a page-start character into the preceding page on resume.
	// Ignore float representation noise so saving a restored coordinate is idempotent.
	scroll = ceil((ratio.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f) * 10_000.0 - 0.0005).toInt(),
)

internal fun novelProgressRatio(scroll: Int): Float = (scroll / 10_000f).coerceIn(0f, 1f)

/** Also recover page boundaries from older history written with downward rounding. */
internal fun novelRestoredPage(offset: Int, length: Int, pageStarts: List<Int>): Int {
	val tolerance = ceil(length.coerceAtLeast(0) / 10_000.0).toInt()
	return pageStarts.indexOfLast { it <= offset + tolerance }.coerceAtLeast(0)
}

internal fun novelPagedProgress(offset: Int, length: Int, page: Int, pages: Int, dual: Boolean): Float {
	if (length <= 0 || pages <= 0) return 0f
	if (page + (if (dual) 2 else 1) >= pages) return 1f
	return (offset.toFloat() / length).coerceIn(0f, 1f)
}
