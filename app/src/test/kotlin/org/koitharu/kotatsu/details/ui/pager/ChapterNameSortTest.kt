package org.koitharu.kotatsu.details.ui.pager

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

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

	private fun item(number: Float, title: String?) = ChapterListItem(
		chapter = MangaChapter(
			id = number.toLong(),
			title = title,
			number = number,
			volume = 0,
			url = "chapter/$number",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = MangaParserSource.MANGADEX,
		),
		flags = 0,
	)
}
