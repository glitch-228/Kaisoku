package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzFilterTest {

	@Test
	fun recognizesCbrExtensionCaseInsensitively() {
		assertTrue(hasRarComicExtension("book.cbr"))
		assertTrue(hasRarComicExtension("BOOK.CBR"))
		assertFalse(hasRarComicExtension("book.rar"))
		assertFalse(hasRarComicExtension("book.cbr.zip"))
	}
}
