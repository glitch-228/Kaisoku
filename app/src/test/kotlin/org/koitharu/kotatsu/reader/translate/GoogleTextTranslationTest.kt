package org.koitharu.kotatsu.reader.translate

import org.junit.Assert.*
import org.junit.Test

class GoogleTextTranslationTest {
	@Test fun unicodeParagraphsAreEncodedOnceAndNoKeyIsRequired() {
		val text = "Глава 1 & 100%\n\n第二段 🙂"
		val url = GoogleTextTranslation.url(text, "", "ru")
		assertEquals(text, url.queryParameter("q"))
		assertEquals("auto", url.queryParameter("sl"))
		assertEquals("ru", url.queryParameter("tl"))
		assertNull(url.queryParameter("key"))
		assertFalse(url.toString().contains(" "))
	}

	@Test fun allResponseSegmentsAndParagraphBreaksSurvive() {
		assertEquals("Первый абзац.\n\nВторой абзац.", GoogleTextTranslation.response(
			"""[[["Первый абзац.\n\n","First paragraph.\n\n"],["Второй абзац.","Second paragraph."]],null,"en"]"""))
	}

	@Test fun emptyAndInvalidResponsesCannotReplaceTheOriginal() {
		for (body in listOf("<html>Blocked</html>", "[]", "[[]]", "[[[null]]]", "[[[\"\"]]]")) {
			assertThrows(TranslateException.Parse::class.java) { GoogleTextTranslation.response(body) }
		}
	}
}
