package org.koitharu.kotatsu.core.parser.lnreader

import org.junit.Assert.*
import org.junit.Test

class LNReaderChapterOrderTest {
	@Test fun newestFirstPagesBecomeReadingOrderWithoutChangingChapterIdentity() {
		val newest = LNReaderChapter("Главы 624–611: Убийство и совершенствование", "/624/")
		val first = LNReaderChapter("Глава 1: Молодой Ван Сюань", "/1/")
		val prologue = LNReaderChapter("Пролог", "/prologue/")
		val chapters = listOf(newest, first, prologue)
		val ordered = normalizeNovelChapterOrder("jaomix.ru", chapters) { it.name }
		assertEquals(listOf(prologue, first, newest), ordered)
		assertSame(first, ordered[1])
		assertEquals(ordered, normalizeNovelChapterOrder("lnreader:jaomix.ru", ordered) { it.name })
	}

	@Test fun otherPluginsAndUnnumberedListsKeepTheirDeclaredOrder() {
		val titles = listOf("Глава 9", "Глава 1")
		assertEquals(titles, normalizeNovelChapterOrder("RLIB", titles) { it })
		val unnumbered = listOf("Введение", "Эпилог")
		assertEquals(unnumbered, normalizeNovelChapterOrder("jaomix.ru", unnumbered) { it })
	}
}
