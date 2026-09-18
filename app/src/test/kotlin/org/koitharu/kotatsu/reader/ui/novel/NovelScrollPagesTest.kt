package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.*
import org.junit.Test

class NovelScrollPagesTest {
    @Test
    fun `scroll pages use chapter height and viewport with a partial last page`() {
        assertEquals(4, novelScrollPageCount(3100, 1000))
        assertEquals(0, novelScrollPageIndex(0, 3100, 1000))
        assertEquals(2, novelScrollPageIndex(2200, 3100, 1000))
        assertEquals(3, novelScrollPageIndex(3100, 3100, 1000))
        assertEquals(7, novelScrollPageCount(3100, 500))
    }

    @Test
    fun `empty and short chapters keep a valid slider range`() {
        assertEquals(1, novelScrollPageCount(0, 0))
        assertEquals(1, novelScrollPageCount(200, 1000))
        assertEquals(0, novelScrollPageIndex(-10, 200, 1000))
        assertFalse(NovelReaderSettings().enableDualPage)
    }
}
