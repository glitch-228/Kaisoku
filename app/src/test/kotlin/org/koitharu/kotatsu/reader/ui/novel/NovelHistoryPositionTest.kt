package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.*
import org.junit.Test

class NovelHistoryPositionTest {
	@Test fun repeatedlySavingRestoredHistoryDoesNotDriftForward() {
		for (scroll in 0..10_000) {
			assertEquals(scroll, novelHistoryPosition(42, novelProgressRatio(scroll)).scroll)
		}
	}
	@Test fun resumeDoesNotMovePageStartsIntoThePreviousPage() {
		val length = 183721
		val starts = listOf(0, 913, 1811, 2837, 3841, 4900)
		for ((page, offset) in starts.withIndex()) {
			val ratio = offset.toFloat() / length
			val saved = novelHistoryPosition(42, ratio)
			val restored = kotlin.math.round(novelProgressRatio(saved.scroll) * length).toInt()
			assertEquals(page, novelRestoredPage(restored, length, starts))
			val oldScroll = (ratio * 10_000).toInt()
			val oldOffset = kotlin.math.round(novelProgressRatio(oldScroll) * length).toInt()
			assertEquals(page, novelRestoredPage(oldOffset, length, starts))
		}
		assertEquals(2, novelRestoredPage(2100, length, starts))
	}
	@Test fun lastPageAndLastSpreadReportCompletion() {
		assertEquals(.25f, novelPagedProgress(250, 1000, 1, 4, false), 0f)
		assertEquals(1f, novelPagedProgress(750, 1000, 3, 4, false), 0f)
		assertEquals(1f, novelPagedProgress(500, 1000, 2, 4, true), 0f)
		assertEquals(0f, novelPagedProgress(0, 0, 0, 0, false), 0f)
	}
	@Test fun withinChapterPositionRoundTripsThroughExistingHistoryCoordinates() {
		for (ratio in listOf(0f, .1234f, .5823f, .9999f, 1f)) {
			val state = novelHistoryPosition(42, ratio)
			assertEquals(42L, state.chapterId)
			assertEquals(0, state.page)
			assertEquals(ratio, novelProgressRatio(state.scroll), .00011f)
		}
	}

	@Test fun malformedAndOutOfRangePositionsCannotCorruptHistory() {
		assertEquals(0, novelHistoryPosition(1, Float.NaN).scroll)
		assertEquals(0, novelHistoryPosition(1, -.5f).scroll)
		assertEquals(10_000, novelHistoryPosition(1, 2f).scroll)
		assertEquals(1f, novelProgressRatio(Int.MAX_VALUE), 0f)
		assertEquals(0f, novelProgressRatio(-1), 0f)
	}
}
