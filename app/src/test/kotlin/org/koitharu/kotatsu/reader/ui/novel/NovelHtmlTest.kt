package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelHtmlTest {

	@Test
	fun `plain text strips tags and keeps image markers`() {
		val html = """
			<h1>Chapter 1</h1>
			<p>Hello <b>world</b>, this &amp; that.</p>
			<p><br/></p>
			<p><img src="https://example.com/pic.jpg"/></p>
			<p>Second paragraph.</p>
		""".trimIndent()

		val text = NovelHtml.toPlainText(html)

		assertTrue("Hello world, this & that." in text)
		assertTrue("Second paragraph." in text)
		assertTrue("📷 [图片: https://example.com/pic.jpg]" in text)
		assertTrue(!text.contains("<p>"))
		assertTrue(!text.contains("<b>"))
	}

	@Test
	fun `paragraphs are joined with blank lines`() {
		val text = NovelHtml.toPlainText("<p>One</p><p>Two</p>")
		assertEquals("One\n\nTwo", text)
	}

	@Test
	fun `parseNovelImages extracts block images and strips html`() {
		val parsed = parseNovelImages(
			"<p>Intro</p>\n📷 [图片: https://example.com/a.jpg]\n<p><img src=\"https://example.com/b.jpg\"/></p>",
		)
		assertEquals(listOf("https://example.com/a.jpg", "https://example.com/b.jpg"), parsed.blockImagePaths)
		assertTrue("[IMAGE_PLACEHOLDER_0]" in parsed.text)
		assertTrue("[IMAGE_PLACEHOLDER_1]" in parsed.text)
		assertTrue(!parsed.text.contains("<img"))
	}

	@Test
	fun `parseNovelImages renders surrounded images inline`() {
		val parsed = parseNovelImages("<p>看图<img src=\"https://example.com/emoji.png\" alt=\" :) \">继续</p>")
		assertEquals(listOf("https://example.com/emoji.png"), parsed.inlineImagePaths)
		assertTrue(parsed.text.contains("[INLINE_IMAGE_0]"))
	}

	@Test
	fun `parseNovelImages uses short alt text for standalone images`() {
		val parsed = parseNovelImages("<p><img src=\"\" alt=\":)\"></p>")
		assertTrue(parsed.text.contains(":)"))
		assertTrue(parsed.inlineImagePaths.isEmpty())
		assertTrue(parsed.blockImagePaths.isEmpty())
	}
}
