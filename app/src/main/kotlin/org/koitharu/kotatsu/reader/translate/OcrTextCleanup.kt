package org.koitharu.kotatsu.reader.translate

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Repairs what text recognition gets wrong before anything is translated: Latin look-alikes inside Cyrillic
 * words (which make a translator treat "КХА" as the Latin letters it resembles and leave it as it is), words
 * hyphenated across two lines of one bubble that arrive as separate paragraphs, and "text" made of nothing a
 * translator could use.
 */
internal object OcrTextCleanup {

	private val LOOKALIKES = mapOf(
		'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М', 'O' to 'О',
		'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У',
		'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у',
	)

	/** Latin look-alikes become Cyrillic when the text is mostly Cyrillic; any other text is left alone. */
	fun normalizeScript(text: String): String {
		var cyrillic = 0
		var latin = 0
		for (char in text) {
			when (Character.UnicodeScript.of(char.code)) {
				Character.UnicodeScript.CYRILLIC -> cyrillic++
				Character.UnicodeScript.LATIN -> latin++
				else -> Unit
			}
		}
		if (latin == 0 || cyrillic == 0 || cyrillic < latin) return text
		return buildString(text.length) {
			for (char in text) {
				val script = Character.UnicodeScript.of(char.code)
				append(if (script == Character.UnicodeScript.LATIN) LOOKALIKES[char] ?: char else char)
			}
		}
	}

	/** Whether there is anything to translate: at least one letter or digit. */
	fun hasContent(text: String): Boolean = text.any { it.isLetterOrDigit() }

	/** A recognised paragraph and where it is, on any scale as long as all of them share it. */
	data class Paragraph(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
		val width get() = right - left
		val height get() = bottom - top
	}

	fun mergeHyphenated(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
		if (blocks.size < 2) return blocks
		val merged = mergeHyphenatedParagraphs(blocks.map {
			Paragraph(it.originalText, it.rect.left, it.rect.top, it.rect.right, it.rect.bottom)
		})
		if (merged.size == blocks.size) return blocks
		return merged.map { TranslatedBlock(it.text, "", RectF(it.left, it.top, it.right, it.bottom)) }
	}

	/**
	 * Joins a paragraph that ends in a hyphenated word with the one directly below it that continues it.
	 * Only a hyphen right after a letter counts, and only when the next paragraph sits under it with the same
	 * horizontal span and no more than a line of space between them.
	 */
	fun mergeHyphenatedParagraphs(paragraphs: List<Paragraph>): List<Paragraph> {
		val result = ArrayList<Paragraph>(paragraphs.size)
		var index = 0
		while (index < paragraphs.size) {
			var current = paragraphs[index]
			while (index + 1 < paragraphs.size && continues(current, paragraphs[index + 1])) {
				val next = paragraphs[index + 1]
				current = Paragraph(
					current.text.dropLast(1) + next.text,
					min(current.left, next.left), min(current.top, next.top),
					max(current.right, next.right), max(current.bottom, next.bottom),
				)
				index++
			}
			result += current
			index++
		}
		return result
	}

	private fun continues(first: Paragraph, second: Paragraph): Boolean {
		val text = first.text
		if (text.length < 2 || text.last() != '-' || !text[text.length - 2].isLetter()) return false
		if (second.text.isEmpty() || !second.text.first().isLetter()) return false
		val overlap = min(first.right, second.right) - max(first.left, second.left)
		val narrower = min(first.width, second.width)
		if (narrower <= 0f || overlap < narrower * 0.5f) return false
		val gap = second.top - first.bottom
		return gap <= max(first.height, second.height) * 1.2f && gap > -first.height
	}
}
