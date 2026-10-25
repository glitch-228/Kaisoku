package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.*
import org.junit.Test

class NovelSpeechTextTest {
    @Test fun narrationOmitsGeneratedImageMarkersAndProtectedPackagePaths() {
        val text = "First paragraph\n📷 [图片: file+zip:///private/book.epub#OPS/secret.png]\nLast paragraph"
        val narration = novelSpeechContent(text)
        assertTrue(narration.contains("First paragraph"))
        assertTrue(narration.contains("Last paragraph"))
        assertFalse(narration.contains("IMAGE_PLACEHOLDER"))
        assertFalse(narration.contains("secret.png"))
        assertFalse(narration.contains("file+zip:"))
    }

    @Test fun inlineIllustrationsDoNotBecomeSpokenImplementationTokens() {
        val narration = novelSpeechContent("<p>Before <img src=\"local-illustration.png\"/> after.</p>")
        assertTrue(narration.contains("Before"))
        assertTrue(narration.contains("after."))
        assertFalse(narration.contains("INLINE_IMAGE"))
        assertFalse(narration.contains("local-illustration.png"))
    }

    @Test fun ordinaryBracketedProseAndNonGeneratedMarkerLookalikesRemain() {
        val text = "[Illustration] A [quiet] night. [IMAGE_PLACEHOLDER_x] [INLINE_IMAGE_name]"
        assertEquals(text, novelSpeechContent(text))
    }

    @Test fun longCjkAndEmojiTextIsSplitLosslesslyWithinEngineLimits() {
        val text = "这是一个段落😀".repeat(200)
        val chunks = novelSpeechChunks(text, 0f, 37)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 37 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
    @Test fun readingStartsAtTheVisibleParagraphAndDoesNotMutateProgress() {
        val text = "First paragraph\nВторой абзац\n第三段"
        val ratio = 20f / text.length
        assertEquals("Второй абзац\n第三段", novelSpeechChunks(text, ratio, 4000).joinToString(""))
        assertTrue(novelSpeechChunks(text, 1f, 4000).isEmpty())
        assertTrue(novelSpeechChunks("  ", 0f, 4000).isEmpty())
    }
}
