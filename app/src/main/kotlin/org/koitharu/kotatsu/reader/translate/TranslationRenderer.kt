package org.koitharu.kotatsu.reader.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Paints translated text onto a copy of the source page bitmap. Two-pass so
 * later bubbles can't be hidden under earlier ones. v1 is horizontal-only;
 * vertical-text plans + bubble grouping are deferred.
 *
 * The white background is auto-sized to fit the rendered StaticLayout, then
 * centered on the original bbox's center — so Chinese→English (where target
 * is wider than source) doesn't overflow a too-small box.
 */
@Singleton
class TranslationRenderer @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	fun render(source: Bitmap, blocks: List<TranslatedBlock>, overlayBg: Boolean): Bitmap {
		val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: source
		if (blocks.isEmpty()) return out
		val canvas = Canvas(out)
		val rounded = dp(6f)
		val padding = dp(4f)
		val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			style = Paint.Style.FILL
		}
		val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0x33000000
			style = Paint.Style.STROKE
			strokeWidth = dp(0.5f)
		}
		val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.BLACK
			isAntiAlias = true
		}

		val prepared = blocks.map { block ->
			val box = denormalize(block.rect, out.width, out.height)
			val layout = fitLayout(block.translatedText, basePaint, box, padding)
			val width = layout.maxLineWidth().toFloat() + padding * 2
			val height = layout.height.toFloat() + padding * 2
			val cx = box.centerX()
			val cy = box.centerY()
			var left = cx - width / 2f
			var top = cy - height / 2f
			var right = left + width
			var bottom = top + height
			// Clamp to bitmap, shifting if needed
			if (left < 0f) { right -= left; left = 0f }
			if (top < 0f) { bottom -= top; top = 0f }
			if (right > out.width) { left -= right - out.width; right = out.width.toFloat() }
			if (bottom > out.height) { top -= bottom - out.height; bottom = out.height.toFloat() }
			Prepared(RectF(left, top, right, bottom), layout, cy)
		}
		// Spread overlapping boxes apart vertically so none is buried inside another,
		// keeping each block in its source vertical order (a higher bubble stays higher).
		separateOverlaps(prepared, out.height)

		if (overlayBg) {
			for (p in prepared) {
				canvas.drawRoundRect(p.rect, rounded, rounded, bgPaint)
				canvas.drawRoundRect(p.rect, rounded, rounded, strokePaint)
			}
		}
		for (p in prepared) {
			canvas.save()
			canvas.translate(p.rect.left + padding, p.rect.top + padding)
			p.layout.draw(canvas)
			canvas.restore()
		}
		return out
	}

	private class Prepared(val rect: RectF, val layout: StaticLayout, val anchorY: Float)

	/**
	 * Resolve overlaps between rendered text boxes by sliding them apart on the Y axis.
	 * Each box is anchored to [Prepared.anchorY] (its source bubble's vertical centre), so
	 * when two boxes collide the one whose original text sat higher is pushed up and the
	 * lower one down — overlaps (and fully-nested boxes) separate without scrambling reading
	 * order. Runs a few relaxation rounds because clamping a box back on-screen can nudge it
	 * into a neighbour again. Horizontal placement is left untouched.
	 */
	private fun separateOverlaps(items: List<Prepared>, height: Int) {
		if (items.size < 2) return
		val gap = dp(2f)
		val maxY = height.toFloat()
		repeat(SEPARATE_ROUNDS) {
			var moved = false
			for (i in items.indices) {
				for (j in i + 1 until items.size) {
					val a = items[i]
					val b = items[j]
					if (!RectF.intersects(a.rect, b.rect)) continue
					val upper = if (a.anchorY <= b.anchorY) a else b
					val lower = if (upper === a) b else a
					val overlap = upper.rect.bottom - lower.rect.top + gap
					if (overlap > 0f) {
						val shift = overlap / 2f
						upper.rect.offset(0f, -shift)
						lower.rect.offset(0f, shift)
						moved = true
					}
				}
			}
			// Keep every box on the bitmap; the next round mops up overlaps this re-introduces.
			for (p in items) {
				if (p.rect.top < 0f) p.rect.offset(0f, -p.rect.top)
				if (p.rect.bottom > maxY) p.rect.offset(0f, maxY - p.rect.bottom)
			}
			if (!moved) return
		}
	}

	private fun denormalize(rect: RectF, width: Int, height: Int): RectF =
		RectF(
			rect.left * width,
			rect.top * height,
			rect.right * width,
			rect.bottom * height,
		)

	/**
	 * Find a layout that fits inside [box] (with padding) — preferring to use the
	 * original box height but allowing the layout to grow up to 1.75× the box
	 * height if the translation is significantly longer than the source text
	 * (typical when CJK→Latin expansion). Falls back to shrinking the text down
	 * to [MIN_SIZE_DP] if no font size fits.
	 */
	private fun fitLayout(text: String, base: TextPaint, box: RectF, padding: Float): StaticLayout {
		val width = max((box.width() - padding * 2).toInt(), dp(80f).toInt())
		val targetHeight = max((box.height() - padding * 2), dp(20f))
		val maxHeight = targetHeight * 1.75f
		val minSize = dp(MIN_SIZE_DP)
		val maxSize = dp(MAX_SIZE_DP)
		// Start near the target height; binary search would be faster but unnecessary at this scale.
		var size = max(minSize, min(maxSize, targetHeight * 0.30f))
		var layout = makeLayout(text, base, width, size)
		while (layout.height > maxHeight && size > minSize) {
			size = max(minSize, size - dp(0.5f))
			layout = makeLayout(text, base, width, size)
		}
		// Cosmetic: if we have lots of vertical headroom but the text is small, scale up a touch
		while (size < maxSize && layout.height < targetHeight * 0.6f) {
			val next = size + dp(0.5f)
			val candidate = makeLayout(text, base, width, next)
			if (candidate.height > targetHeight) break
			size = next
			layout = candidate
		}
		return layout
	}

	private fun makeLayout(text: String, base: TextPaint, width: Int, size: Float): StaticLayout {
		val paint = TextPaint(base).apply { textSize = size }
		return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
			.setIncludePad(false)
			.setLineSpacing(0f, 1.05f)
			.build()
	}

	private fun StaticLayout.maxLineWidth(): Int {
		var max = 0
		for (i in 0 until lineCount) {
			val w = getLineWidth(i).toInt()
			if (w > max) max = w
		}
		return max
	}

	private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

	private companion object {
		const val MIN_SIZE_DP = 9f
		const val MAX_SIZE_DP = 22f
		const val SEPARATE_ROUNDS = 8
	}
}
