/*
 * Ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.resolveSp
import org.koitharu.kotatsu.reader.domain.TapGridArea
import javax.inject.Inject
import kotlin.math.max

/**
 * Static text item view for the continuous scroll reader list.
 */
@AndroidEntryPoint
class NovelChapterView @JvmOverloads constructor(
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
		textSize = resources.resolveSp(settings.fontSizeSp)
		isSubpixelText = true
		letterSpacing = 0.01f
	}

	var chapterContent: String = ""
		private set
	private var displayLayout: StaticLayout? = null

	private val highlightPaint by lazy {
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = palette.highlightColor
			style = Paint.Style.FILL
		}
	}
	private var highlightRange: IntRange? = null
	private var imageSpans: List<ChapterImageSpan> = emptyList()

	private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	var imageHeadersProvider: ((String) -> Map<String, String>?)? = null
	var onImageClickListener: ((NovelInlineImageRequest) -> Unit)? = null
	var onTapListener: ((rawX: Float, rawY: Float, eventTime: Long) -> Unit)? = null
	var onTapAreaListener: ((area: TapGridArea) -> Unit)? = null
	var onBeforeGeometryChange: (() -> Unit)? = null

	@Inject
	lateinit var imageLoader: ImageLoader

	private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
	private val imageCache = LruCache<String, Bitmap>(20)
	private val loadingImages = mutableSetOf<String>()
	private val failedImages = mutableSetOf<String>()
	private val gestureDetector: GestureDetectorCompat

	init {
		isClickable = true
		isFocusable = true
		gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean = true

			override fun onSingleTapUp(e: MotionEvent): Boolean {
				findInlineImageAt(e.x, e.y)?.let { image ->
					onImageClickListener?.invoke(image)
					return true
				}
				onTapAreaListener?.invoke(getTapArea(e.x, e.y))
					?: onTapListener?.invoke(e.rawX, e.rawY, e.eventTime)
				return true
			}
		})
	}

	private fun isNightMode(): Boolean = resources.configuration.uiMode and
		android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
		android.content.res.Configuration.UI_MODE_NIGHT_YES

	fun setContent(content: String) {
		if (chapterContent == content) return
		scope.coroutineContext.cancelChildren()
		chapterContent = content
		displayLayout = null
		loadingImages.clear()
		failedImages.clear()
		requestLayout()
		invalidate()
	}

	fun updateSettings(newSettings: NovelReaderSettings) {
		if (settings == newSettings) return
		settings = newSettings
		textPaint.textSize = resources.resolveSp(settings.fontSizeSp)
		updatePalette()
		displayLayout = null
		requestLayout()
		invalidate()
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

	fun setHighlightRange(range: IntRange?) {
		if (highlightRange != range) {
			highlightRange = range
			invalidate()
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val handled = gestureDetector.onTouchEvent(event)
		return handled || super.onTouchEvent(event)
	}

	fun getOffsetForVertical(y: Float): Int {
		val layout = displayLayout ?: return 0
		return try {
			val adjustedY = y - paddingTop - settings.marginVertical
			val clampedY = adjustedY.coerceIn(0f, layout.height.toFloat())
			val line = layout.getLineForVertical(clampedY.toInt())
			layout.getOffsetForHorizontal(line, 0f)
		} catch (e: Exception) {
			0
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val widthMode = MeasureSpec.getMode(widthMeasureSpec)
		val widthSize = MeasureSpec.getSize(widthMeasureSpec)

		if (widthSize <= 0) {
			super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			return
		}

		val availableWidth = widthSize - paddingLeft - paddingRight - (settings.marginHorizontal * 2)

		if (displayLayout?.width != availableWidth && chapterContent.isNotEmpty() && availableWidth > 0) {
			buildLayout(availableWidth)
		}

		val contentHeight = displayLayout?.height ?: 0
		val desiredHeight = paddingTop + paddingBottom + contentHeight + settings.marginVertical * 2

		val heightMode = MeasureSpec.getMode(heightMeasureSpec)
		val heightSize = MeasureSpec.getSize(heightMeasureSpec)

		val finalHeight = when (heightMode) {
			MeasureSpec.EXACTLY -> heightSize
			MeasureSpec.AT_MOST -> kotlin.math.min(desiredHeight, heightSize)
			else -> desiredHeight
		}

		setMeasuredDimension(widthSize, finalHeight)
	}

	private fun buildLayout(pageWidth: Int) {
		val parsedImages = parseNovelImages(chapterContent)
		var processedText = NovelTypography.prepareContentText(parsedImages.text, settings, textPaint)
		val blockImagePaths = parsedImages.blockImagePaths
		val inlineImagePaths = parsedImages.inlineImagePaths
		val hasImages = blockImagePaths.isNotEmpty()
		val tempImageSpans = mutableListOf<ChapterImageSpan>()

		if (hasImages) {
			val lineHeight = (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent) * settings.lineSpacing
			val maxScreenHeight = resources.displayMetrics.heightPixels * 1.5f
			val paraSpacingPx = settings.paragraphSpacing * resources.displayMetrics.density
			val extraSpacerLines = if (paraSpacingPx > 0) {
				max(1, kotlin.math.ceil(paraSpacingPx / lineHeight).toInt())
			} else {
				0
			}

			var newText = processedText
			for (i in blockImagePaths.indices) {
				val placeholder = "[IMAGE_PLACEHOLDER_$i]"
				val imagePath = blockImagePaths[i]
				val imageHeight = getReservedNovelImageHeight(
					imagePath = imagePath,
					maxWidth = pageWidth.toFloat(),
					maxHeight = maxScreenHeight,
				)

				val spacerLines = (imageHeight / lineHeight).toInt() + (extraSpacerLines * 2).coerceAtLeast(2)
				val spacer = "\n".repeat(spacerLines)
				newText = newText.replace(placeholder, "\n$placeholder\n$spacer\n")
			}
			processedText = newText
		}

		if (hasImages) {
			val placeholderPattern = Regex("""\[IMAGE_PLACEHOLDER_(\d+)\]""")
			val displayBuilder = StringBuilder()
			var lastIndex = 0

			placeholderPattern.findAll(processedText).forEach { match ->
				val imageIndex = match.groupValues[1].toInt()
				if (imageIndex < blockImagePaths.size) {
					val imagePath = blockImagePaths[imageIndex]
					val before = processedText.substring(lastIndex, match.range.first)
					displayBuilder.append(before)
					lastIndex = match.range.last + 1

					val tempLayout = createStaticLayout(
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
					val maxScreenHeight = resources.displayMetrics.heightPixels * 1.5f
					val imageHeight = getReservedNovelImageHeight(
						imagePath = imagePath,
						maxWidth = imageWidth,
						maxHeight = maxScreenHeight,
					)

					tempImageSpans.add(ChapterImageSpan(imagePath, yPosition, imageWidth, imageHeight))
				}
			}
			if (lastIndex < processedText.length) {
				displayBuilder.append(processedText.substring(lastIndex))
			}
			processedText = displayBuilder.toString()
		}

		imageSpans = tempImageSpans
		val layoutText = applyInlineImageSpans(
			NovelTypography.styleChapterTitles(processedText, palette.secondaryTextColor),
			inlineImagePaths,
		) { path -> loadImage(path) }
		displayLayout = createStaticLayout(layoutText, pageWidth)
	}

	private fun createStaticLayout(text: CharSequence, width: Int): StaticLayout = try {
		StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
			.setLineSpacing(0f, settings.lineSpacing)
			.setIncludePad(false)
			.build()
	} catch (e: Exception) {
		@Suppress("DEPRECATION")
		StaticLayout(text, textPaint, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val layout = displayLayout ?: return
		canvas.drawColor(palette.backgroundColor)

		canvas.save()
		val x = paddingLeft + settings.marginHorizontal.toFloat()
		canvas.translate(x, (paddingTop + settings.marginVertical).toFloat())

		highlightRange?.let { range ->
			val intersectStart = max(0, range.first)
			val intersectEnd = kotlin.math.min(processedTextLength(), range.last + 1)
			if (intersectStart < intersectEnd) {
				val path = android.graphics.Path()
				layout.getSelectionPath(intersectStart, intersectEnd, path)
				canvas.drawPath(path, highlightPaint)
			}
		}

		layout.draw(canvas)

		for (imageSpan in imageSpans) {
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
				val placeholderPaint = Paint().apply {
					color = palette.placeholderColor
					style = Paint.Style.FILL
				}
				val placeholderRect = RectF(0f, imageSpan.yPosition, imageSpan.width, imageSpan.yPosition + imageSpan.height)
				canvas.drawRect(placeholderRect, placeholderPaint)
			}
		}
		canvas.restore()
		imagePaint.color = palette.secondaryTextColor
		imagePaint.strokeWidth = resources.displayMetrics.density
		val separatorY = (height - paddingBottom - settings.marginVertical / 2f)
		canvas.drawLine(
			(paddingLeft + settings.marginHorizontal).toFloat(), separatorY,
			(width - paddingRight - settings.marginHorizontal).toFloat(), separatorY, imagePaint,
		)
	}

	/** Character-based anchor shared with the paged reader's history ratio. */
	fun progressAt(y: Int): Float {
		val layout = displayLayout ?: return 0f
		if (layout.text.isEmpty()) return 0f
		val line = layout.getLineForVertical((y - paddingTop - settings.marginVertical).coerceAtLeast(0))
		return layout.getLineStart(line).toFloat() / layout.text.length
	}

	fun offsetForProgress(ratio: Float): Int {
		val layout = displayLayout ?: return 0
		val offset = (layout.text.length * ratio.coerceIn(0f, 1f)).toInt()
		return if (ratio <= 0f) 0 else paddingTop + settings.marginVertical + layout.getLineTop(layout.getLineForOffset(offset))
	}

	fun pageCount(viewportHeight: Int): Int = novelScrollPageCount(height, viewportHeight)

	fun pageAtProgress(ratio: Float, viewportHeight: Int): Int =
		if (ratio >= 1f) pageCount(viewportHeight) - 1
		else novelScrollPageIndex(offsetForProgress(ratio), height, viewportHeight)

	private fun processedTextLength(): Int = displayLayout?.text?.length ?: 0

	private fun findInlineImageAt(x: Float, y: Float): NovelInlineImageRequest? {
		val localX = x - paddingLeft - settings.marginHorizontal
		val localY = y - paddingTop - settings.marginVertical
		if (localX < 0f || localY < 0f) {
			return null
		}
		val inlineImagePath = displayLayout?.let { layout ->
			findInlineImagePathAt(layout, localX, localY)
		}
		if (inlineImagePath != null) {
			return NovelInlineImageRequest(
				imagePath = inlineImagePath,
				headers = imageHeadersProvider?.invoke(inlineImagePath).orEmpty(),
			)
		}
		val image = imageSpans.firstOrNull { span ->
			getNovelImageDisplayRect(
				imagePath = span.imagePath,
				reservedWidth = span.width,
				reservedHeight = span.height,
				yPosition = span.yPosition,
			).contains(localX, localY)
		} ?: return null
		return NovelInlineImageRequest(
			imagePath = image.imagePath,
			headers = imageHeadersProvider?.invoke(image.imagePath).orEmpty(),
		)
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

	private fun loadImage(imagePath: String): Bitmap? {
		if (!::imageLoader.isInitialized) return null
		val cacheKey = imagePath
		imageCache.get(cacheKey)?.let { return it }

		if (!loadingImages.contains(cacheKey) && !failedImages.contains(cacheKey)) {
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
							onBeforeGeometryChange?.invoke()
							displayLayout = null
							requestLayout()
						} else {
							invalidate()
						}
					} else {
						loadingImages.remove(cacheKey)
						failedImages.add(cacheKey)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					loadingImages.remove(cacheKey)
					failedImages.add(cacheKey)
				}
			}
		}
		return null
	}

	private suspend fun loadCoilImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
		val requestBuilder = ImageRequest.Builder(context)
			.data(url)
			.allowHardware(false)
		imageHeadersProvider?.invoke(url)?.takeIf { it.isNotEmpty() }?.let { extra ->
			val headers = NetworkHeaders.Builder().apply {
				extra.forEach { (k, v) -> add(k, v) }
			}.build()
			requestBuilder.httpHeaders(headers)
		}
		when (val result = imageLoader.execute(requestBuilder.build())) {
			is SuccessResult -> result.image.toBitmap(width = result.image.width, height = result.image.height)
			is ErrorResult -> throw result.throwable
		}
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		scope.coroutineContext.cancelChildren()
		loadingImages.clear()
	}
}

data class ChapterImageSpan(
	val imagePath: String,
	val yPosition: Float,
	val width: Float,
	val height: Float,
)
