package org.koitharu.kotatsu.reader.ui.novel

internal fun novelScrollPageCount(contentHeight: Int, viewportHeight: Int): Int =
    ((contentHeight.coerceAtLeast(1).toLong() + viewportHeight.coerceAtLeast(1) - 1) /
        viewportHeight.coerceAtLeast(1)).toInt().coerceAtLeast(1)

internal fun novelScrollPageIndex(offset: Int, contentHeight: Int, viewportHeight: Int): Int =
    (offset.coerceAtLeast(0) / viewportHeight.coerceAtLeast(1))
        .coerceAtMost(novelScrollPageCount(contentHeight, viewportHeight) - 1)
