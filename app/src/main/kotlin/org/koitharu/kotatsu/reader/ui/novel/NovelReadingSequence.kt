package org.koitharu.kotatsu.reader.ui.novel

import org.koitharu.kotatsu.parsers.model.MangaChapter

internal data class NovelReadingSequence(val chapters: List<MangaChapter>, val currentIndex: Int)

/** Reorder whole chapter objects so history IDs, local URLs and unnumbered extras remain intact. */
internal fun novelReadingSequence(
	chapters: List<MangaChapter>,
	reversed: Boolean,
	currentChapterId: Long?,
): NovelReadingSequence {
	val ordered = if (reversed) chapters.reversed() else chapters
	val index = ordered.indexOfFirst { it.id == currentChapterId }
	return NovelReadingSequence(ordered, if (ordered.isEmpty()) -1 else index.coerceAtLeast(0))
}
