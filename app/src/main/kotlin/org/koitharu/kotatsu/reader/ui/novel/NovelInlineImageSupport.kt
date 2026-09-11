/*
 * Ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.min

data class NovelInlineImageRequest(
	val imagePath: String,
	val headers: Map<String, String>,
)

internal data class NovelImageMetrics(
	val width: Int,
	val height: Int,
) {
	fun fittedSize(maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
		if (width <= 0 || height <= 0 || maxWidth <= 0f || maxHeight <= 0f) {
			return 0f to 0f
		}
		var targetWidth = min(width.toFloat(), maxWidth)
		var targetHeight = targetWidth * height / width.toFloat()
		if (targetHeight > maxHeight) {
			val scale = maxHeight / targetHeight
			targetWidth *= scale
			targetHeight *= scale
		}
		return targetWidth to targetHeight
	}
}

internal object NovelImageMetricsCache {

	private val metrics = mutableMapOf<String, NovelImageMetrics>()

	@Synchronized
	fun get(imagePath: String): NovelImageMetrics? = metrics[imagePath]

	@Synchronized
	fun put(imagePath: String, value: NovelImageMetrics) {
		metrics[imagePath] = value
	}
}

data class ParsedNovelImages(
	val text: String,
	val blockImagePaths: List<String>,
	val inlineImagePaths: List<String>,
)

private val HTML_IMG_TAG_REGEX = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_IMG_SRC_REGEX = Regex("""(?:data-src|src)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
private val HTML_INLINE_TEXT_ATTRS = listOf("alt", "title")
private val HTML_BLOCK_BOUNDARIES = listOf(
	"</p>", "<p", "</div>", "<div", "</li>", "<li", "</h1", "<h1", "</h2", "<h2", "<br", "\n",
)

fun parseNovelImages(text: String): ParsedNovelImages {
	val blockImagePaths = mutableListOf<String>()
	val inlineImagePaths = mutableListOf<String>()
	val imagePattern = Regex("""📷\s*\[图片:\s*([^\]]+)\]""")

	var processedText = text
	imagePattern.findAll(text).forEach { match ->
		val imagePath = match.groupValues[1].trim()
		blockImagePaths.add(imagePath)
		processedText = processedText.replaceFirst(
			match.value,
			"[IMAGE_PLACEHOLDER_${blockImagePaths.size - 1}]",
		)
	}

	processedText = processedText.replace(HTML_IMG_TAG_REGEX) { matchResult ->
		val tag = matchResult.value
		val src = HTML_IMG_SRC_REGEX.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()
		val inlineText = extractInlineImageText(tag)
		when {
			src.isNotEmpty() && shouldRenderHtmlImageInline(processedText, matchResult.range) -> {
				inlineImagePaths.add(src)
				"[INLINE_IMAGE_${inlineImagePaths.size - 1}]"
			}

			src.isNotEmpty() -> {
				blockImagePaths.add(src)
				"[IMAGE_PLACEHOLDER_${blockImagePaths.size - 1}]"
			}

			inlineText != null -> inlineText

			else -> ""
		}
	}

	processedText = processedText
		.replace(Regex("(?i)<br\\s*/?>"), "\n")
		.replace(Regex("(?i)</p>"), "\n")
		.replace(Regex("(?i)<p[^>]*>"), "")
		.replace(Regex("<[^>]+>"), "")

	return ParsedNovelImages(
		text = processedText,
		blockImagePaths = blockImagePaths,
		inlineImagePaths = inlineImagePaths,
	)
}

fun applyInlineImageSpans(
	text: CharSequence,
	inlineImagePaths: List<String>,
	bitmapProvider: (String) -> Bitmap?,
): CharSequence {
	if (inlineImagePaths.isEmpty()) {
		return text
	}
	val ssb = SpannableStringBuilder.valueOf(text)
	inlineImagePaths.forEachIndexed { index, imagePath ->
		val token = "[INLINE_IMAGE_$index]"
		var start = ssb.indexOf(token)
		while (start >= 0) {
			val end = start + token.length
			ssb.setSpan(
				NovelInlineImageSpan(imagePath, bitmapProvider),
				start,
				end,
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
			start = ssb.indexOf(token, end)
		}
	}
	return ssb
}

internal fun findInlineImagePathAt(layout: Layout, x: Float, y: Float): String? {
	val text = layout.text as? Spanned ?: return null
	if (text.isEmpty()) {
		return null
	}
	val line = layout.getLineForVertical(y.toInt().coerceAtLeast(0))
	val offset = layout.getOffsetForHorizontal(line, x.coerceAtLeast(0f))
	val candidateOffsets = intArrayOf(
		offset.coerceIn(0, text.length - 1),
		(offset - 1).coerceIn(0, text.length - 1),
		(offset + 1).coerceIn(0, text.length - 1),
	)
	return candidateOffsets.asSequence()
		.flatMap { candidate -> text.getSpans(candidate, candidate, NovelInlineImageSpan::class.java).asSequence() }
		.map { it.imagePath }
		.firstOrNull()
}

private fun shouldRenderHtmlImageInline(text: String, range: IntRange): Boolean {
	val start = findPreviousBoundary(text, range.first)
	val end = findNextBoundary(text, range.last + 1)
	val segment = text.substring(start, end)
	val visibleText = segment
		.replaceFirst(HTML_IMG_TAG_REGEX, " ")
		.replace(Regex("<[^>]+>"), " ")
		.replace("&nbsp;", " ")
		.replace(Regex("\\s+"), " ")
		.trim()
	return visibleText.isNotEmpty()
}

private fun findPreviousBoundary(text: String, index: Int): Int {
	var result = 0
	for (marker in HTML_BLOCK_BOUNDARIES) {
		val pos = text.lastIndexOf(marker, startIndex = index.coerceAtLeast(0))
		if (pos >= 0) {
			result = maxOf(result, pos)
		}
	}
	return result
}

private fun findNextBoundary(text: String, index: Int): Int {
	var result = text.length
	for (marker in HTML_BLOCK_BOUNDARIES) {
		val pos = text.indexOf(marker, startIndex = index.coerceAtLeast(0))
		if (pos >= 0) {
			result = minOf(result, pos)
		}
	}
	return result
}

private fun extractInlineImageText(tag: String): String? = HTML_INLINE_TEXT_ATTRS.asSequence()
	.mapNotNull { attr ->
		Regex("""$attr\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
			.find(tag)
			?.groupValues
			?.getOrNull(1)
	}
	.map { value ->
		value.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
	}
	.firstOrNull { value ->
		value.isNotBlank() && value.length <= 3 && value.none { it == '\n' || it == '\r' }
	}

internal fun getReservedNovelImageHeight(
	imagePath: String,
	maxWidth: Float,
	maxHeight: Float,
	fallbackAspectRatio: Float = 0.75f,
): Float {
	val metrics = NovelImageMetricsCache.get(imagePath)
	if (metrics != null) {
		return metrics.fittedSize(maxWidth, maxHeight).second
	}
	return min(maxHeight, maxWidth * fallbackAspectRatio)
}

internal fun getNovelImageDisplayRect(
	imagePath: String,
	reservedWidth: Float,
	reservedHeight: Float,
	yPosition: Float,
	fallbackAspectRatio: Float = 0.75f,
): RectF {
	val metrics = NovelImageMetricsCache.get(imagePath)
	val (targetWidth, targetHeight) = if (metrics != null) {
		metrics.fittedSize(reservedWidth, reservedHeight)
	} else {
		reservedWidth to min(reservedHeight, reservedWidth * fallbackAspectRatio)
	}
	val left = (reservedWidth - targetWidth) / 2f
	val top = yPosition + (reservedHeight - targetHeight) / 2f
	return RectF(left, top, left + targetWidth, top + targetHeight)
}

internal class NovelInlineImageSpan(
	val imagePath: String,
	private val bitmapProvider: (String) -> Bitmap?,
) : ReplacementSpan() {

	override fun getSize(
		paint: Paint,
		text: CharSequence,
		start: Int,
		end: Int,
		fm: Paint.FontMetricsInt?,
	): Int = computeInlineSize(paint).first.toInt()

	override fun draw(
		canvas: Canvas,
		text: CharSequence,
		start: Int,
		end: Int,
		x: Float,
		top: Int,
		y: Int,
		bottom: Int,
		paint: Paint,
	) {
		val (targetWidth, targetHeight) = computeInlineSize(paint)
		val bitmap = bitmapProvider(imagePath)
		val topPos = y + paint.fontMetrics.ascent +
			((paint.fontMetrics.descent - paint.fontMetrics.ascent) - targetHeight) / 2f
		if (bitmap != null) {
			val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
			val dstRect = RectF(x, topPos, x + targetWidth, topPos + targetHeight)
			canvas.drawBitmap(bitmap, srcRect, dstRect, null)
		} else {
			val oldStyle = paint.style
			val oldColor = paint.color
			paint.style = Paint.Style.STROKE
			paint.color = (oldColor and 0x00FFFFFF) or 0x66000000
			canvas.drawRect(x, topPos, x + targetWidth, topPos + targetHeight, paint)
			paint.style = oldStyle
			paint.color = oldColor
		}
	}

	private fun computeInlineSize(paint: Paint): Pair<Float, Float> {
		val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
		val targetHeight = lineHeight * 0.92f
		val metrics = NovelImageMetricsCache.get(imagePath)
		if (metrics == null || metrics.width <= 0 || metrics.height <= 0) {
			return targetHeight to targetHeight
		}
		var targetWidth = targetHeight * metrics.width / metrics.height.toFloat()
		val maxWidth = lineHeight * 2.2f
		if (targetWidth > maxWidth) {
			targetWidth = maxWidth
		}
		return targetWidth to targetHeight
	}
}
