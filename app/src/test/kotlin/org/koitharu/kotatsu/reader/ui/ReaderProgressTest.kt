package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressTest {

	@Test fun reported313ChapterCompletionAndOtherCountsStayExactlyComplete() {
		for (count in 1..2000) {
			assertEquals("Chapter count $count", 1f, calculateReaderPercent(count - 1, count, 29, 30), 0f)
		}
		assertTrue(calculateReaderPercent(312, 313, 28, 30) < 1f)
	}

	@Test
	fun finalPageOfFinalChapterIsExactlyComplete() {
		assertEquals(
			1f,
			calculateReaderPercent(
				chapterIndex = 96,
				chaptersCount = 97,
				pageIndex = 16,
				pagesCount = 17,
			),
		)
	}

	@Test
	fun pageBeforeEndIsNotCompleted() {
		val percent = calculateReaderPercent(
			chapterIndex = 96,
			chaptersCount = 97,
			pageIndex = 15,
			pagesCount = 17,
		)

		assertTrue(percent < 1f)
	}

	@Test
	fun absoluteWebtoonBottomStaysCompleteWhenFreshPageListDiffers() {
		assertEquals(
			1f,
			calculatePersistedReaderPercent(
				chapterIndex = 312,
				chaptersCount = 313,
				pageIndex = 43,
				pagesCount = 46,
				scrollOffset = 10_000,
			),
		)
	}

	@Test
	fun absoluteBottomDoesNotCompleteAnEarlierChapter() {
		assertTrue(
			calculatePersistedReaderPercent(
				chapterIndex = 311,
				chaptersCount = 313,
				pageIndex = 43,
				pagesCount = 46,
				scrollOffset = 10_000,
			) < 1f,
		)
	}
}
