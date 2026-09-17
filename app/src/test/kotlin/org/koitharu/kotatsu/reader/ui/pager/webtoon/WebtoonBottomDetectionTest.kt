package org.koitharu.kotatsu.reader.ui.pager.webtoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonBottomDetectionTest {

	@Test
	fun shortFinalCreditImageDoesNotHaveAnUnreadRoundingPixel() {
		// 1001 * 1080 / 1200 = 900.9; layout fits the entire image in a 900px item.
		val height = calculateScaledPageHeight(1200, 1001, 1080, 2400)
		assertEquals(900, height)
		val range = calculateScaledPageScrollRange(1200, 1001, 1080, height)
		assertEquals(0, range)
		assertTrue(isPageScrolledToBottom(ready = true, scroll = 0, scrollRange = range))
	}

	@Test
	fun fullyVisibleShortPagesHaveNoInternalScrollAcrossImageSizes() {
		for (sourceHeight in 1..2600) {
			val height = calculateScaledPageHeight(1200, sourceHeight, 1080, 2400)
			assertEquals(
				"Image height $sourceHeight",
				0,
				calculateScaledPageScrollRange(1200, sourceHeight, 1080, height),
			)
		}
	}

	@Test
	fun tallFinalPageStillRequiresScrollingToItsRealBottom() {
		val height = calculateScaledPageHeight(1200, 5001, 1080, 2400)
		val range = calculateScaledPageScrollRange(1200, 5001, 1080, height)
		assertEquals(2100, range)
		assertFalse(isPageScrolledToBottom(ready = true, scroll = range - 1, scrollRange = range))
		assertTrue(isPageScrolledToBottom(ready = true, scroll = range, scrollRange = range))
	}

	/**
	 * Regression: when the last page's image is not ready yet, getScrollRange() is 0 and getScroll()
	 * is 0, so a naive `scroll >= range` reports "at the bottom" of a page that hasn't even loaded.
	 * That false positive made the reader save/report the last loaded page (jumping to the next
	 * chapter on resume and flickering while scrolling near a chapter end).
	 */
	@Test
	fun notReadyPageIsNeverAtBottom() {
		assertFalse(isPageScrolledToBottom(ready = false, scroll = 0, scrollRange = 0))
		assertFalse(isPageScrolledToBottom(ready = false, scroll = 0, scrollRange = 5000))
	}

	@Test
	fun readyPageScrolledToEndIsAtBottom() {
		assertTrue(isPageScrolledToBottom(ready = true, scroll = 5000, scrollRange = 5000))
		assertTrue(isPageScrolledToBottom(ready = true, scroll = 6000, scrollRange = 5000))
	}

	@Test
	fun readyPageNotScrolledToEndIsNotAtBottom() {
		assertFalse(isPageScrolledToBottom(ready = true, scroll = 0, scrollRange = 5000))
		assertFalse(isPageScrolledToBottom(ready = true, scroll = 4999, scrollRange = 5000))
	}

	/** A short page (image shorter than the viewport) has range 0 but is genuinely fully shown. */
	@Test
	fun readyShortPageIsAtBottom() {
		assertTrue(isPageScrolledToBottom(ready = true, scroll = 0, scrollRange = 0))
	}

	@Test
	fun reachingBottomIsReportedEvenWithoutAVisiblePositionChange() {
		assertTrue(shouldReportAbsoluteBottom(atAbsoluteBottom = true, wasAtAbsoluteBottom = false))
	}

	@Test
	fun remainingAtBottomDoesNotSpamPageChanges() {
		assertFalse(shouldReportAbsoluteBottom(atAbsoluteBottom = true, wasAtAbsoluteBottom = true))
		assertFalse(shouldReportAbsoluteBottom(atAbsoluteBottom = false, wasAtAbsoluteBottom = false))
	}
}
