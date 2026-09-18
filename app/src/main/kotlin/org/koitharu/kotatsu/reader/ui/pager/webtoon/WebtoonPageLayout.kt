package org.koitharu.kotatsu.reader.ui.pager.webtoon

import java.util.LinkedHashMap

private const val SAVED_SCROLL_SCALE = 10_000
private const val DEFAULT_PAGE_SIZE_CACHE_LIMIT = 256

internal data class WebtoonPageKey(
	val chapterId: Long,
	val pageId: Long,
)

internal data class WebtoonImageSize(
	val width: Int,
	val height: Int,
)

internal fun isRestoreTarget(
	expected: WebtoonPageKey,
	actualChapterId: Long?,
	actualPageId: Long?,
): Boolean = expected.chapterId == actualChapterId && expected.pageId == actualPageId

internal fun calculateUnloadedPageScrollPercent(itemTop: Int, itemHeight: Int): Int {
	if (itemHeight <= 0) return 0
	val height = itemHeight.toLong()
	val offset = (-itemTop.toLong()).coerceIn(0L, height)
	return ((offset * SAVED_SCROLL_SCALE + height / 2L) / height).toInt()
}

internal fun calculateScaledPageHeight(
	sourceWidth: Int,
	sourceHeight: Int,
	targetWidth: Int,
	maximumHeight: Int,
): Int {
	if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || maximumHeight <= 0) {
		return maximumHeight.coerceAtLeast(0)
	}
	return (sourceHeight.toLong() * targetWidth / sourceWidth)
		.coerceIn(1L, maximumHeight.toLong())
		.toInt()
}

internal fun calculateScaledPageScrollRange(
	sourceWidth: Int,
	sourceHeight: Int,
	targetWidth: Int,
	viewHeight: Int,
): Int {
	if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || viewHeight <= 0) return 0
	// Use exactly the same pixel rounding as onMeasure. Rounding up here leaves a phantom
	// scroll pixel on short images, preventing the existing absolute-bottom completion path.
	val totalHeight = calculateScaledPageHeight(sourceWidth, sourceHeight, targetWidth, Int.MAX_VALUE)
	return (totalHeight - viewHeight).coerceAtLeast(0)
}

internal fun calculateUnresolvedPageHeight(
	viewportHeight: Int,
	compactHeight: Int,
	useCompactHeight: Boolean,
): Int = when {
	!useCompactHeight -> viewportHeight.coerceAtLeast(0)
	compactHeight <= 0 -> viewportHeight.coerceAtLeast(0)
	viewportHeight <= 0 -> compactHeight
	else -> compactHeight.coerceAtMost(viewportHeight)
}

internal class WebtoonPageSizeCache(
	private val maximumSize: Int = DEFAULT_PAGE_SIZE_CACHE_LIMIT,
) {

	private val sizes = object : LinkedHashMap<WebtoonPageKey, WebtoonImageSize>(maximumSize, 0.75f, true) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<WebtoonPageKey, WebtoonImageSize>?,
		): Boolean = size > maximumSize
	}

	operator fun get(key: WebtoonPageKey): WebtoonImageSize? = sizes[key]

	fun put(key: WebtoonPageKey, width: Int, height: Int) {
		if (width > 0 && height > 0) {
			sizes[key] = WebtoonImageSize(width, height)
		}
	}
}
