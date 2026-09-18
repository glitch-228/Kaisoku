/*
 * Ported from Kototoro (Apache-2.0), based on the Legado page-fold algorithm.
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.collection.LruCache
import androidx.core.view.GestureDetectorCompat
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.resolveSp
import org.koitharu.kotatsu.reader.domain.TapGridArea
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Paged and scroll novel reader view. Renders chapter text with StaticLayout,
 * paginates per chapter, supports slide and page-fold turn animations.
 */
@AndroidEntryPoint
class NovelReaderView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	private var settings: NovelReaderSettings = NovelReaderSettings.load(context)
	private var palette: NovelReaderPalette = novelReaderPalette(
		preset = settings.themePreset,
		isDarkTheme = isNightMode(),
	)

	private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
		color = palette.textColor
		textSize = resources.resolveSp(17f)
		isSubpixelText = true
		letterSpacing = 0.01f
	}

	var chapterContent: String = ""
		private set
	private var pages: List<PageInfo> = emptyList()
	private var currentPageIndex: Int = 0
	private var isDualPage: Boolean = false
	private var footerHeight: Int = 0
	private var suppressPageChangeNotification: Boolean = false
	private var pendingPageIndex: Int = 0
	private var pendingProgressRatio: Float? = null
	private var pendingTargetOffset: Int? = null
	private var pendingBiasToEnd: Boolean = false
	private var paginatedTotalLength: Int = 0

	private val scroller = android.widget.OverScroller(context)
	private var maxScrollOffset: Int = 0
	private var isFlinging: Boolean = false

	private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	var imageHeadersProvider: ((String) -> Map<String, String>?)? = null

	private val highlightPaint by lazy {
		Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.highlightColor }
	}
	private var highlightRange: IntRange? = null

	@Inject
	lateinit var imageLoader: ImageLoader

	private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
	private val loadingImages = mutableSetOf<String>()
	private val failedImages = mutableSetOf<String>()
	private val imageCache = LruCache<String, Bitmap>(50)

	private val gestureDetector: GestureDetectorCompat
	private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
	private val pageTurnThresholdFraction = 0.18f
	private val pageTurnInterpolator = DecelerateInterpolator()
	private val simulationPageTurnInterpolator = LinearInterpolator()
	private var pageSwipeDownX: Float = 0f
	private var pageSwipeDownY: Float = 0f
	private var pageSwipeStartX: Float = 0f
	private var pageSwipeStartY: Float = 0f
	private var isPageDragging: Boolean = false
	private var pageSwipeBaseIndex: Int = -1
	private var pageSwipeTargetIndex: Int = -1
	private var pageSwipeOffsetX: Float = 0f
	private var pageSwipeAnimator: ValueAnimator? = null
	private var pageSwipeChapterDelta: Int = 0
	private var pageSwipeDirection: Int = 0
	private var pageSwipeLastX: Float = 0f
	private var pageSwipeCurrentX: Float = 0f
	private var pageSwipeCurrentY: Float = 0f
	private var isSimulationAutoPageTurn: Boolean = false
	private var isAwaitingChapterTransitionContent: Boolean = false
	private var previousChapterPreviewText: String? = null
	private var nextChapterPreviewText: String? = null
	private var previousChapterPreviewPages: List<PageInfo> = emptyList()
	private var nextChapterPreviewPages: List<PageInfo> = emptyList()
	private val foldPath = Path()
	private val foldBackPath = Path()
	private val foldStart1 = PointF()
	private val foldControl1 = PointF()
	private val foldVertex1 = PointF()
	private val foldEnd1 = PointF()
	private val foldStart2 = PointF()
	private val foldControl2 = PointF()
	private val foldVertex2 = PointF()
	private val foldEnd2 = PointF()
	private val foldBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
		style = Paint.Style.FILL
	}
	private val foldFolderShadowDrawableRL = GradientDrawable(
		GradientDrawable.Orientation.RIGHT_LEFT,
		intArrayOf(0x333333, -0x4fcccccd),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private val foldFolderShadowDrawableLR = GradientDrawable(
		GradientDrawable.Orientation.LEFT_RIGHT,
		intArrayOf(0x333333, -0x4fcccccd),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private val foldBackShadowDrawableRL = GradientDrawable(
		GradientDrawable.Orientation.RIGHT_LEFT,
		intArrayOf(-0xeeeeef, 0x111111),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private val foldBackShadowDrawableLR = GradientDrawable(
		GradientDrawable.Orientation.LEFT_RIGHT,
		intArrayOf(-0xeeeeef, 0x111111),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private val foldFrontShadowDrawableVLR = GradientDrawable(
		GradientDrawable.Orientation.LEFT_RIGHT,
		intArrayOf(-0x7feeeeef, 0x111111),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private val foldFrontShadowDrawableVRL = GradientDrawable(
		GradientDrawable.Orientation.RIGHT_LEFT,
		intArrayOf(-0x7feeeeef, 0x111111),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private val foldFrontShadowDrawableHTB = GradientDrawable(
		GradientDrawable.Orientation.TOP_BOTTOM,
		intArrayOf(-0x7feeeeef, 0x111111),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private val foldFrontShadowDrawableHBT = GradientDrawable(
		GradientDrawable.Orientation.BOTTOM_TOP,
		intArrayOf(-0x7feeeeef, 0x111111),
	).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
	private var foldCurrentBitmap: Bitmap? = null
	private var foldTargetBitmap: Bitmap? = null
	private var foldCurrentHalfBitmap: Bitmap? = null
	private var foldTargetHalfBitmap: Bitmap? = null
	private val foldBitmapCanvas = Canvas()
	private var foldBitmapBaseIndex: Int = -1
	private var foldBitmapTargetIndex: Int = -1
	private var foldBitmapChapterDelta: Int = 0
	private var foldMaxLength: Float = 0f
	private var foldDegrees: Float = 0f
	private var foldTouchToCornerDistance: Float = 0f
	private var foldCornerX: Float = 0f
	private var foldCornerY: Float = 0f
	private var foldTouchX: Float = 0f
	private var foldTouchY: Float = 0f
	private var foldIsRtOrLb: Boolean = false
	private var foldViewWidth: Int = 0
	private var foldViewHeight: Int = 0
	private var pageSwipeFoldCornerX: Float = 0f
	private var pageSwipeFoldCornerY: Float = 0f
	private var pageSwipeFoldCornerLocked: Boolean = false

	var onPageChangeListener: ((page: Int, total: Int) -> Unit)? = null
	var onTapAreaListener: ((area: TapGridArea) -> Unit)? = null
	var onChapterChangeRequestListener: ((delta: Int) -> Unit)? = null
	var onImageClickListener: ((NovelInlineImageRequest) -> Unit)? = null

	init {
		isClickable = true
		isFocusable = true

		gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean {
				if (!scroller.isFinished) {
					scroller.forceFinished(true)
				}
				return true
			}

			override fun onScroll(
				e1: MotionEvent?,
				e2: MotionEvent,
				distanceX: Float,
				distanceY: Float,
			): Boolean {
				if (settings.readingMode == NovelReadingMode.SCROLL) {
					val oldY = scrollY
					val newY = (oldY + distanceY.toInt()).coerceIn(0, maxScrollOffset)
					if (oldY != newY) {
						scrollTo(0, newY)
						invalidate()
					} else {
						if (oldY <= 0 && distanceY < -touchSlop) {
							onChapterChangeRequestListener?.invoke(-1)
						} else if (oldY >= maxScrollOffset && distanceY > touchSlop) {
							onChapterChangeRequestListener?.invoke(1)
						}
					}
					return true
				}
				return false
			}

			override fun onSingleTapUp(e: MotionEvent): Boolean {
				findInlineImageAt(e.x, e.y)?.let { image ->
					onImageClickListener?.invoke(image)
					return true
				}
				onTapAreaListener?.invoke(getTapArea(e.x, e.y))
				return true
			}

			override fun onFling(
				e1: MotionEvent?,
				e2: MotionEvent,
				velocityX: Float,
				velocityY: Float,
			): Boolean {
				if (settings.readingMode == NovelReadingMode.SCROLL && abs(velocityY) > abs(velocityX)) {
					isFlinging = true
					scroller.fling(0, scrollY, 0, -velocityY.toInt(), 0, 0, 0, maxScrollOffset)
					invalidate()
					return true
				}
				return false
			}
		})
	}

	private fun isNightMode(): Boolean = resources.configuration.uiMode and
		android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
		android.content.res.Configuration.UI_MODE_NIGHT_YES

	override fun onTouchEvent(event: MotionEvent): Boolean {
		if (settings.readingMode != NovelReadingMode.SCROLL && isAwaitingChapterTransitionContent) {
			return true
		}
		if (settings.readingMode != NovelReadingMode.SCROLL && handlePagedTouch(event)) {
			return true
		}
		val handled = gestureDetector.onTouchEvent(event)
		return handled || super.onTouchEvent(event)
	}

	override fun computeScroll() {
		if (scroller.computeScrollOffset()) {
			val oldY = scrollY
			val y = scroller.currY
			if (oldY != y) {
				scrollTo(0, y)
			}
			invalidate()
		} else if (isFlinging) {
			isFlinging = false
			val currentScroll = scrollY
			if (currentScroll <= 0 && scrollY < scroller.startY) {
				onChapterChangeRequestListener?.invoke(-1)
			} else if (currentScroll >= maxScrollOffset && scrollY > scroller.startY) {
				onChapterChangeRequestListener?.invoke(1)
			}
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		canvas.drawColor(palette.backgroundColor)

		if (pages.isEmpty()) {
			return
		}

		if (settings.readingMode == NovelReadingMode.SCROLL) {
			drawPage(canvas, pages[0], 0f, width.toFloat())
			return
		}

		if (pageSwipeBaseIndex >= 0 && pageSwipeTargetIndex >= 0) {
			drawPageSwipe(canvas)
			return
		}

		if (currentPageIndex !in pages.indices) return
		drawSpread(canvas, currentPageIndex, 0f)
	}

	private fun drawPageSwipe(canvas: Canvas) {
		if (settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION) {
			drawPageSimulationSwipe(canvas)
			return
		}
		if (pageSwipeOffsetX == 0f) {
			drawSpread(canvas, pageSwipeBaseIndex, 0f)
			return
		}
		drawSpread(canvas, pageSwipeBaseIndex, pageSwipeOffsetX)
		if (pageSwipeChapterDelta != 0) {
			drawChapterBoundaryPreview(canvas, pageSwipeChapterDelta, pageSwipeOffsetX)
			return
		}
		val targetOffset = if (pageSwipeOffsetX < 0f) {
			pageSwipeOffsetX + width
		} else {
			pageSwipeOffsetX - width
		}
		drawSpread(canvas, pageSwipeTargetIndex, targetOffset)
	}

	private fun drawPageSimulationSwipe(canvas: Canvas) {
		if (width <= 0 || height <= 0) return
		val forward = pageSwipeDirection < 0
		val currentBitmap = getFoldCurrentBitmap() ?: run {
			drawSpread(canvas, pageSwipeBaseIndex, pageSwipeOffsetX)
			return
		}
		val targetBitmap = getFoldTargetBitmap(forward) ?: run {
			drawSpread(canvas, pageSwipeBaseIndex, pageSwipeOffsetX)
			return
		}
		calcFoldCornerXY(forward)
		calcFoldPoints(
			forward = forward,
			touchX = getSimulationTouchX(),
			touchY = pageSwipeCurrentY,
		)
		drawFoldCurrentPageArea(canvas, currentBitmap)
		drawFoldNextPageAreaAndShadow(canvas, targetBitmap)
		drawFoldCurrentPageShadow(canvas)
		drawFoldCurrentBackArea(canvas)
	}

	private fun drawSpread(canvas: Canvas, startIndex: Int, offsetX: Float) {
		val page = pages.getOrNull(startIndex) ?: return
		if (isDualPage && startIndex < pages.lastIndex) {
			val nextPage = pages[startIndex + 1]
			drawPage(canvas, page, offsetX, offsetX + width / 2f)
			drawPage(canvas, nextPage, offsetX + width / 2f, offsetX + width.toFloat())
		} else {
			drawPage(canvas, page, offsetX, offsetX + width.toFloat())
		}
	}

	private fun drawChapterBoundaryPreview(canvas: Canvas, chapterDelta: Int, offsetX: Float) {
		val previewPages = if (chapterDelta > 0) nextChapterPreviewPages else previousChapterPreviewPages
		if (previewPages.isEmpty()) {
			return
		}
		val previewOffset = if (offsetX < 0f) {
			offsetX + width
		} else {
			offsetX - width
		}
		val startIndex = if (chapterDelta < 0) {
			getLastBoundaryPreviewStartIndex(previewPages.size)
		} else {
			0
		}
		drawPreviewSpread(canvas, previewPages, startIndex, previewOffset)
	}

	private fun drawPreviewSpread(
		canvas: Canvas,
		previewPages: List<PageInfo>,
		startIndex: Int,
		offsetX: Float,
	) {
		val page = previewPages.getOrNull(startIndex) ?: return
		if (isDualPage) {
			val nextPage = previewPages.getOrNull(startIndex + 1)
			drawPage(canvas, page, offsetX, offsetX + width / 2f)
			if (nextPage != null) {
				drawPage(canvas, nextPage, offsetX + width / 2f, offsetX + width.toFloat())
			}
		} else {
			drawPage(canvas, page, offsetX, offsetX + width.toFloat())
		}
	}

	private fun getFoldCurrentBitmap(): Bitmap? {
		if (
			foldCurrentBitmap == null ||
			foldCurrentBitmap?.width != width ||
			foldCurrentBitmap?.height != height ||
			foldBitmapBaseIndex != pageSwipeBaseIndex
		) {
			foldCurrentBitmap = renderFoldBitmap(foldCurrentBitmap) { canvas ->
				drawSpread(canvas, pageSwipeBaseIndex, 0f)
			}
			foldBitmapBaseIndex = pageSwipeBaseIndex
		}
		return foldCurrentBitmap
	}

	private fun getFoldTargetBitmap(forward: Boolean): Bitmap? {
		if (
			foldTargetBitmap == null ||
			foldTargetBitmap?.width != width ||
			foldTargetBitmap?.height != height ||
			foldBitmapTargetIndex != pageSwipeTargetIndex ||
			foldBitmapChapterDelta != pageSwipeChapterDelta
		) {
			foldTargetBitmap = renderFoldBitmap(foldTargetBitmap) { canvas ->
				if (pageSwipeChapterDelta != 0) {
					val previewPages = if (pageSwipeChapterDelta > 0) {
						nextChapterPreviewPages
					} else {
						previousChapterPreviewPages
					}
					val previewIndex = if (pageSwipeChapterDelta < 0) {
						getLastBoundaryPreviewStartIndex(previewPages.size)
					} else {
						0
					}
					drawPreviewSpread(canvas, previewPages, previewIndex, 0f)
				} else {
					drawSpread(canvas, pageSwipeTargetIndex, 0f)
				}
			}
			foldBitmapTargetIndex = pageSwipeTargetIndex
			foldBitmapChapterDelta = pageSwipeChapterDelta
		}
		return foldTargetBitmap?.takeIf { forward || pageSwipeTargetIndex >= 0 || pageSwipeChapterDelta != 0 }
	}

	private inline fun renderFoldBitmap(reusable: Bitmap?, draw: (Canvas) -> Unit): Bitmap? {
		if (width <= 0 || height <= 0) return null
		val bitmap = reusable
			?.takeIf { it.width == width && it.height == height && !it.isRecycled }
			?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		foldBitmapCanvas.setBitmap(bitmap)
		foldBitmapCanvas.drawColor(palette.backgroundColor)
		draw(foldBitmapCanvas)
		foldBitmapCanvas.setBitmap(null)
		return bitmap
	}

	private fun calcFoldCornerXY(forward: Boolean, viewWidth: Int = width, viewHeight: Int = height) {
		foldViewWidth = viewWidth
		foldViewHeight = viewHeight
		if (!pageSwipeFoldCornerLocked) {
			lockFoldCorner(forward = forward)
		}
		foldCornerX = pageSwipeFoldCornerX.coerceIn(0f, viewWidth.toFloat())
		foldCornerY = pageSwipeFoldCornerY.coerceIn(0f, viewHeight.toFloat())
		foldIsRtOrLb = (foldCornerX == 0f && foldCornerY == viewHeight.toFloat()) ||
			(foldCornerY == 0f && foldCornerX == viewWidth.toFloat())
	}

	private fun calcFoldCornerXYFromPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
		foldCornerX = if (x <= viewWidth / 2f) 0f else viewWidth.toFloat()
		foldCornerY = if (y <= viewHeight / 2f) 0f else viewHeight.toFloat()
		foldIsRtOrLb = (foldCornerX == 0f && foldCornerY == viewHeight.toFloat()) ||
			(foldCornerY == 0f && foldCornerX == viewWidth.toFloat())
	}

	private fun lockFoldCorner(forward: Boolean) {
		if (width <= 0 || height <= 0) return
		val cornerPoint = when {
			!forward && pageSwipeDownX < width / 2f -> pageSwipeDownX to height.toFloat()
			!forward -> (width - pageSwipeDownX) to height.toFloat()
			forward && pageSwipeDownX < width / 2f -> (width - pageSwipeDownX) to pageSwipeDownY
			else -> pageSwipeDownX to pageSwipeDownY
		}
		calcFoldCornerXYFromPoint(
			x = cornerPoint.first.coerceIn(1f, width - 1f),
			y = cornerPoint.second.coerceIn(1f, height - 1f),
			viewWidth = width,
			viewHeight = height,
		)
		pageSwipeFoldCornerX = foldCornerX
		pageSwipeFoldCornerY = foldCornerY
		pageSwipeFoldCornerLocked = true
	}

	private fun calcFoldPoints(
		forward: Boolean,
		viewWidth: Int = width,
		viewHeight: Int = height,
		touchX: Float = pageSwipeCurrentX,
		touchY: Float = pageSwipeCurrentY,
	) {
		foldViewWidth = viewWidth
		foldViewHeight = viewHeight
		foldMaxLength = hypot(viewWidth.toDouble(), viewHeight.toDouble()).toFloat()
		foldTouchX = touchX
		foldTouchY = touchY.coerceIn(1f, viewHeight - 1f)

		var middleX = (foldTouchX + foldCornerX) / 2f
		var middleY = (foldTouchY + foldCornerY) / 2f
		foldControl1.x = middleX - (foldCornerY - middleY) * (foldCornerY - middleY) /
			safeDenominator(foldCornerX - middleX)
		foldControl1.y = foldCornerY
		foldControl2.x = foldCornerX
		foldControl2.y = middleY - (foldCornerX - middleX) * (foldCornerX - middleX) /
			safeDenominator(foldCornerY - middleY)
		foldStart1.x = foldControl1.x - (foldCornerX - foldControl1.x) / 2f
		foldStart1.y = foldCornerY

		if (foldTouchX > 0f && foldTouchX < viewWidth && (foldStart1.x < 0f || foldStart1.x > viewWidth)) {
			if (foldStart1.x < 0f) {
				foldStart1.x = viewWidth - foldStart1.x
			}
			val f1 = abs(foldCornerX - foldTouchX).coerceAtLeast(0.1f)
			val f2 = viewWidth * f1 / safeDenominator(foldStart1.x)
			foldTouchX = abs(foldCornerX - f2).coerceIn(0.1f, viewWidth - 0.1f)
			val f3 = abs(foldCornerX - foldTouchX) * abs(foldCornerY - foldTouchY) / f1
			foldTouchY = abs(foldCornerY - f3).coerceIn(1f, viewHeight - 1f)
			middleX = (foldTouchX + foldCornerX) / 2f
			middleY = (foldTouchY + foldCornerY) / 2f
			foldControl1.x = middleX - (foldCornerY - middleY) * (foldCornerY - middleY) /
				safeDenominator(foldCornerX - middleX)
			foldControl1.y = foldCornerY
			foldControl2.x = foldCornerX
			foldControl2.y = middleY - (foldCornerX - middleX) * (foldCornerX - middleX) /
				safeDenominator(foldCornerY - middleY)
			foldStart1.x = foldControl1.x - (foldCornerX - foldControl1.x) / 2f
		}

		foldStart2.x = foldCornerX
		foldStart2.y = foldControl2.y - (foldCornerY - foldControl2.y) / 2f
		setFoldCross(foldEnd1, foldTouchX, foldTouchY, foldControl1, foldStart1, foldStart2)
		setFoldCross(foldEnd2, foldTouchX, foldTouchY, foldControl2, foldStart1, foldStart2)
		foldVertex1.x = (foldStart1.x + 2f * foldControl1.x + foldEnd1.x) / 4f
		foldVertex1.y = (2f * foldControl1.y + foldStart1.y + foldEnd1.y) / 4f
		foldVertex2.x = (foldStart2.x + 2f * foldControl2.x + foldEnd2.x) / 4f
		foldVertex2.y = (2f * foldControl2.y + foldStart2.y + foldEnd2.y) / 4f
		foldTouchToCornerDistance = hypot(
			(foldTouchX - foldCornerX).toDouble(),
			(foldTouchY - foldCornerY).toDouble(),
		).toFloat()
	}

	private fun drawFoldCurrentBackArea(canvas: Canvas) {
		val i = ((foldStart1.x + foldControl1.x) / 2f).toInt()
		val f1 = abs(i - foldControl1.x)
		val i1 = ((foldStart2.y + foldControl2.y) / 2f).toInt()
		val f2 = abs(i1 - foldControl2.y)
		val f3 = min(f1, f2)
		foldBackPath.reset()
		foldBackPath.moveTo(foldVertex2.x, foldVertex2.y)
		foldBackPath.lineTo(foldVertex1.x, foldVertex1.y)
		foldBackPath.lineTo(foldEnd1.x, foldEnd1.y)
		foldBackPath.lineTo(foldTouchX, foldTouchY)
		foldBackPath.lineTo(foldEnd2.x, foldEnd2.y)
		foldBackPath.close()
		val folderShadowDrawable: GradientDrawable
		val left: Int
		val right: Int
		if (foldIsRtOrLb) {
			left = (foldStart1.x - 1).toInt()
			right = (foldStart1.x + f3 + 1).toInt()
			folderShadowDrawable = foldFolderShadowDrawableLR
		} else {
			left = (foldStart1.x - f3 - 1).toInt()
			right = (foldStart1.x + 1).toInt()
			folderShadowDrawable = foldFolderShadowDrawableRL
		}

		canvas.save()
		canvas.clipPath(foldPath)
		clipPathIntersect(canvas, foldBackPath)
		canvas.drawColor(palette.backgroundColor)
		canvas.rotate(foldDegrees, foldStart1.x, foldStart1.y)
		folderShadowDrawable.setBounds(left, foldStart1.y.toInt(), right, (foldStart1.y + foldMaxLength).toInt())
		folderShadowDrawable.draw(canvas)
		canvas.restore()
	}

	private fun drawFoldCurrentPageShadow(canvas: Canvas) {
		val shadowOnRight = foldIsRtOrLb
		val degree = if (shadowOnRight) {
			Math.PI / 4 - atan2(foldControl1.y - foldTouchY, foldTouchX - foldControl1.x)
		} else {
			Math.PI / 4 - atan2(foldTouchY - foldControl1.y, foldTouchX - foldControl1.x)
		}
		val d1 = (25f * 1.414f * cos(degree)).toFloat()
		val d2 = (25f * 1.414f * sin(degree)).toFloat()
		val x = foldTouchX + d1
		val y = if (shadowOnRight) foldTouchY + d2 else foldTouchY - d2
		foldBackPath.reset()
		foldBackPath.moveTo(x, y)
		foldBackPath.lineTo(foldTouchX, foldTouchY)
		foldBackPath.lineTo(foldControl1.x, foldControl1.y)
		foldBackPath.lineTo(foldStart1.x, foldStart1.y)
		foldBackPath.close()
		canvas.save()
		clipOutPath(canvas, foldPath)
		clipPathIntersect(canvas, foldBackPath)

		var leftX: Int
		var rightX: Int
		var currentPageShadow: GradientDrawable
		if (shadowOnRight) {
			leftX = foldControl1.x.toInt()
			rightX = (foldControl1.x + 25).toInt()
			currentPageShadow = foldFrontShadowDrawableVLR
		} else {
			leftX = (foldControl1.x - 25).toInt()
			rightX = (foldControl1.x + 1).toInt()
			currentPageShadow = foldFrontShadowDrawableVRL
		}
		var rotateDegrees = Math.toDegrees(
			atan2(foldTouchX - foldControl1.x, foldControl1.y - foldTouchY).toDouble(),
		).toFloat()
		canvas.rotate(rotateDegrees, foldControl1.x, foldControl1.y)
		currentPageShadow.setBounds(leftX, (foldControl1.y - foldMaxLength).toInt(), rightX, foldControl1.y.toInt())
		currentPageShadow.draw(canvas)
		canvas.restore()

		foldBackPath.reset()
		foldBackPath.moveTo(x, y)
		foldBackPath.lineTo(foldTouchX, foldTouchY)
		foldBackPath.lineTo(foldControl2.x, foldControl2.y)
		foldBackPath.lineTo(foldStart2.x, foldStart2.y)
		foldBackPath.close()
		canvas.save()
		clipOutPath(canvas, foldPath)
		canvas.clipPath(foldBackPath)

		if (shadowOnRight) {
			leftX = foldControl2.y.toInt()
			rightX = (foldControl2.y + 25).toInt()
			currentPageShadow = foldFrontShadowDrawableHTB
		} else {
			leftX = (foldControl2.y - 25).toInt()
			rightX = (foldControl2.y + 1).toInt()
			currentPageShadow = foldFrontShadowDrawableHBT
		}
		rotateDegrees = Math.toDegrees(
			atan2(foldControl2.y - foldTouchY, foldControl2.x - foldTouchX).toDouble(),
		).toFloat()
		canvas.rotate(rotateDegrees, foldControl2.x, foldControl2.y)
		val temp = if (foldControl2.y < 0f) {
			(foldControl2.y - foldViewHeight).toDouble()
		} else {
			foldControl2.y.toDouble()
		}
		val hmg = hypot(foldControl2.x.toDouble(), temp)
		if (hmg > foldMaxLength) {
			currentPageShadow.setBounds(
				(foldControl2.x - 25 - hmg).toInt(),
				leftX,
				(foldControl2.x + foldMaxLength - hmg).toInt(),
				rightX,
			)
		} else {
			currentPageShadow.setBounds(
				(foldControl2.x - foldMaxLength).toInt(),
				leftX,
				foldControl2.x.toInt(),
				rightX,
			)
		}
		currentPageShadow.draw(canvas)
		canvas.restore()
	}

	private fun drawFoldNextPageAreaAndShadow(canvas: Canvas, bitmap: Bitmap) {
		foldBackPath.reset()
		foldBackPath.moveTo(foldStart1.x, foldStart1.y)
		foldBackPath.lineTo(foldVertex1.x, foldVertex1.y)
		foldBackPath.lineTo(foldVertex2.x, foldVertex2.y)
		foldBackPath.lineTo(foldStart2.x, foldStart2.y)
		foldBackPath.lineTo(foldCornerX, foldCornerY)
		foldBackPath.close()
		foldDegrees = Math.toDegrees(
			atan2((foldControl1.x - foldCornerX).toDouble(), foldControl2.y - foldCornerY.toDouble()),
		).toFloat()
		val leftX: Int
		val rightX: Int
		val backShadowDrawable: GradientDrawable
		if (foldIsRtOrLb) {
			leftX = foldStart1.x.toInt()
			rightX = (foldStart1.x + foldTouchToCornerDistance / 4f).toInt()
			backShadowDrawable = foldBackShadowDrawableLR
		} else {
			leftX = (foldStart1.x - foldTouchToCornerDistance / 4f).toInt()
			rightX = foldStart1.x.toInt()
			backShadowDrawable = foldBackShadowDrawableRL
		}
		canvas.save()
		canvas.clipPath(foldPath)
		clipPathIntersect(canvas, foldBackPath)
		canvas.drawBitmap(bitmap, 0f, 0f, null)
		canvas.rotate(foldDegrees, foldStart1.x, foldStart1.y)
		backShadowDrawable.setBounds(leftX, foldStart1.y.toInt(), rightX, (foldMaxLength + foldStart1.y).toInt())
		backShadowDrawable.draw(canvas)
		canvas.restore()
	}

	private fun drawFoldCurrentPageArea(canvas: Canvas, bitmap: Bitmap) {
		foldPath.reset()
		foldPath.moveTo(foldStart1.x, foldStart1.y)
		foldPath.quadTo(foldControl1.x, foldControl1.y, foldEnd1.x, foldEnd1.y)
		foldPath.lineTo(foldTouchX, foldTouchY)
		foldPath.lineTo(foldEnd2.x, foldEnd2.y)
		foldPath.quadTo(foldControl2.x, foldControl2.y, foldStart2.x, foldStart2.y)
		foldPath.lineTo(foldCornerX, foldCornerY)
		foldPath.close()

		canvas.save()
		clipOutPath(canvas, foldPath)
		canvas.drawBitmap(bitmap, 0f, 0f, null)
		canvas.restore()
	}

	@Suppress("DEPRECATION")
	private fun clipOutPath(canvas: Canvas, path: Path) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			canvas.clipOutPath(path)
		} else {
			canvas.clipPath(path, android.graphics.Region.Op.XOR)
		}
	}

	@Suppress("DEPRECATION")
	private fun clipPathIntersect(canvas: Canvas, path: Path) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			canvas.clipPath(path)
		} else {
			canvas.clipPath(path, android.graphics.Region.Op.INTERSECT)
		}
	}

	private fun setFoldCross(
		out: PointF,
		p1x: Float,
		p1y: Float,
		p2: PointF,
		p3: PointF,
		p4: PointF,
	) {
		val a1 = (p2.y - p1y) / safeDenominator(p2.x - p1x)
		val b1 = (p1x * p2.y - p2.x * p1y) / safeDenominator(p1x - p2.x)
		val a2 = (p4.y - p3.y) / safeDenominator(p4.x - p3.x)
		val b2 = (p3.x * p4.y - p4.x * p3.y) / safeDenominator(p3.x - p4.x)
		out.x = (b2 - b1) / safeDenominator(a1 - a2)
		out.y = a1 * out.x + b1
	}

	private fun safeDenominator(value: Float): Float {
		if (abs(value) >= 0.1f) return value
		return if (value < 0f) -0.1f else 0.1f
	}

	private fun drawPage(canvas: Canvas, page: PageInfo, left: Float, right: Float) {
		canvas.save()
		val x = left + paddingLeft + settings.marginHorizontal
		val y = paddingTop + settings.marginVertical.toFloat()
		canvas.translate(x, y)

		highlightRange?.let { range ->
			val intersectStart = max(page.startOffset, range.first)
			val intersectEnd = min(page.endOffset, range.last + 1)
			if (intersectStart < intersectEnd && page.layout != null) {
				val path = Path()
				val localStart = intersectStart - page.startOffset
				val localEnd = intersectEnd - page.startOffset
				page.layout.getSelectionPath(localStart, localEnd, path)
				canvas.drawPath(path, highlightPaint)
			}
		}

		page.layout?.draw(canvas)

		for (imageSpan in page.images) {
			val bitmap = loadImage(imageSpan.imagePath)
			if (bitmap != null) {
				val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
				val dstRect = getNovelImageDisplayRect(
					imagePath = imageSpan.imagePath,
					reservedWidth = imageSpan.width,
					reservedHeight = imageSpan.height,
					yPosition = imageSpan.yPosition,
				)
				canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint)
			} else {
				drawImagePlaceholder(canvas, imageSpan)
			}
		}

		canvas.restore()
	}

	private fun drawImagePlaceholder(canvas: Canvas, imageSpan: ImageSpan) {
		val placeholderPaint = Paint().apply {
			color = palette.placeholderColor
			style = Paint.Style.FILL
		}
		val placeholderRect = RectF(
			0f,
			imageSpan.yPosition,
			imageSpan.width,
			imageSpan.yPosition + imageSpan.height,
		)
		canvas.drawRect(placeholderRect, placeholderPaint)
		val errorPaint = Paint().apply {
			color = palette.placeholderTextColor
			textSize = 14f * resources.displayMetrics.density
			textAlign = Paint.Align.CENTER
		}
		val errorText = "…"
		canvas.drawText(
			errorText,
			imageSpan.width / 2,
			imageSpan.yPosition + imageSpan.height / 2,
			errorPaint,
		)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (w > 0 && h > 0) {
			repaginate()
			repaginateBoundaryPreviews()
		}
	}

	fun setContent(
		content: String,
		resetPage: Boolean = true,
		suppressNotification: Boolean = false,
		initialPageIndex: Int = 0,
		initialProgressRatio: Float? = null,
	) {
		chapterContent = content
		suppressPageChangeNotification = suppressNotification

		if (tryAdoptChapterBoundaryPreview(content, resetPage, initialPageIndex, initialProgressRatio)) {
			loadingImages.clear()
			failedImages.clear()
			return
		}

		if (resetPage) {
			pendingPageIndex = initialPageIndex
			pendingProgressRatio = initialProgressRatio
			// Resolve the ratio after paginating the new chapter, using its own length.
			pendingTargetOffset = null
			pendingBiasToEnd = false
			currentPageIndex = 0
		} else {
			pendingPageIndex = -2
			pendingProgressRatio = null
			pendingTargetOffset = null
			pendingBiasToEnd = false
		}

		if (width > 0 && height > 0) {
			repaginate()
		} else {
			post { repaginate() }
		}
		loadingImages.clear()
		failedImages.clear()
	}

	private fun tryAdoptChapterBoundaryPreview(
		content: String,
		resetPage: Boolean,
		initialPageIndex: Int,
		initialProgressRatio: Float?,
	): Boolean {
		if (
			settings.readingMode == NovelReadingMode.SCROLL ||
			!resetPage ||
			width <= 0 ||
			height <= 0
		) {
			return false
		}
		val adoptingNext = isAwaitingChapterTransitionContent &&
			initialPageIndex == 0 &&
			nextChapterPreviewText == content &&
			nextChapterPreviewPages.isNotEmpty()
		val adoptingPrevious = isAwaitingChapterTransitionContent &&
			initialPageIndex == -1 &&
			previousChapterPreviewText == content &&
			previousChapterPreviewPages.isNotEmpty()
		if (!adoptingNext && !adoptingPrevious) {
			return false
		}
		val adoptedPages = if (adoptingNext) nextChapterPreviewPages else previousChapterPreviewPages
		val adoptedPageIndex = when {
			initialProgressRatio != null -> {
				val targetOffset = (content.length * initialProgressRatio).roundToInt()
				novelRestoredPage(targetOffset, content.length, adoptedPages.map { it.startOffset })
			}

			initialPageIndex == -1 -> getLastBoundaryPreviewStartIndex(adoptedPages.size)

			else -> initialPageIndex.coerceIn(0, max(0, adoptedPages.size - 1))
		}
		pages = adoptedPages
		paginatedTotalLength = content.length
		currentPageIndex = adoptedPageIndex
		pendingPageIndex = -2
		pendingProgressRatio = null
		pendingTargetOffset = null
		pendingBiasToEnd = false
		maxScrollOffset = 0
		if (scrollY != 0) {
			scrollTo(0, 0)
		}
		resetPageSwipeState()
		invalidate()
		notifyPageChanged()
		return true
	}

	fun updateSettings(newSettings: NovelReaderSettings) {
		settings = newSettings
		textPaint.textSize = resources.resolveSp(settings.fontSizeSp)
		updatePalette()
		repaginate()
		repaginateBoundaryPreviews()
	}

	fun updatePalette(
		palette: NovelReaderPalette = novelReaderPalette(
			preset = settings.themePreset,
			isDarkTheme = isNightMode(),
		),
	) {
		this.palette = palette
		textPaint.color = palette.textColor
		highlightPaint.color = palette.highlightColor
		invalidate()
	}

	fun setDualPageMode(enabled: Boolean) {
		if (isDualPage != enabled) {
			isDualPage = enabled
			repaginate()
			repaginateBoundaryPreviews()
		}
	}

	fun setFooterHeight(height: Int) {
		if (footerHeight != height) {
			footerHeight = height
			repaginate()
			repaginateBoundaryPreviews()
		}
	}

	/**
	 * Applies system-bar insets as view padding and repaginates: page layout is derived from
	 * the padded area, so inset changes must re-flow the text.
	 */
	fun applyContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
		if (paddingLeft == left && paddingTop == top && paddingRight == right && paddingBottom == bottom) {
			return
		}
		setPadding(left, top, right, bottom)
		if (width > 0 && height > 0) {
			repaginate()
			repaginateBoundaryPreviews()
		}
	}

	fun setChapterBoundaryPreview(chapterDelta: Int, content: String?) {
		when {
			chapterDelta > 0 -> nextChapterPreviewText = content?.takeIf { it.isNotBlank() }
			chapterDelta < 0 -> previousChapterPreviewText = content?.takeIf { it.isNotBlank() }
			else -> return
		}
		repaginateBoundaryPreview(chapterDelta)
	}

	fun clearChapterBoundaryPreviews() {
		previousChapterPreviewText = null
		nextChapterPreviewText = null
		previousChapterPreviewPages = emptyList()
		nextChapterPreviewPages = emptyList()
		invalidate()
	}

	fun setHighlightRange(range: IntRange?) {
		if (highlightRange != range) {
			highlightRange = range
			invalidate()
		}
	}

	fun goToPage(page: Int) {
		if (settings.readingMode == NovelReadingMode.SCROLL) {
			val h = scrollViewportHeight()
			if (h > 0) {
				val targetScroll = (page * h).coerceIn(0, maxScrollOffset)
				scrollTo(0, targetScroll)
				notifyPageChanged()
			}
			return
		}
		if (page in pages.indices) {
			currentPageIndex = page
			invalidate()
			notifyPageChanged()
		}
	}

	fun nextPage(): Boolean {
		if (settings.readingMode == NovelReadingMode.SCROLL) {
			val h = scrollViewportHeight()
			if (h > 0 && scrollY < maxScrollOffset) {
				val targetScroll = (scrollY + h).coerceIn(0, maxScrollOffset)
				scroller.startScroll(0, scrollY, 0, targetScroll - scrollY, 250)
				invalidate()
				return true
			}
			return false
		}
		val step = getPageTurnStep()
		if (currentPageIndex + step < pages.size) {
			animatePageSettle(targetIndex = currentPageIndex + step, commit = true, direction = -1)
			return true
		}
		return false
	}

	fun previousPage(): Boolean {
		if (settings.readingMode == NovelReadingMode.SCROLL) {
			val h = scrollViewportHeight()
			if (h > 0 && scrollY > 0) {
				val targetScroll = (scrollY - h).coerceIn(0, maxScrollOffset)
				scroller.startScroll(0, scrollY, 0, targetScroll - scrollY, 250)
				invalidate()
				return true
			}
			return false
		}
		val step = getPageTurnStep()
		if (currentPageIndex - step >= 0) {
			animatePageSettle(targetIndex = currentPageIndex - step, commit = true, direction = 1)
			return true
		}
		return false
	}

	fun getCurrentPage(): Int = if (settings.readingMode == NovelReadingMode.SCROLL) {
		getDisplayPageIndex()
	} else {
		currentPageIndex
	}

	fun getTotalPages(): Int = if (settings.readingMode == NovelReadingMode.SCROLL) {
		getDisplayPageCount()
	} else {
		pages.size
	}

	fun getCurrentCharOffset(): Int {
		if (settings.readingMode == NovelReadingMode.SCROLL && pages.isNotEmpty()) {
			val layout = pages[0].layout ?: return 0
			val line = layout.getLineForVertical(scrollY)
			return layout.getLineStart(line)
		}
		return pages.getOrNull(currentPageIndex)?.startOffset ?: 0
	}

	fun getChapterLength(): Int = paginatedTotalLength

	fun isDualPage(): Boolean = isDualPage

	fun getProgressRatio(): Float {
		pendingProgressRatio?.let { return it.coerceIn(0f, 1f) }
		val total = paginatedTotalLength
		if (total == 0) return 0f
		return novelPagedProgress(getCurrentCharOffset(), total, currentPageIndex, pages.size, isDualPage)
	}

	fun getDisplayPageIndex(): Int {
		if (settings.readingMode == NovelReadingMode.SCROLL) {
			val h = scrollViewportHeight()
			if (h <= 0) return 0
			return (scrollY / h).coerceIn(0, max(0, getDisplayPageCount() - 1))
		}
		return if (isDualPage) currentPageIndex / 2 else currentPageIndex
	}

	fun getDisplayPageCount(): Int {
		if (settings.readingMode == NovelReadingMode.SCROLL) {
			val h = scrollViewportHeight()
			if (h <= 0) return 1
			val contentHeight = pages.getOrNull(0)?.layout?.height ?: 0
			return max(1, kotlin.math.ceil(contentHeight.toFloat() / h).toInt())
		}
		return if (isDualPage) {
			(pages.size + 1) / 2
		} else {
			pages.size
		}
	}

	fun setPendingProgressRatio(ratio: Float) {
		pendingProgressRatio = ratio.coerceIn(0f, 1f)
		val total = if (paginatedTotalLength > 0) paginatedTotalLength else chapterContent.length
		pendingTargetOffset = (total * pendingProgressRatio!!).toInt()
		pendingBiasToEnd = false
	}

	private fun scrollViewportHeight(): Int {
		return height - paddingTop - paddingBottom - footerHeight - settings.marginVertical * 2
	}

	private fun findClosestPageForOffset(offset: Int, biasToEnd: Boolean): Int {
		if (pages.isEmpty()) return 0
		if (pendingProgressRatio != null) {
			return novelRestoredPage(offset, paginatedTotalLength, pages.map { it.startOffset })
		}
		val clamped = offset.coerceIn(0, paginatedTotalLength)
		val exact = pages.indexOfFirst { clamped in it.startOffset until it.endOffset }
		if (exact != -1) {
			if (!biasToEnd) return exact
			var idx = exact
			while (idx + 1 < pages.size && clamped >= pages[idx + 1].startOffset) {
				idx++
			}
			return idx
		}
		var bestIndex = 0
		var bestDiff = Int.MAX_VALUE
		pages.forEachIndexed { index, page ->
			val diff = abs(page.startOffset - clamped)
			if (diff < bestDiff) {
				bestDiff = diff
				bestIndex = index
			}
		}
		return bestIndex
	}

	private fun repaginate() {
		resetPageSwipeState()
		if (width == 0 || height == 0 || chapterContent.isEmpty()) {
			pages = emptyList()
			invalidate()
			return
		}

		val savedCharPosition = pages.getOrNull(currentPageIndex)?.startOffset ?: 0
		val savedProgressRatio = if (paginatedTotalLength > 0) {
			savedCharPosition.toFloat() / paginatedTotalLength
		} else {
			0f
		}

		val availableWidth = width - paddingLeft - paddingRight
		val availableHeight = height - paddingTop - paddingBottom - footerHeight

		val pageWidth = if (isDualPage) {
			(availableWidth / 2) - settings.marginHorizontal * 2
		} else {
			availableWidth - settings.marginHorizontal * 2
		}
		val pageHeight = if (settings.readingMode == NovelReadingMode.SCROLL) {
			10_000_000
		} else {
			availableHeight - settings.marginVertical * 2
		}

		if (pageWidth <= 0 || pageHeight <= 0) {
			pages = emptyList()
			paginatedTotalLength = 0
			invalidate()
			return
		}

		pages = paginateText(chapterContent, pageWidth, pageHeight)

		val targetCharOffset = when {
			pendingTargetOffset != null -> pendingTargetOffset!!
			pendingProgressRatio != null -> (paginatedTotalLength * pendingProgressRatio!!).roundToInt()
			pendingPageIndex == -1 -> paginatedTotalLength
			(savedCharPosition > 0 || savedProgressRatio > 0f) ->
				(paginatedTotalLength * savedProgressRatio).roundToInt()

			else -> 0
		}.coerceIn(0, paginatedTotalLength)

		when {
			pendingPageIndex == -1 -> {
				currentPageIndex = getLastBoundaryPreviewStartIndex(pages.size)
				pendingPageIndex = -2
			}

			pendingTargetOffset != null || pendingProgressRatio != null -> {
				currentPageIndex = findClosestPageForOffset(targetCharOffset, pendingBiasToEnd)
				pendingPageIndex = -2
				pendingTargetOffset = null
				pendingProgressRatio = null
				pendingBiasToEnd = false
			}

			pendingPageIndex >= 0 -> {
				currentPageIndex = pendingPageIndex.coerceIn(0, max(0, pages.size - 1))
				pendingPageIndex = -2
			}

			(savedCharPosition > 0 || savedProgressRatio > 0f) && pages.isNotEmpty() -> {
				currentPageIndex = findClosestPageForOffset(targetCharOffset, false)
			}

			else -> {
				if (currentPageIndex >= pages.size) {
					currentPageIndex = max(0, pages.size - 1)
				}
			}
		}

		if (settings.readingMode == NovelReadingMode.SCROLL && pages.isNotEmpty()) {
			val contentHeight = pages[0].layout?.height ?: 0
			val viewportTextHeight = availableHeight - settings.marginVertical * 2
			maxScrollOffset = max(0, contentHeight - viewportTextHeight)

			val layout = pages[0].layout
			var targetScroll = 0
			if (layout != null && targetCharOffset > 0) {
				if (targetCharOffset >= paginatedTotalLength) {
					targetScroll = maxScrollOffset
				} else {
					val line = layout.getLineForOffset(targetCharOffset)
					targetScroll = layout.getLineTop(line)
				}
			}
			scrollTo(0, targetScroll.coerceIn(0, maxScrollOffset))
		} else {
			maxScrollOffset = 0
			if (scrollY != 0) scrollTo(0, 0)
		}

		invalidate()
		notifyPageChanged()
	}

	private fun repaginateBoundaryPreviews() {
		repaginateBoundaryPreview(-1)
		repaginateBoundaryPreview(1)
	}

	private fun repaginateBoundaryPreview(chapterDelta: Int) {
		if (width == 0 || height == 0 || settings.readingMode == NovelReadingMode.SCROLL) {
			if (chapterDelta > 0) {
				nextChapterPreviewPages = emptyList()
			} else {
				previousChapterPreviewPages = emptyList()
			}
			return
		}
		val previewText = if (chapterDelta > 0) nextChapterPreviewText else previousChapterPreviewText
		val previewPages = previewText
			?.takeIf { it.isNotBlank() }
			?.let(::paginatePreviewText)
			.orEmpty()
		if (chapterDelta > 0) {
			nextChapterPreviewPages = previewPages
		} else {
			previousChapterPreviewPages = previewPages
		}
		invalidate()
	}

	private fun paginatePreviewText(text: String): List<PageInfo> {
		val availableWidth = width - paddingLeft - paddingRight
		val availableHeight = height - paddingTop - paddingBottom - footerHeight
		val pageWidth = if (isDualPage) {
			(availableWidth / 2) - settings.marginHorizontal * 2
		} else {
			availableWidth - settings.marginHorizontal * 2
		}
		val pageHeight = availableHeight - settings.marginVertical * 2
		if (pageWidth <= 0 || pageHeight <= 0) {
			return emptyList()
		}
		val previousTotalLength = paginatedTotalLength
		return try {
			paginateText(text, pageWidth, pageHeight)
		} finally {
			paginatedTotalLength = previousTotalLength
		}
	}

	private fun getLastBoundaryPreviewStartIndex(pageCount: Int): Int {
		if (pageCount <= 1) {
			return 0
		}
		if (!isDualPage) {
			return pageCount - 1
		}
		return ((pageCount - 1) / 2) * 2
	}

	private fun paginateText(text: String, pageWidth: Int, pageHeight: Int): List<PageInfo> {
		if (text.isBlank()) {
			return listOf(PageInfo(text, null, 0, 0, emptyList()))
		}

		val parsedImages = parseNovelImages(text)
		var processedText = NovelTypography.prepareContentText(parsedImages.text, settings, textPaint)
		val blockImagePaths = parsedImages.blockImagePaths
		val inlineImagePaths = parsedImages.inlineImagePaths
		val hasImages = blockImagePaths.isNotEmpty()

		if (hasImages) {
			val lineHeight = (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent) * settings.lineSpacing
			val maxImageHeight = resources.displayMetrics.heightPixels * 1.5f
			val paraSpacingPx = settings.paragraphSpacing * resources.displayMetrics.density
			val extraSpacerLines = if (paraSpacingPx > 0) {
				max(1, kotlin.math.ceil(paraSpacingPx / lineHeight).toInt())
			} else {
				0
			}

			var newText = processedText
			for (i in blockImagePaths.indices) {
				val placeholder = "[IMAGE_PLACEHOLDER_$i]"
				val imageHeight = getReservedNovelImageHeight(
					imagePath = blockImagePaths[i],
					maxWidth = pageWidth.toFloat(),
					maxHeight = maxImageHeight,
				)
				val spacerLines = (imageHeight / lineHeight).toInt() + (extraSpacerLines * 2).coerceAtLeast(2)
				val spacer = "\n".repeat(spacerLines)
				newText = newText.replace(placeholder, "\n$placeholder\n$spacer\n")
			}
			processedText = newText
		}

		paginatedTotalLength = processedText.length
		val result = mutableListOf<PageInfo>()

		val fullLayout = try {
			createLayout(
				applyInlineImageSpans(processedText, inlineImagePaths) { path -> loadImage(path) },
				pageWidth,
			)
		} catch (e: Exception) {
			return listOf(PageInfo(text, null, 0, text.length, emptyList()))
		}

		val totalLines = fullLayout.lineCount
		var startLine = 0
		val maxImageHeight = resources.displayMetrics.heightPixels * 1.5f

		while (startLine < totalLines) {
			var endLine = startLine
			var accumulatedHeight = 0f

			while (endLine < totalLines) {
				val lineTop = fullLayout.getLineTop(endLine)
				val lineBottom = fullLayout.getLineBottom(endLine)
				val lineHeight = lineBottom - lineTop

				if (accumulatedHeight + lineHeight > pageHeight && endLine > startLine) {
					break
				}

				val lineStart = fullLayout.getLineStart(endLine)
				val lineEnd = fullLayout.getLineEnd(endLine)
				val lineText = processedText.substring(lineStart, lineEnd)

				if (lineText.contains("[IMAGE_PLACEHOLDER")) {
					val imageIndex = Regex("""\[IMAGE_PLACEHOLDER_(\d+)\]""")
						.find(lineText)
						?.groupValues
						?.getOrNull(1)
						?.toIntOrNull()
					val estimatedImageHeight = imageIndex
						?.takeIf { it in blockImagePaths.indices }
						?.let {
							getReservedNovelImageHeight(
								imagePath = blockImagePaths[it],
								maxWidth = pageWidth.toFloat(),
								maxHeight = maxImageHeight,
							)
						}
						?: pageWidth * 0.75f
					if (accumulatedHeight > 0 && accumulatedHeight + estimatedImageHeight > pageHeight) {
						break
					}
				}

				accumulatedHeight += lineHeight
				endLine++
			}

			if (endLine == startLine) {
				endLine = startLine + 1
			}

			val startOffset = fullLayout.getLineStart(startLine)
			val endOffset = if (endLine < totalLines) {
				fullLayout.getLineEnd(endLine - 1)
			} else {
				processedText.length
			}

			val pageText = processedText.substring(startOffset, endOffset)

			val pageImages = mutableListOf<ImageSpan>()
			var displayText = pageText

			if (hasImages) {
				val placeholderPattern = Regex("""\[IMAGE_PLACEHOLDER_(\d+)\]""")
				val displayBuilder = StringBuilder()
				var lastIndex = 0

				placeholderPattern.findAll(pageText).forEach { match ->
					val imageIndex = match.groupValues[1].toInt()
					if (imageIndex < blockImagePaths.size) {
						val imagePath = blockImagePaths[imageIndex]
						val before = pageText.substring(lastIndex, match.range.first)
						displayBuilder.append(before)
						lastIndex = match.range.last + 1

						val tempLayout = createLayout(
							applyInlineImageSpans(displayBuilder.toString(), inlineImagePaths) { path ->
								loadImage(path)
							},
							pageWidth,
						)
						val yPosition = if (tempLayout.lineCount > 0) {
							tempLayout.getLineBottom(tempLayout.lineCount - 1).toFloat()
						} else {
							0f
						}

						val imageWidth = pageWidth.toFloat()
						val imageHeight = getReservedNovelImageHeight(
							imagePath = imagePath,
							maxWidth = imageWidth,
							maxHeight = maxImageHeight,
						)

						pageImages.add(
							ImageSpan(
								imagePath = imagePath,
								yPosition = yPosition,
								width = imageWidth,
								height = imageHeight,
							),
						)
					}
				}

				if (lastIndex < pageText.length) {
					displayBuilder.append(pageText.substring(lastIndex))
				}
				displayText = displayBuilder.toString()
			}

			try {
				val pageLayoutText = applyInlineImageSpans(displayText, inlineImagePaths) { path ->
					loadImage(path)
				}
				val pageLayout = createLayout(pageLayoutText, pageWidth)
				result.add(PageInfo(displayText, pageLayout, startOffset, endOffset, pageImages))
			} catch (e: Exception) {
				// skip broken page
			}

			startLine = endLine
		}

		return result.ifEmpty {
			try {
				val fallbackText = processedText.take(500)
				val fallbackLayoutText = applyInlineImageSpans(fallbackText, inlineImagePaths) { path ->
					loadImage(path)
				}
				listOf(
					PageInfo(
						fallbackText,
						createLayout(fallbackLayoutText, pageWidth),
						0,
						fallbackText.length,
						emptyList(),
					),
				)
			} catch (e: Exception) {
				listOf(PageInfo(text, null, 0, 0, emptyList()))
			}
		}
	}

	private fun createLayout(text: CharSequence, width: Int): StaticLayout = try {
		StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
			.setLineSpacing(0f, settings.lineSpacing)
			.setIncludePad(false)
			.build()
	} catch (e: Exception) {
		@Suppress("DEPRECATION")
		StaticLayout(
			text,
			textPaint,
			width,
			Layout.Alignment.ALIGN_NORMAL,
			1.0f,
			0f,
			false,
		)
	}

	private fun notifyPageChanged() {
		if (!suppressPageChangeNotification) {
			onPageChangeListener?.invoke(currentPageIndex, pages.size)
		}
	}

	private fun findInlineImageAt(x: Float, y: Float): NovelInlineImageRequest? {
		if (pages.isEmpty() || settings.readingMode == NovelReadingMode.SCROLL) {
			return null
		}
		val availableWidth = width - paddingLeft - paddingRight
		val pageWidth = if (isDualPage) {
			(availableWidth / 2f) - settings.marginHorizontal * 2f
		} else {
			availableWidth - settings.marginHorizontal * 2f
		}
		if (pageWidth <= 0f) {
			return null
		}
		val pageTop = paddingTop + settings.marginVertical.toFloat()
		val pageSlots = buildList {
			add(currentPageIndex to 0f)
			if (isDualPage && currentPageIndex < pages.lastIndex) {
				add((currentPageIndex + 1) to width / 2f)
			}
		}
		for ((index, left) in pageSlots) {
			val page = pages.getOrNull(index) ?: continue
			val pageLeft = left + paddingLeft + settings.marginHorizontal
			val localX = x - pageLeft
			val localY = y - pageTop
			if (localX !in 0f..pageWidth || localY < 0f) {
				continue
			}
			val inlineImagePath = page.layout?.let { layout -> findInlineImagePathAt(layout, localX, localY) }
			if (inlineImagePath != null) {
				return NovelInlineImageRequest(
					imagePath = inlineImagePath,
					headers = imageHeadersProvider?.invoke(inlineImagePath).orEmpty(),
				)
			}
			val image = page.images.firstOrNull { span ->
				getNovelImageDisplayRect(
					imagePath = span.imagePath,
					reservedWidth = span.width,
					reservedHeight = span.height,
					yPosition = span.yPosition,
				).contains(localX, localY)
			} ?: continue
			return NovelInlineImageRequest(
				imagePath = image.imagePath,
				headers = imageHeadersProvider?.invoke(image.imagePath).orEmpty(),
			)
		}
		return null
	}

	fun resumePageChangeNotification() {
		suppressPageChangeNotification = false
		notifyPageChanged()
	}

	private fun getTapArea(x: Float, y: Float): TapGridArea {
		val col = when {
			x < width / 3f -> 0
			x < width * 2f / 3f -> 1
			else -> 2
		}
		val row = when {
			y < height / 3f -> 0
			y < height * 2f / 3f -> 1
			else -> 2
		}
		return when (row * 3 + col) {
			0 -> TapGridArea.TOP_LEFT
			1 -> TapGridArea.TOP_CENTER
			2 -> TapGridArea.TOP_RIGHT
			3 -> TapGridArea.CENTER_LEFT
			4 -> TapGridArea.CENTER
			5 -> TapGridArea.CENTER_RIGHT
			6 -> TapGridArea.BOTTOM_LEFT
			7 -> TapGridArea.BOTTOM_CENTER
			else -> TapGridArea.BOTTOM_RIGHT
		}
	}

	private data class PageInfo(
		val text: String,
		val layout: StaticLayout?,
		val startOffset: Int,
		val endOffset: Int,
		val images: List<ImageSpan> = emptyList(),
	)

	internal data class ImageSpan(
		val imagePath: String,
		val yPosition: Float,
		val width: Float,
		val height: Float,
	)

	fun clearImageCache() {
		imageCache.evictAll()
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		pageSwipeAnimator?.cancel()
		foldCurrentBitmap?.recycle()
		foldTargetBitmap?.recycle()
		foldCurrentHalfBitmap?.recycle()
		foldTargetHalfBitmap?.recycle()
		foldCurrentBitmap = null
		foldTargetBitmap = null
		foldCurrentHalfBitmap = null
		foldTargetHalfBitmap = null
		scope.cancel()
	}

	private fun loadImage(imagePath: String): Bitmap? {
		val cacheKey = "remote:$imagePath"
		imageCache.get(cacheKey)?.let { return it }

		if (loadingImages.contains(cacheKey) || failedImages.contains(cacheKey)) return null

		loadingImages.add(cacheKey)
		scope.launch {
			try {
				val bitmap = if (imagePath.startsWith("http", ignoreCase = true) ||
					imagePath.startsWith("file", ignoreCase = true) || imagePath.startsWith("zip:")
				) {
					loadCoilImage(imagePath)
				} else {
					null
				}

				if (bitmap != null) {
					imageCache.put(cacheKey, bitmap)
					loadingImages.remove(cacheKey)
					val metrics = NovelImageMetrics(bitmap.width, bitmap.height)
					val previousMetrics = NovelImageMetricsCache.get(imagePath)
					NovelImageMetricsCache.put(imagePath, metrics)
					if (previousMetrics != metrics) {
						repaginate()
					} else {
						invalidate()
					}
				} else {
					loadingImages.remove(cacheKey)
				}
			} catch (e: Exception) {
				loadingImages.remove(cacheKey)
				failedImages.add(cacheKey)
			}
		}

		return null
	}

	override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
		super.onScrollChanged(l, t, oldl, oldt)
		if (settings.readingMode == NovelReadingMode.SCROLL && height > 0) {
			val h = scrollViewportHeight()
			if (h > 0) {
				val newPage = (t / h).coerceIn(0, max(0, getDisplayPageCount() - 1))
				val oldPage = (oldt / h).coerceIn(0, max(0, getDisplayPageCount() - 1))
				if (newPage != oldPage) {
					notifyPageChanged()
				}
			}
		}
	}

	private suspend fun loadCoilImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
		val requestBuilder = ImageRequest.Builder(context)
			.data(url)
			.allowHardware(false)

		imageHeadersProvider?.invoke(url)?.takeIf { it.isNotEmpty() }?.let { extra: Map<String, String> ->
			val headers = NetworkHeaders.Builder().apply {
				extra.forEach { (k, v) -> add(k, v) }
			}.build()
			requestBuilder.httpHeaders(headers)
		}

		when (val result = imageLoader.execute(requestBuilder.build())) {
			is SuccessResult -> result.image.toBitmap(
				width = result.image.width,
				height = result.image.height,
			)

			is ErrorResult -> throw result.throwable
		}
	}

	private fun handlePagedTouch(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				pageSwipeAnimator?.cancel()
				pageSwipeDownX = event.x
				pageSwipeDownY = event.y
				pageSwipeStartX = event.x
				pageSwipeStartY = event.y
				pageSwipeLastX = event.x
				pageSwipeCurrentX = event.x
				pageSwipeCurrentY = event.y
				isPageDragging = false
				resetFoldBitmaps()
				resetPageSwipeState(keepOffset = false)
			}

			MotionEvent.ACTION_MOVE -> {
				val deltaX = event.x - pageSwipeDownX
				val deltaY = event.y - pageSwipeDownY
				if (!isPageDragging && abs(deltaX) > touchSlop && abs(deltaX) > abs(deltaY)) {
					val targetIndex = resolveSwipeTargetIndex(deltaX)
					val chapterDelta = if (targetIndex < 0) resolveSwipeChapterDelta(deltaX) else 0
					if (targetIndex >= 0 || chapterDelta != 0) {
						isPageDragging = true
						pageSwipeBaseIndex = currentPageIndex
						pageSwipeTargetIndex = if (targetIndex >= 0) targetIndex else currentPageIndex
						pageSwipeChapterDelta = chapterDelta
						pageSwipeDirection = deltaX.sign.toInt()
						pageSwipeLastX = event.x
						pageSwipeCurrentX = pageSwipeDownX
						pageSwipeCurrentY = event.y
						pageSwipeOffsetX = event.x - pageSwipeDownX
						lockFoldCorner(forward = pageSwipeDirection < 0)
						applyLegadoTouchYPolicy(pageSwipeDirection)
						parent?.requestDisallowInterceptTouchEvent(true)
						invalidate()
						return true
					} else {
						return false
					}
				}
				if (isPageDragging) {
					pageSwipeOffsetX = coerceSwipeOffsetForLockedDirection(deltaX)
					pageSwipeLastX = event.x
					pageSwipeCurrentX = getSimulationTouchX()
					pageSwipeCurrentY = event.y
					applyLegadoTouchYPolicy(pageSwipeDirection)
					invalidate()
					return true
				}
			}

			MotionEvent.ACTION_CANCEL,
			MotionEvent.ACTION_UP -> {
				if (isPageDragging) {
					val shouldCommit = abs(pageSwipeOffsetX) >= getPageTurnDistance() * pageTurnThresholdFraction
					animatePageSettle(
						targetIndex = pageSwipeTargetIndex,
						commit = shouldCommit,
						direction = pageSwipeDirection,
					)
					isPageDragging = false
					return true
				}
			}
		}
		return false
	}

	private fun applyLegadoTouchYPolicy(direction: Int) {
		if (pageSwipeStartY > height / 3f && pageSwipeStartY < height * 2f / 3f || direction > 0) {
			pageSwipeCurrentY = height.toFloat()
		}
		if (pageSwipeStartY > height / 3f && pageSwipeStartY < height / 2f && direction < 0) {
			pageSwipeCurrentY = 1f
		}
	}

	private fun animatePageSettle(targetIndex: Int, commit: Boolean, direction: Int) {
		if (direction == 0 || pageSwipeBaseIndex == -1) {
			pageSwipeBaseIndex = currentPageIndex
			pageSwipeTargetIndex = targetIndex
		}
		val resolvedDirection = if (direction == 0) {
			when {
				targetIndex > currentPageIndex -> -1
				targetIndex < currentPageIndex -> 1
				else -> 0
			}
		} else {
			direction
		}
		if (resolvedDirection == 0) return
		if (pageSwipeDirection != resolvedDirection) {
			pageSwipeDirection = resolvedDirection
		}
		if (settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION && !isPageDragging) {
			prepareSimulationAutoPageTurn(resolvedDirection)
		}

		pageSwipeAnimator?.cancel()
		val settleFromX = getSimulationTouchX()
		val settleFromY = pageSwipeCurrentY
		val settleFromOffset = pageSwipeOffsetX
		val settleToX = if (commit) {
			if (resolvedDirection < 0) -getPageTurnDistance() else getPageTurnDistance()
		} else {
			if (resolvedDirection < 0) getPageTurnDistance() else -getPageTurnDistance()
		}
		val settleToY = if (!commit) {
			pageSwipeCurrentY
		} else if (pageSwipeFoldCornerLocked && pageSwipeFoldCornerY == 0f) {
			1f
		} else {
			height.toFloat()
		}
		val settleToOffset = if (commit) {
			if (resolvedDirection < 0) -getPageTurnDistance() else getPageTurnDistance()
		} else {
			0f
		}
		pageSwipeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
			var cancelled = false
			duration = pageTurnSettleDuration(
				fromOffset = settleFromOffset,
				toOffset = settleToOffset,
				commit = commit,
			)
			interpolator = if (settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION) {
				simulationPageTurnInterpolator
			} else {
				pageTurnInterpolator
			}
			addUpdateListener { animator ->
				val fraction = animator.animatedFraction
				pageSwipeCurrentX = settleFromX + (settleToX - settleFromX) * fraction
				pageSwipeCurrentY = settleFromY + (settleToY - settleFromY) * fraction
				pageSwipeOffsetX = settleFromOffset + (settleToOffset - settleFromOffset) * fraction
				invalidate()
			}
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationCancel(animation: Animator) {
					cancelled = true
				}

				override fun onAnimationEnd(animation: Animator) {
					if (!cancelled && commit) {
						if (pageSwipeChapterDelta != 0) {
							isAwaitingChapterTransitionContent = true
							onChapterChangeRequestListener?.invoke(pageSwipeChapterDelta)
							invalidate()
							return
						} else {
							currentPageIndex = targetIndex
							notifyPageChanged()
						}
					}
					resetPageSwipeState()
					invalidate()
				}
			})
			start()
		}
	}

	private fun getSimulationTouchX(): Float {
		val anchorX = if (isSimulationAutoPageTurn) pageSwipeStartX else pageSwipeDownX
		return (anchorX + pageSwipeOffsetX).coerceIn(0.1f, width.toFloat() - 0.1f)
	}

	private fun prepareSimulationAutoPageTurn(direction: Int) {
		if (width <= 0 || height <= 0) return
		pageSwipeStartX = if (direction < 0) width * 0.92f else width * 0.08f
		pageSwipeStartY = height * 0.9f
		pageSwipeLastX = pageSwipeStartX
		pageSwipeCurrentX = pageSwipeStartX
		pageSwipeCurrentY = pageSwipeStartY
		pageSwipeOffsetX = 0f
		pageSwipeDownX = pageSwipeStartX
		pageSwipeDownY = pageSwipeStartY
		isSimulationAutoPageTurn = true
		lockFoldCorner(forward = direction < 0)
		resetFoldBitmaps()
	}

	private fun coerceSwipeOffsetForLockedDirection(deltaX: Float): Float {
		val distance = getPageTurnDistance()
		return if (pageSwipeDirection < 0) {
			deltaX.coerceIn(-distance, 0f)
		} else {
			deltaX.coerceIn(0f, distance)
		}
	}

	private fun pageTurnSettleDuration(fromOffset: Float, toOffset: Float, commit: Boolean): Long {
		if (settings.pageTurnAnimation != NovelPageTurnAnimation.SIMULATION) {
			return 180L
		}
		val distanceRatio =
			(abs(toOffset - fromOffset) / getPageTurnDistance().coerceAtLeast(1f)).coerceIn(0f, 1f)
		val baseDuration = if (isSimulationAutoPageTurn && commit) {
			450L
		} else if (commit) {
			420L
		} else {
			260L
		}
		return (baseDuration * distanceRatio).toLong().coerceIn(120L, baseDuration)
	}

	private fun getPageTurnDistance(): Float = width.toFloat().coerceAtLeast(1f)

	private fun resolveSwipeTargetIndex(deltaX: Float): Int {
		val step = getPageTurnStep()
		return when {
			deltaX < 0f && currentPageIndex + step < pages.size -> currentPageIndex + step
			deltaX > 0f && currentPageIndex - step >= 0 -> currentPageIndex - step
			else -> -1
		}
	}

	private fun resolveSwipeChapterDelta(deltaX: Float): Int {
		val step = getPageTurnStep()
		return when {
			deltaX < 0f && currentPageIndex + step >= pages.size -> 1
			deltaX > 0f && currentPageIndex - step < 0 -> -1
			else -> 0
		}
	}

	private fun getPageTurnStep(): Int = if (isDualPage) 2 else 1

	private fun resetPageSwipeState(keepOffset: Boolean = false) {
		isPageDragging = false
		isAwaitingChapterTransitionContent = false
		pageSwipeBaseIndex = -1
		pageSwipeTargetIndex = -1
		pageSwipeChapterDelta = 0
		pageSwipeDirection = 0
		pageSwipeFoldCornerX = 0f
		pageSwipeFoldCornerY = 0f
		pageSwipeFoldCornerLocked = false
		isSimulationAutoPageTurn = false
		resetFoldBitmaps()
		if (!keepOffset) {
			pageSwipeOffsetX = 0f
		}
	}

	private fun resetFoldBitmaps() {
		foldBitmapBaseIndex = -1
		foldBitmapTargetIndex = -1
		foldBitmapChapterDelta = 0
		foldCurrentHalfBitmap?.eraseColor(Color.TRANSPARENT)
		foldTargetHalfBitmap?.eraseColor(Color.TRANSPARENT)
	}

	fun cancelPendingChapterTransition() {
		if (isAwaitingChapterTransitionContent || pageSwipeChapterDelta != 0 || pageSwipeBaseIndex >= 0) {
			resetPageSwipeState()
			invalidate()
		}
	}
}
