package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzFilterTest {

    @Test
    fun recognizesCbrExtensionCaseInsensitively() {
        assertTrue(hasRarComicExtension("book.cbr"))
        assertTrue(hasRarComicExtension("BOOK.CBR"))
        assertTrue(hasRarComicExtension("book.rar"))
        assertTrue(hasRarComicExtension("BOOK.RAR"))
        assertFalse(hasRarComicExtension("book.cbr.zip"))
    }
}
