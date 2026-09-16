package org.koitharu.kotatsu.reader.ui.pager.reversed

import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.BasePagerReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject

@AndroidEntryPoint
class ReversedReaderFragment : BasePagerReaderFragment() {

	@Inject
	lateinit var settings: AppSettings

	override fun onCreateAdvancedTransformer(): ViewPager2.PageTransformer = ReversedPageAnimTransformer()

	override fun onCreateAdapter() = ReversedPagesAdapter(
		lifecycleOwner = viewLifecycleOwner,
		loader = pageLoader,
		readerSettingsProducer = viewModel.readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
	)

	override fun onWheelScroll(axisValue: Float) {
		val value = if (settings.isReaderControlAlwaysLTR) -axisValue else axisValue
		super.onWheelScroll(value)
	}

	override fun switchPageBy(delta: Int) {
		super.switchPageBy(-delta)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		super.switchPageTo(reversed(position), smooth)
	}

	override fun adapterPages(pages: List<ReaderPage>): List<ReaderPage> =
		super.adapterPages(pages.reversed())

	override fun notifyPageChanged(page: Int) {
		val pos = reversed(page)
		viewModel.onCurrentPageChanged(pos, pos, contentGeneration = adapterContentGeneration)
	}

	private fun reversed(position: Int): Int {
		return ((readerAdapter?.itemCount ?: 0) - position - 1).coerceAtLeast(0)
	}
}
