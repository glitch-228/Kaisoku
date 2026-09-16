package org.koitharu.kotatsu.reader.translate

import org.junit.Assert.*
import org.junit.Test

class NovelTextTranslationTest {
	@Test fun splittingIsLosslessBoundedAndDoesNotSendImagesToTheProvider() {
		val text = "  First paragraph.\n\n" + "слово 😀 ".repeat(1300) +
			"\n📷 [图片: zip:/private/book.cbz!/1.jpg]\n\nLast paragraph.  "
		val parts = NovelTextTranslation.parts(text)
		assertEquals(text, parts.joinToString("") { it.text })
		assertTrue(parts.filter { it.translate }.all { it.text.length <= 2500 })
		assertFalse(parts.filter { it.translate }.any { it.text.contains("zip:") })
		assertTrue(parts.filter { !it.translate }.any { it.text.contains("zip:") })
		val unbroken = "😀".repeat(3500)
		assertEquals(unbroken, NovelTextTranslation.parts(unbroken, 251).joinToString("") { it.text })
		assertTrue(NovelTextTranslation.parts(unbroken, 251).all { !it.text.last().isHighSurrogate() })
	}

	@Test fun providerPayloadsContainTextWithoutImageOrOcrInstructions() {
		val openai = NovelTextTranslation.payload("Read this", "auto", "ru", "model", false)
		assertEquals("Read this", openai.getJSONArray("messages").getJSONObject(1).getString("content"))
		assertFalse(openai.toString().contains("image_url"))
		val gemini = NovelTextTranslation.payload("Read this", "auto", "ru", "model", true)
		assertEquals("Read this", gemini.getJSONArray("contents").getJSONObject(0)
			.getJSONArray("parts").getJSONObject(0).getString("text"))
		assertFalse(gemini.toString().contains("inline_data"))
	}

	@Test fun parsesBothProvidersAndRejectsTruncatedOrEmptyTranslations() {
		assertEquals("Привет", NovelTextTranslation.response("""{"choices":[{"finish_reason":"stop","message":{"content":"Привет"}}]}"""))
		assertEquals("Привет", NovelTextTranslation.response("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"hidden","thought":true},{"text":"Привет"}]}}]}"""))
		for (body in listOf("{}", "null", """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""",
			"""{"candidates":[{"finishReason":"SAFETY"}]}""", """{"choices":[{"finish_reason":"stop","message":{"content":null}}]}""")) {
			assertThrows(TranslateException.Parse::class.java) { NovelTextTranslation.response(body) }
		}
	}
}
