package org.koitharu.kotatsu.reader.ui.pager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.reader.translate.TranslationOverflow

/** Small numbered markers for translations that could not safely fit inside their source area. */
class TranslationMarkerOverlay @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {
	private var image: SubsamplingScaleImageView? = null
	private var items: List<TranslationOverflow> = emptyList()
	private var onSelected: ((Int) -> Unit)? = null
	private var pressedIndex: Int? = null
	private val point = PointF()
	private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff356b77.toInt() }
	private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = android.graphics.Color.WHITE
		textAlign = Paint.Align.CENTER
		typeface = android.graphics.Typeface.DEFAULT_BOLD
	}

	fun showMarkers(image: SubsamplingScaleImageView, items: List<TranslationOverflow>, onSelected: (Int) -> Unit) {
		this.image = image
		this.items = items
		this.onSelected = onSelected
		visibility = if (items.isEmpty()) GONE else VISIBLE
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val view = image?.takeIf { it.isReady } ?: return
		val radius = 16f * resources.displayMetrics.density
		label.textSize = 13f * resources.displayMetrics.scaledDensity
		items.forEach { item ->
			point.set(item.block.rect.centerX() * view.sWidth, item.block.rect.centerY() * view.sHeight)
			val mapped = view.sourceToViewCoord(point) ?: return@forEach
			if (mapped.x !in -radius..(width + radius) || mapped.y !in -radius..(height + radius)) return@forEach
			canvas.drawCircle(mapped.x, mapped.y, radius, circle)
			canvas.drawText(item.index.toString(), mapped.x, mapped.y - (label.ascent() + label.descent()) / 2f, label)
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val view = image?.takeIf { it.isReady } ?: return false
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				val radius = 24f * resources.displayMetrics.density
				pressedIndex = items.firstOrNull { item ->
					point.set(item.block.rect.centerX() * view.sWidth, item.block.rect.centerY() * view.sHeight)
					val mapped = view.sourceToViewCoord(point) ?: return@firstOrNull false
					kotlin.math.hypot((mapped.x - event.x).toDouble(), (mapped.y - event.y).toDouble()) <= radius
				}?.index
				return pressedIndex != null
			}
			MotionEvent.ACTION_UP -> {
				val selected = pressedIndex
				pressedIndex = null
				if (selected != null) {
					performClick()
					onSelected?.invoke(selected)
					return true
				}
			}
			MotionEvent.ACTION_CANCEL -> {
				val consumed = pressedIndex != null
				pressedIndex = null
				return consumed
			}
		}
		return pressedIndex != null
	}

	override fun performClick(): Boolean {
		super.performClick()
		return true
	}
}
