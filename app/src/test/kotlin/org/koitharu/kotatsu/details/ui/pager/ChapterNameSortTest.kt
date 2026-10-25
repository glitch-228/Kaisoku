package org.koitharu.kotatsu.details.ui.pager

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.reader.domain.itemsInReadingOrder

class ChapterNameSortTest {

    @Test
    fun naturalChapterNamesKeepNumericOrder() {
        val chapters = listOf(item(10f, "Chapter 10"), item(2f, "Chapter 2"), item(1f, "Prologue"))

        val sorted = chapters.sortedWith(CHAPTER_NAME_COMPARATOR)

        assertEquals(listOf("Chapter 2", "Chapter 10", "Prologue"), sorted.map { it.chapter.title })
    }

    @Test
    fun blankTitlesFallBackToChapterNumber() {
        val chapters = listOf(item(10f, " "), item(2f, null))

        val sorted = chapters.sortedWith(CHAPTER_NAME_COMPARATOR)

        assertEquals(listOf(2f, 10f), sorted.map { it.chapter.number })
    }

    @Test
    fun displayControlsRemainIndependentOfSourceReadingDirection() {
        val chapters = listOf(item(10f, "Chapter 10"), item(1f, "Chapter 1"), item(2f, "Chapter 2"))
        for (readingReversed in listOf(false, true)) {
            val readingOrder = itemsInReadingOrder(chapters, readingReversed)
            assertEquals(listOf(2f, 1f, 10f), chapters.orderChapterList(true, false).map { it.chapter.number })
            assertEquals(listOf(10f, 1f, 2f), chapters.orderChapterList(false, false).map { it.chapter.number })
            assertEquals(listOf(1f, 2f, 10f), chapters.orderChapterList(false, true).map { it.chapter.number })
            assertEquals(listOf(10f, 2f, 1f), chapters.orderChapterList(true, true).map { it.chapter.number })
            assertEquals(readingOrder, itemsInReadingOrder(chapters, readingReversed))
        }
    }

    @Test
    fun disablingNameSortRestoresTheOriginalSourceSequence() {
        val chapters = listOf(item(10f, "Chapter 10"), item(2f, "Chapter 2"), item(1f, "Chapter 1"))
        assertEquals(listOf(1f, 2f, 10f), chapters.orderChapterList(false, true).map { it.chapter.number })
        assertEquals(listOf(10f, 2f, 1f), chapters.orderChapterList(false, false).map { it.chapter.number })
        assertEquals(listOf(1f, 2f, 10f), chapters.orderChapterList(false, true).map { it.chapter.number })
    }

    @Test
    fun datesAreDisplayOnlyAndUnknownDatesRemainLast() {
        val chapters = listOf(item(3f, "Third", 300), item(0f, "Unknown"), item(1f, "First", 100), item(2f, "Equal", 100))
        val original = chapters.toList()
        assertEquals(listOf(1f, 2f, 3f, 0f), chapters.orderChapterList(false, false, true).map { it.chapter.number })
        assertEquals(listOf(3f, 2f, 1f, 0f), chapters.orderChapterList(true, false, true).map { it.chapter.number })
        assertEquals(original, chapters)
        assertEquals(original, chapters.orderChapterList(false, false, false))
    }

    @Test
    fun nameChoiceRetainsPriorityWhenRestoringOldPreferences() {
        val chapters = listOf(item(2f, "B", 100), item(1f, "A", 300))
        assertEquals(listOf(1f, 2f), chapters.orderChapterList(false, true, true).map { it.chapter.number })
    }

    private fun item(number: Float, title: String?, date: Long = 0L) = ChapterListItem(
        chapter = MangaChapter(
            id = number.toLong(),
            title = title,
            number = number,
            volume = 0,
            url = "chapter/$number",
            scanlator = null,
            uploadDate = date,
            branch = null,
            source = MangaParserSource.MANGADEX,
        ),
        flags = 0,
    )
}
