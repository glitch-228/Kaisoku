package org.koitharu.kotatsu.reader.ui.pager.doublereversed

import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.doublepage.DoubleReaderFragment

class ReversedDoubleReaderFragment : DoubleReaderFragment() {

	override fun switchPageBy(delta: Int) {
		super.switchPageBy(-delta)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		super.switchPageTo(reversed(position), smooth)
	}

	override fun adapterPages(pages: List<ReaderPage>): List<ReaderPage> =
		super.adapterPages(pages.reversed())

	override fun notifyPageChanged(lowerPos: Int, upperPos: Int) {
		val reversedLower = positionMap.getOrElse(upperPos) { -1 }
		val reversedUpper = positionMap.getOrElse(lowerPos) { -1 }
		val totalPages = originalPageCount
		val originalLower = if (reversedLower >= 0) (totalPages - 1 - reversedLower) else -1
		val originalUpper = if (reversedUpper >= 0) (totalPages - 1 - reversedUpper) else -1
		val lower = if (originalLower >= 0) originalLower else originalUpper
		val upper = if (originalUpper >= 0) originalUpper else originalLower
		if (lower >= 0) {
			viewModel.onCurrentPageChanged(
				lower,
				upper.coerceAtLeast(lower),
				contentGeneration = adapterContentGeneration,
			)
		}
	}

	private fun reversed(position: Int): Int {
		return (originalPageCount - position - 1).coerceAtLeast(0)
	}
}
