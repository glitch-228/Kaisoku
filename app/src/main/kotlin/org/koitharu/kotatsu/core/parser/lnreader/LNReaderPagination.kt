package org.koitharu.kotatsu.core.parser.lnreader

/** Item offsets belong to the host; plugin page numbers advance only after successful requests. */
internal class LNReaderPagination {
	private var key: String? = null
	private val pages = HashMap<Int, Int>()

	fun pageFor(requestKey: String, offset: Int): Int {
		if (key != requestKey || offset == 0) return 1
		return pages[offset] ?: throw IllegalArgumentException("Unknown novel list offset: $offset")
	}

	fun accept(requestKey: String, offset: Int, page: Int, count: Int) {
		if (key != requestKey || offset == 0) pages.clear()
		key = requestKey
		pages[offset] = page
		if (count > 0) pages[offset + count] = page + 1
	}
}

internal suspend fun loadAllNovelChapters(
	details: LNReaderNovelDetails,
	loadPage: suspend (Int) -> List<LNReaderChapter>,
): List<LNReaderChapter> {
	val chapters = ArrayList(details.chapters)
	for (page in 1..details.totalPages) chapters.addAll(loadPage(page))
	return chapters.distinctBy { it.path }
}
