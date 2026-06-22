package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.list.lifecycle.RecyclerViewLifecycleDispatcher
import org.koitharu.kotatsu.core.util.ext.firstVisibleItemPosition
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.removeItemDecoration
import org.koitharu.kotatsu.databinding.FragmentReaderWebtoonBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class WebtoonReaderFragment : BaseReaderFragment<FragmentReaderWebtoonBinding>(),
	WebtoonRecyclerView.OnWebtoonScrollListener,
	WebtoonRecyclerView.OnPullGestureListener {

	@Inject
	lateinit var networkState: NetworkState

	@Inject
	lateinit var pageLoader: PageLoader

	private val scrollInterpolator = DecelerateInterpolator()

	private var recyclerLifecycleDispatcher: RecyclerViewLifecycleDispatcher? = null
	private var canGoPrev = true
	private var canGoNext = true
	private var lastFirstPos = RecyclerView.NO_POSITION
	private var lastLastPos = RecyclerView.NO_POSITION

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentReaderWebtoonBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentReaderWebtoonBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		with(binding.recyclerView) {
			setHasFixedSize(true)
			adapter = readerAdapter
			addOnPageScrollListener(this@WebtoonReaderFragment)
			recyclerLifecycleDispatcher = RecyclerViewLifecycleDispatcher().also {
				addOnScrollListener(it)
			}
			setOnPullGestureListener(this@WebtoonReaderFragment)
		}
		viewModel.isWebtoonZooEnabled.observe(viewLifecycleOwner) {
			binding.frame.isZoomEnable = it
		}
		viewModel.defaultWebtoonZoomOut.take(1).observe(viewLifecycleOwner) {
			binding.frame.zoom = 1f - it
		}
		viewModel.isWebtoonGapsEnabled.observe(viewLifecycleOwner) {
			val rv = binding.recyclerView
			rv.removeItemDecoration(WebtoonGapsDecoration::class.java)
			if (it) {
				rv.addItemDecoration(WebtoonGapsDecoration())
			}
		}
		viewModel.readerSettingsProducer.observe(viewLifecycleOwner) {
			it.applyBackground(binding.root)
		}
		viewModel.isWebtoonPullGestureEnabled.observe(viewLifecycleOwner) { enabled ->
			binding.recyclerView.isPullGestureEnabled = enabled
		}
		viewModel.uiState.observe(viewLifecycleOwner) { state ->
			if (state != null) {
				canGoPrev = state.chapterIndex > 0
				canGoNext = state.chapterIndex < state.chaptersTotal - 1
			} else {
				canGoPrev = true
				canGoNext = true
			}
		}
	}

	override fun onDestroyView() {
		recyclerLifecycleDispatcher = null
		requireViewBinding().recyclerView.adapter = null
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val offsetInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding?.apply {
			feedbackTop.updateLayoutParams<MarginLayoutParams> {
				topMargin = bottomMargin + offsetInsets.top
			}
			feedbackBottom.updateLayoutParams<MarginLayoutParams> {
				bottomMargin = topMargin + offsetInsets.bottom
			}
		}
		return super.onApplyWindowInsets(v, insets)
	}

	override fun onCreateAdapter() = WebtoonAdapter(
		lifecycleOwner = viewLifecycleOwner,
		loader = pageLoader,
		readerSettingsProducer = viewModel.readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
	)

	override fun onScrollChanged(
		recyclerView: WebtoonRecyclerView,
		dy: Int,
		firstVisiblePosition: Int,
		lastVisiblePosition: Int,
	) {
		// Update progress from first visible holder (for continuous display)
		val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
		val firstPos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
		val adapter = recyclerView.adapter as? BaseReaderAdapter<*>
		val firstHolder = if (firstPos != RecyclerView.NO_POSITION) {
			recyclerView.findViewHolderForAdapterPosition(firstPos) as? WebtoonHolder
		} else null
		val firstPage = adapter?.getItemOrNull(firstPos)
		val progress = firstHolder?.getScrollProgress()
			?: firstPage?.let { getSavedScrollPercent(it.chapterId, it.index) }
			?: -1f
		viewModel.updateScrollProgress(progress)
		viewModel.updateScrollOffset(
			if (progress >= 0f) {
				(progress * 10000).toInt()
			} else {
				firstPage?.let { getSavedScrollOffset(it.chapterId, it.index) } ?: 0
			},
		)
		// Only notify page change when visible positions actually change
		if (firstVisiblePosition != lastFirstPos || lastVisiblePosition != lastLastPos) {
			lastFirstPos = firstVisiblePosition
			lastLastPos = lastVisiblePosition
			// Compute scroll from centerPos holder (matches what ViewModel uses for chapterId/page)
			val centerPos = (firstVisiblePosition + lastVisiblePosition) / 2
			val centerHolder = recyclerView.findViewHolderForAdapterPosition(centerPos) as? WebtoonHolder
			val centerPage = adapter?.getItemOrNull(centerPos)
			val centerProgress = centerHolder?.getScrollProgress()
				?: centerPage?.let { getSavedScrollPercent(it.chapterId, it.index) }
				?: 0f
			val scrollPercent = if (centerProgress >= 0f) {
				(centerProgress * 10000).toInt()
			} else {
				centerPage?.let { getSavedScrollOffset(it.chapterId, it.index) } ?: 0
			}
			viewModel.onCurrentPageChanged(firstVisiblePosition, lastVisiblePosition, progress, scrollPercent)
		}
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) = coroutineScope {
		val setItems = launch {
			requireAdapter().setItems(pages)
			yield()
			viewBinding?.recyclerView?.let { rv ->
				recyclerLifecycleDispatcher?.invalidate(rv)
			}
		}
		if (pendingState != null) {
			val position = pages.indexOfFirst {
				it.chapterId == pendingState.chapterId && it.index == pendingState.page
			}
			setItems.join()
			if (position != -1) {
				with(requireViewBinding().recyclerView) {
					firstVisibleItemPosition = position
					postRestoreScroll(this, position, pendingState.scroll)
				}
				viewModel.onCurrentPageChanged(
					position,
					position,
					scrollProgress = pendingState.scroll / 10000f,
					scrollOffset = pendingState.scroll,
				)
			} else {
				Snackbar.make(requireView(), R.string.not_found_404, Snackbar.LENGTH_SHORT)
					.show()
			}
		} else {
			setItems.join()
		}
	}

	override fun getCurrentState(): ReaderState? = viewBinding?.run {
		val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
			?: return@run null
		val currentItem = lm.findFirstVisibleItemPosition()
		if (currentItem == RecyclerView.NO_POSITION) return@run null
		val adapter = recyclerView.adapter as? BaseReaderAdapter<*>
		val page = adapter?.getItemOrNull(currentItem) ?: return@run null
		val holder = recyclerView.findViewHolderForAdapterPosition(currentItem) as? WebtoonHolder
		val fallbackScroll = getSavedScrollOffset(page.chapterId, page.index)
		val progress = holder?.getScrollProgress() ?: getSavedScrollPercent(page.chapterId, page.index)
		val scroll = if (progress >= 0f) {
			(progress * 10000).toInt()
		} else {
			fallbackScroll
		}
		ReaderState(
			chapterId = page.chapterId,
			page = page.index,
			scroll = scroll,
		)
	}

	private fun getSavedScrollOffset(chapterId: Long, pageIndex: Int): Int {
		return viewModel.getCurrentState()
			?.takeIf { it.chapterId == chapterId && it.page == pageIndex }
			?.scroll
			?: 0
	}

	private fun getSavedScrollPercent(chapterId: Long, pageIndex: Int): Float {
		val scroll = getSavedScrollOffset(chapterId, pageIndex)
		return if (scroll > 0) scroll / 10000f else -1f
	}

	private fun postRestoreScroll(rv: RecyclerView, position: Int, scrollPercent: Int) {
		rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
			override fun onChildViewAttachedToWindow(view: android.view.View) {
				val holder = rv.getChildViewHolder(view) as? WebtoonHolder
				if (holder != null && holder.bindingAdapterPosition == position) {
					rv.removeOnChildAttachStateChangeListener(this)
					holder.restoreScrollPercent(scrollPercent)
				}
			}
			override fun onChildViewDetachedFromWindow(view: android.view.View) {}
		})
	}

	override fun onZoomIn() {
		viewBinding?.frame?.onZoomIn()
	}

	override fun onZoomOut() {
		viewBinding?.frame?.onZoomOut()
	}

	override fun switchPageBy(delta: Int) {
		with(requireViewBinding().recyclerView) {
			if (isAnimationEnabled()) {
				smoothScrollByAdaptive((height * 0.9).toInt() * delta)
			} else {
				nestedScrollBy(0, (height * 0.9).toInt() * delta)
			}
		}
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		requireViewBinding().recyclerView.firstVisibleItemPosition = position
	}

	override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
		if (smooth && isAnimationEnabled()) {
			requireViewBinding().recyclerView.smoothScrollByAdaptive(delta)
		} else {
			requireViewBinding().recyclerView.nestedScrollBy(0, delta)
		}
		return true
	}

	override fun onPullProgressTop(progress: Float) {
		val binding = viewBinding ?: return
		if (canGoPrev) {
			binding.feedbackTop.setFeedbackText(getString(R.string.pull_to_prev_chapter))
		} else {
			binding.feedbackTop.setFeedbackText(getString(R.string.pull_top_no_prev))
		}
		binding.feedbackTop.updateFeedback(progress)
	}

	override fun onPullProgressBottom(progress: Float) {
		val binding = viewBinding ?: return
		if (canGoNext) {
			binding.feedbackBottom.setFeedbackText(getString(R.string.pull_to_next_chapter))
		} else {
			binding.feedbackBottom.setFeedbackText(getString(R.string.pull_bottom_no_next))
		}
		binding.feedbackBottom.updateFeedback(progress)
	}

	override fun onPullTriggeredTop() {
		(viewBinding ?: return).feedbackTop.hideFeedback()
		if (canGoPrev) {
			viewModel.switchChapterBy(-1)
		}
	}

	override fun onPullTriggeredBottom() {
		(viewBinding ?: return).feedbackBottom.hideFeedback()
		if (canGoNext) {
			viewModel.switchChapterBy(1)
		}
	}

	override fun onPullCancelled() {
		viewBinding?.apply {
			feedbackTop.hideFeedback()
			feedbackBottom.hideFeedback()
		}
	}

	private fun RecyclerView.findCurrentPagePosition(): Int {
		val centerX = width / 2f
		val centerY = height - resources.getDimension(R.dimen.webtoon_pages_gap)
		if (centerY <= 0) {
			return RecyclerView.NO_POSITION
		}
		val view = findChildViewUnder(centerX, centerY) ?: return RecyclerView.NO_POSITION
		return getChildAdapterPosition(view)
	}

	private fun RecyclerView.smoothScrollByAdaptive(deltaY: Int) {
		if (deltaY == 0) {
			return
		}
		val viewportHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
		val distance = abs(deltaY)
		val refreshRate = display?.refreshRate?.takeIf { it in MIN_REFRESH_RATE..MAX_REFRESH_RATE } ?: DEFAULT_REFRESH_RATE
		val frameTimeMs = 1000f / refreshRate
		val targetSpeedPxPerSec = viewportHeight * VIEWPORTS_PER_SECOND
		val durationByDistance = (distance / targetSpeedPxPerSec) * 1000f
		val minDuration = frameTimeMs * MIN_SCROLL_FRAMES
		val maxDuration = frameTimeMs * MAX_SCROLL_FRAMES
		val durationMs = durationByDistance
			.coerceIn(minDuration, maxDuration)
			.roundToInt()
		smoothScrollBy(0, deltaY, scrollInterpolator, durationMs)
	}

	private fun TextView.updateFeedback(progress: Float) {
		val clamped = progress.coerceIn(0f, 1.2f)
		val isReady = clamped >= 1f
		val wasReady = getTag(R.id.tag_pull_feedback_ready) as? Boolean ?: false
		if (wasReady != isReady) {
			setTag(R.id.tag_pull_feedback_ready, isReady)
			setBackgroundResource(
				if (isReady) {
					R.drawable.bg_reader_indicator_ready
				} else {
					R.drawable.bg_reader_indicator
				},
			)
		}
		animate().cancel()
		alpha = if (clamped > 0f) {
			(0.15f + 0.85f * clamped.coerceAtMost(1f)).coerceAtMost(1f)
		} else {
			0f
		}
		val scale = 0.9f + 0.1f * clamped.coerceAtMost(1f)
		scaleX = scale
		scaleY = scale
	}

	private fun TextView.hideFeedback() {
		animate().cancel()
		alpha = 0f
	}

	private fun TextView.setFeedbackText(text: CharSequence) {
		if (this.text != text) {
			this.text = text
		}
	}

	companion object {

		private const val VIEWPORTS_PER_SECOND = 3.2f
		private const val MIN_SCROLL_FRAMES = 10f
		private const val MAX_SCROLL_FRAMES = 72f
		private const val DEFAULT_REFRESH_RATE = 60f
		private const val MIN_REFRESH_RATE = 30f
		private const val MAX_REFRESH_RATE = 240f
	}
}
