package org.koitharu.kotatsu.core.parser.lnreader

private val jaomixChapterNumber = Regex("^глав[аы]\\s+(\\d+)", RegexOption.IGNORE_CASE)

/** Jaomix 1.0.3 emits newest-first pages without chapter numbers. Keep IDs and special chapters intact. */
internal fun <T> normalizeNovelChapterOrder(source: String, chapters: List<T>, title: (T) -> String?): List<T> {
	if (source.removePrefix("lnreader:") != "jaomix.ru") return chapters
	val numbers = chapters.mapNotNull { jaomixChapterNumber.find(title(it).orEmpty().trim())?.groupValues?.get(1)?.toLongOrNull() }
	return if (numbers.size >= 2 && numbers.first() > numbers.last()) chapters.asReversed() else chapters
}
