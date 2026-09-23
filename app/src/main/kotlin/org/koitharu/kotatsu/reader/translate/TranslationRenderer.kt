package org.koitharu.kotatsu.reader.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class TranslationOverflow(val index: Int, val block: TranslatedBlock)
data class TranslationRenderResult(val bitmap: Bitmap, val overflow: List<TranslationOverflow>)

/** Draws text in its source region at a readable size; content which cannot fit stays accessible. */
@Singleton
class TranslationRenderer @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) {

	fun render(source: Bitmap, blocks: List<TranslatedBlock>, overlayBg: Boolean): TranslationRenderResult {
		val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: source
		if (blocks.isEmpty()) return TranslationRenderResult(out, emptyList())
		val canvas = Canvas(out)
		val density = context.resources.displayMetrics
		// StaticLayout draws into image pixels. Convert the requested scaled-sp size to the
		// image's fitted display scale so it remains legible on both high- and low-density phones.
		val config = context.resources.configuration
		val columns = when {
			config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && settings.isReaderDoubleOnLandscape -> 2f
			config.smallestScreenWidthDp >= 600 && settings.isReaderDoubleOnFoldable -> 2f
			else -> 1f
		}
		val fitScale = (density.widthPixels / columns / out.width.toFloat()).coerceAtLeast(0.25f)
		val pxPerSp = density.scaledDensity / fitScale
		val padding = 4f * density.density / fitScale
		val minFont = MIN_SP * pxPerSp
		val maxFont = MAX_SP * pxPerSp
		val gap = 2f * density.density / fitScale
		val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
		val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0x55000000
			style = Paint.Style.STROKE
			strokeWidth = max(1f, density.density / fitScale)
		}
		val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.BLACK
			typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
		}
		val occupied = mutableListOf<RectF>()
		val sourceRects = blocks.map { denormalize(it.rect, out.width, out.height) }
		val overflow = mutableListOf<TranslationOverflow>()
		blocks.forEachIndexed { index, block ->
			val sourceBox = denormalize(block.rect, out.width, out.height)
			if (block.translatedText.isBlank() || sourceBox.width() <= 1f || sourceBox.height() <= 1f) {
				overflow += TranslationOverflow(index + 1, block)
				return@forEachIndexed
			}
			var placement = sourceBox
			var layout = fitLayout(block.translatedText, textPaint,
				(sourceBox.width() - padding * 2f).toInt().coerceAtLeast(1), sourceBox.height() - padding * 2f, minFont, maxFont)
			if (layout == null) {
				// If unused space surrounds the bubble, try a modest expansion. Never paint over
				// another source block; the numbered text sheet handles dense layouts safely.
				val expandedBox = RectF(
					max(0f, sourceBox.left - sourceBox.width() * 0.22f),
					max(0f, sourceBox.top - sourceBox.height() * 0.22f),
					min(out.width.toFloat(), sourceBox.right + sourceBox.width() * 0.22f),
					min(out.height.toFloat(), sourceBox.bottom + sourceBox.height() * 0.22f),
				)
				val clearOfOtherSources = sourceRects.withIndex().none { (otherIndex, other) ->
					otherIndex != index && RectF.intersects(expandedBox, other)
				}
				if (clearOfOtherSources) {
					placement = expandedBox
					layout = fitLayout(block.translatedText, textPaint,
						(placement.width() - padding * 2f).toInt().coerceAtLeast(1), placement.height() - padding * 2f, minFont, maxFont)
				}
			}
			if (layout == null) {
				overflow += TranslationOverflow(index + 1, block)
				return@forEachIndexed
			}
			val usedWidth = min(placement.width() - padding * 2f, max(1f, layout.maxLineWidth().toFloat()))
			val rect = RectF(
				placement.centerX() - (usedWidth + padding * 2) / 2f,
				placement.centerY() - (layout.height + padding * 2) / 2f,
				placement.centerX() + (usedWidth + padding * 2) / 2f,
				placement.centerY() + (layout.height + padding * 2) / 2f,
			)
			if (rect.left < 0 || rect.top < 0 || rect.right > out.width || rect.bottom > out.height ||
				occupied.any { RectF.intersects(it, expanded(rect, gap)) }
			) {
				overflow += TranslationOverflow(index + 1, block)
				return@forEachIndexed
			}
			occupied += rect
			if (overlayBg) {
				canvas.drawRoundRect(rect, 6f * density.density / fitScale, 6f * density.density / fitScale, background)
				canvas.drawRoundRect(rect, 6f * density.density / fitScale, 6f * density.density / fitScale, outline)
			}
			canvas.save()
			canvas.translate(rect.left + padding, rect.top + padding)
			layout.draw(canvas)
			canvas.restore()
		}
		// Number markers point to the same ordered entries in the translated text sheet.
		val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff356b77.toInt() }
		val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 13f * pxPerSp
			textAlign = Paint.Align.CENTER
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		for (item in overflow) {
			val anchor = denormalize(item.block.rect, out.width, out.height)
			val radius = max(14f * pxPerSp, 20f * density.density / fitScale)
			val cx = anchor.centerX().coerceIn(radius, out.width - radius)
			val cy = anchor.centerY().coerceIn(radius, out.height - radius)
			canvas.drawCircle(cx, cy, radius, markerPaint)
			val label = item.index.toString()
			canvas.drawText(label, cx, cy - (markerText.ascent() + markerText.descent()) / 2f, markerText)
		}
		return TranslationRenderResult(out, overflow)
	}

	private fun fitLayout(text: String, base: TextPaint, width: Int, height: Float, minSize: Float, maxSize: Float): StaticLayout? {
		var size = maxSize
		while (size >= minSize) {
			val paint = TextPaint(base).apply { textSize = size }
			val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
				.setAlignment(Layout.Alignment.ALIGN_CENTER)
				.setIncludePad(false)
				.setLineSpacing(0f, 1.1f)
				.build()
			if (layout.height <= height) return layout
			size -= max(0.5f, minSize / 12f)
		}
		return null
	}

	private fun expanded(rect: RectF, amount: Float) = RectF(rect).apply { inset(-amount, -amount) }

	private fun denormalize(rect: RectF, width: Int, height: Int) =
		RectF(rect.left * width, rect.top * height, rect.right * width, rect.bottom * height)

	private fun StaticLayout.maxLineWidth(): Int = (0 until lineCount).maxOfOrNull { getLineWidth(it).toInt() } ?: 0

	private companion object {
		const val MIN_SP = 12f
		const val MAX_SP = 14f
	}
}
