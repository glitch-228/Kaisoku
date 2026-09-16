package org.koitharu.kotatsu.core.parser.lnreader

import org.junit.Assert.assertEquals
import org.junit.Test

class LNReaderPaginationTest {

	@org.junit.Test fun initialChaptersDoNotHideLaterPagesOrTheirErrors() = kotlinx.coroutines.test.runTest {
		val first = LNReaderChapter("First", "1")
		val details = LNReaderNovelDetails("Book", "book", chapters = listOf(first), totalPages = 3)
		val requested = ArrayList<Int>()
		val all = loadAllNovelChapters(details) { page ->
			requested.add(page)
			listOf(if (page == 1) first else LNReaderChapter("Chapter $page", "$page"))
		}
		org.junit.Assert.assertEquals(listOf(1, 2, 3), requested)
		org.junit.Assert.assertEquals(listOf("1", "2", "3"), all.map { it.path })
		try {
			loadAllNovelChapters(details) { page -> if (page == 2) error("Page 2 failed") else listOf(first) }
			org.junit.Assert.fail("A failed chapter page must fail details instead of truncating them")
		} catch (expected: IllegalStateException) {
			org.junit.Assert.assertEquals("Page 2 failed", expected.message)
		}
	}
	@Test fun itemOffsetsAdvanceOnePageAndRetriesDoNotSkip() {
		val pagination = LNReaderPagination()
		assertEquals(1, pagination.pageFor("popular", 0))
		pagination.accept("popular", 0, 1, 40)
		assertEquals(2, pagination.pageFor("popular", 40))
		assertEquals(2, pagination.pageFor("popular", 40)) // failed request, retry
		pagination.accept("popular", 40, 2, 13)
		assertEquals(3, pagination.pageFor("popular", 53))
		assertEquals(2, pagination.pageFor("popular", 40))
	}

	@Test fun searchAndRefreshRestartAtPageOne() {
		val pagination = LNReaderPagination()
		pagination.accept("one", 0, 1, 20)
		assertEquals(1, pagination.pageFor("two", 0))
		assertEquals(1, pagination.pageFor("one", 0))
	}
}
