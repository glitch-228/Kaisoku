package org.koitharu.kotatsu.reader.ui.pager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.reader.ui.pager.doublepage.padForDoublePage

class ReaderPositionAnchorTest {

	@Test fun reversedAndPaddedPreloadsCompareTheSameVisiblePage() {
		fun chapter(id: Long, size: Int) = (0 until size).map {
			ReaderPage(id * 100 + it, "page/$it", null, id, it, MangaParserSource.READMANGA_RU)
		}
		val current = chapter(2, 5)
		for (double in listOf(false, true)) for (cover in listOf(false, true)) {
			fun ordering(pages: List<ReaderPage>) = pages.reversed().let {
				if (double) it.padForDoublePage(cover) else it
			}
			val old = ordering(current)
			val visible = old.first { it.index == 1 }
			val previousPreload = ordering(chapter(1, 3) + current)
			assertFalse(shouldReanchorAfterPageListUpdate(old.indexOf(visible), previousPreload.indexOf(visible)))
			val nextPreload = ordering(current + chapter(3, 4))
			assertTrue(shouldReanchorAfterPageListUpdate(old.indexOf(visible), nextPreload.indexOf(visible)))
			assertEquals(visible, nextPreload[nextPreload.indexOf(visible)])
			assertTrue(shouldReanchorAfterPageListUpdate(nextPreload.indexOf(visible), old.indexOf(visible)))
		}
	}

	@Test
	fun appendedPagesKeepRecyclerViewAnchor() {
		assertFalse(shouldReanchorAfterPageListUpdate(oldPosition = 10, newPosition = 10))
	}

	@Test
	fun prependedOrTrimmedPagesRequireReanchor() {
		assertTrue(shouldReanchorAfterPageListUpdate(oldPosition = 10, newPosition = 24))
		assertTrue(shouldReanchorAfterPageListUpdate(oldPosition = 24, newPosition = 10))
	}

	@Test
	fun missingCurrentPageDoesNotRequestInvalidRestore() {
		assertFalse(shouldReanchorAfterPageListUpdate(oldPosition = -1, newPosition = 10))
		assertFalse(shouldReanchorAfterPageListUpdate(oldPosition = 10, newPosition = -1))
	}
}
