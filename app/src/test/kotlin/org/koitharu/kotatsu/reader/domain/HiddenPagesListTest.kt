package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

class HiddenPagesListTest {

    private fun page(id: Long, index: Int, chapter: Long = 10L) =
        ReaderPage(id, "u$id", null, chapter, index, TestMangaSource)

    private val pages = listOf(page(1, 0), page(2, 1), page(3, 2), page(4, 0, 11L), page(5, 1, 11L))

    @Test
    fun hiddenPagesAreLeftOutAndIndicesKeepDescribingTheChapter() {
        val visible = withoutHidden(pages, setOf(2L))
        assertEquals(listOf(1L, 3L, 4L, 5L), visible.map { it.id })
        assertEquals(listOf(0, 2, 0, 1), visible.map { it.index })
    }

    @Test
    fun aChapterAlwaysKeepsOnePage() {
        val visible = withoutHidden(pages, setOf(4L, 5L))
        assertEquals(listOf(1L, 2L, 3L, 4L), visible.map { it.id })
    }

    @Test
    fun nothingHiddenReturnsTheSameList() {
        assertEquals(pages, withoutHidden(pages, emptySet()))
    }

    @Test
    fun aSavedPositionOnAHiddenPageGoesToTheNextOneStillThere() {
        val visible = withoutHidden(pages, setOf(2L))
        assertEquals(1, visible.indexOfPosition(10L, 1))
        assertEquals(1, visible.indexOfPosition(10L, 2))
        // The last page hidden: the last one before it
        assertEquals(1, withoutHidden(pages, setOf(3L)).indexOfPosition(10L, 2))
        assertNull(visible.indexOfPosition(99L, 0))
    }
}
