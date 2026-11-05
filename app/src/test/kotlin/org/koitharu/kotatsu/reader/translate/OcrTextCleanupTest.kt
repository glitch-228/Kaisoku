package org.koitharu.kotatsu.reader.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.reader.translate.OcrTextCleanup.Paragraph

class OcrTextCleanupTest {

	@Test
	fun latinLookalikesInCyrillicTextBecomeCyrillic() {
		// Latin K, X, A inside a Cyrillic sound effect
		assertEquals("КХ-А-А-А-А!", OcrTextCleanup.normalizeScript("KX-А-А-А-А!"))
		// A Latin O and C inside a Cyrillic word
		assertEquals("ГОСпожа!", OcrTextCleanup.normalizeScript("ГOCпожа!"))
	}

	@Test
	fun latinOrMixedLatinHeavyTextIsLeftAlone() {
		assertEquals("KXA", OcrTextCleanup.normalizeScript("KXA"))
		assertEquals("Hello да world", OcrTextCleanup.normalizeScript("Hello да world"))
		assertEquals("привет", OcrTextCleanup.normalizeScript("привет"))
	}

	@Test
	fun punctuationOnlyTextHasNoContent() {
		assertFalse(OcrTextCleanup.hasContent("!"))
		assertFalse(OcrTextCleanup.hasContent(" .. ?! "))
		assertTrue(OcrTextCleanup.hasContent("20"))
		assertTrue(OcrTextCleanup.hasContent("а!"))
	}

	@Test
	fun aHyphenatedWordSplitAcrossLinesIsJoined() {
		val merged = OcrTextCleanup.mergeHyphenatedParagraphs(
			listOf(
				Paragraph("А!.. ПОМО-", 0.60f, 0.40f, 0.93f, 0.43f),
				Paragraph("ГИТЕ!", 0.64f, 0.435f, 0.88f, 0.46f),
			),
		)
		assertEquals(1, merged.size)
		assertEquals("А!.. ПОМОГИТЕ!", merged.single().text)
		assertEquals(0.60f, merged.single().left, 0f)
		assertEquals(0.46f, merged.single().bottom, 0f)
	}

	@Test
	fun unrelatedParagraphsAreNotJoined() {
		val a = Paragraph("one-", 0.1f, 0.1f, 0.4f, 0.13f)
		// far to the side
		assertEquals(2, OcrTextCleanup.mergeHyphenatedParagraphs(listOf(a, Paragraph("two", 0.6f, 0.14f, 0.9f, 0.17f))).size)
		// far below
		assertEquals(2, OcrTextCleanup.mergeHyphenatedParagraphs(listOf(a, Paragraph("two", 0.1f, 0.4f, 0.4f, 0.43f))).size)
		// no hyphenated word: a dash after a space is just punctuation
		assertEquals(2, OcrTextCleanup.mergeHyphenatedParagraphs(listOf(Paragraph("one -", 0.1f, 0.1f, 0.4f, 0.13f),
			Paragraph("two", 0.1f, 0.14f, 0.4f, 0.17f))).size)
		// the next one does not continue a word
		assertEquals(2, OcrTextCleanup.mergeHyphenatedParagraphs(listOf(a, Paragraph("...", 0.1f, 0.14f, 0.4f, 0.17f))).size)
	}
}
