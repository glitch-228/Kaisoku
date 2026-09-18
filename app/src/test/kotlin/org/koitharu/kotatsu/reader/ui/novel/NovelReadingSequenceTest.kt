package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class NovelReadingSequenceTest {
	private val chapters = listOf("Prologue", "Chapter 1", "Extra", "Chapter 2").mapIndexed { index, name ->
		MangaChapter((index + 1).toLong(), name, index.toFloat(), 0, "file:///chapter-$index.html",
			null, 0L, null, MangaParserSource.READMANGA_RU)
	}

	@Test fun reversingChangesNeighborsWhileKeepingCurrentChapterAndPositionIdentity() {
		val current = chapters[1]
		val state = novelHistoryPosition(current.id, .5823f)
		val reversed = novelReadingSequence(chapters, true, state.chapterId)
		assertEquals(listOf(4L, 3L, 2L, 1L), reversed.chapters.map { it.id })
		assertSame(current, reversed.chapters[reversed.currentIndex])
		assertEquals(1L, reversed.chapters[reversed.currentIndex + 1].id)
		assertEquals(3L, reversed.chapters[reversed.currentIndex - 1].id)
		assertEquals(current.url, reversed.chapters[reversed.currentIndex].url)
		assertEquals(state, novelHistoryPosition(current.id, novelProgressRatio(state.scroll)))
		val reset = novelReadingSequence(reversed.chapters, true, state.chapterId)
		assertEquals(chapters, reset.chapters)
		assertEquals(1, reset.currentIndex)
	}

	@Test fun reopeningInReverseOrderRestoresTheSameHistoryOrExplicitChapterId() {
		for (chapter in chapters) {
			for (reversed in listOf(false, true)) {
				val sequence = novelReadingSequence(chapters, reversed, chapter.id)
				assertSame(chapter, sequence.chapters[sequence.currentIndex])
			}
		}
	}

	@Test fun missingHistoryStartsAtFirstChapterInSelectedOrder() {
		assertEquals(chapters.last(), novelReadingSequence(chapters, true, null).chapters.first())
		assertEquals(0, novelReadingSequence(chapters, true, -1L).currentIndex)
		assertEquals(-1, novelReadingSequence(emptyList(), true, null).currentIndex)
		assertEquals(0, novelReadingSequence(listOf(chapters[0]), true, chapters[0].id).currentIndex)
	}
}
