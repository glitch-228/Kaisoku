/*
 * Ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import kotlin.math.ceil
import kotlin.math.max

object NovelTypography {

	private val titleLinePattern =
		Regex("""^[《【].+[》】]$|^第[0-9一二三四五六七八九十百千零〇两]+[章节卷回部篇话集].*$""")
	// Literal code-point ranges, not Unicode script-name classes: the ICU regex on older Android
	// (API 28 and below) rejects those and blows up the whole object in <clinit>.
	private val hanRanges = """[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]"""
	private val westernSpacingPattern =
		Regex("""($hanRanges)([A-Za-z0-9@#&])|([A-Za-z0-9@#&])($hanRanges)""")

	fun prepareContentText(
		text: String,
		settings: NovelReaderSettings,
		textPaint: TextPaint,
	): String {
		val normalized = normalizeParagraphs(text)
		val spaced = if (settings.paragraphSpacing <= 0f) {
			normalized
		} else {
			applyParagraphSpacing(normalized, settings, textPaint)
		}
		val indented = applyParagraphIndent(spaced, settings)
		return optimizeChineseTypography(indented)
	}

	fun styleChapterTitles(text: CharSequence, secondaryColor: Int): CharSequence {
		val source = if (text is SpannableStringBuilder) text else SpannableStringBuilder(text)
		var cursor = 0
		val raw = source.toString()
		raw.split('\n').forEach { line ->
			val start = cursor
			val end = cursor + line.length
			if (isChapterTitleLine(line)) {
				source.setSpan(RelativeSizeSpan(1.18f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				source.setSpan(ForegroundColorSpan(secondaryColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				source.setSpan(LeadingMarginSpan.Standard(0, 0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
			cursor = end + 1
		}
		return source
	}

	private fun normalizeParagraphs(text: String): String = text
		.replace("\r\n", "\n")
		.replace(Regex("""[ \t]+\n"""), "\n")
		.replace(Regex("""\n[ \t]+"""), "\n")
		.replace(Regex("\\n{3,}"), "\n\n")
		.replace(Regex("""(?m)^([《【].+[》】]|第[0-9一二三四五六七八九十百千零〇两]+[章节卷回部篇话集].*)$"""), "\n$1\n")
		.replace(Regex("\\n{3,}"), "\n\n")
		.trim()

	private fun applyParagraphSpacing(
		text: String,
		settings: NovelReaderSettings,
		textPaint: TextPaint,
	): String {
		val spacingPx = if (settings.paragraphSpacing <= 0f) {
			((settings.lineSpacing - 1f).coerceAtLeast(0f) * textPaint.textSize)
		} else {
			settings.paragraphSpacing * textPaint.density
		}
		val lineHeight = (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent) * settings.lineSpacing
		val extraLines = if (spacingPx > 0) max(1, ceil(spacingPx / lineHeight).toInt()) else 0
		if (extraLines == 0) return text
		val spacer = "\n".repeat(extraLines)
		return text.split(Regex("\\n+")).joinToString(separator = "\n$spacer")
	}

	private fun applyParagraphIndent(text: String, settings: NovelReaderSettings): String {
		if (!settings.enableParagraphIndent) return text
		val indent = "　　"
		return text.split('\n').joinToString("\n") { line ->
			when {
				line.isBlank() -> line
				isChapterTitleLine(line.trim()) -> line.trim()
				line.startsWith(indent) -> line
				else -> indent + line.trimStart()
			}
		}
	}

	private fun optimizeChineseTypography(text: String): String = text.lines().joinToString("\n") { line ->
		if (line.isBlank()) {
			line
		} else {
			line.replace(Regex("[ \t]+"), " ")
				.replace(westernSpacingPattern) { match ->
					val left = match.groupValues[1].ifBlank { match.groupValues[3] }
					val right = match.groupValues[2].ifBlank { match.groupValues[4] }
					"$left $right"
				}
				.replace("…… ……", "…………")
				.replace("。。", "。")
		}
	}

	private fun isChapterTitleLine(line: String): Boolean {
		val trimmed = line.trim()
		if (trimmed.isBlank()) return false
		if (trimmed.length > 40) return false
		return titleLinePattern.matches(trimmed)
	}
}
