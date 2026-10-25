package org.koitharu.kotatsu.reader.ui.pager.webtoon

import org.koitharu.kotatsu.reader.ui.pager.retainedPageCount

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
import androidx.lifecycle.Lifecycle
import org.koitharu.kotatsu.reader.ui.pager.screenToLocal
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
import org.koitharu.kotatsu.reader.ui.config.ReaderZoomScope
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun resolveWebtoonModeSwitchPosition(
    firstVisiblePosition: Int,
    readingLinePosition: Int,
    itemCount: Int,
    isAtAbsoluteBottom: Boolean,
): Int = when {
    itemCount <= 0 -> RecyclerView.NO_POSITION
    isAtAbsoluteBottom -> itemCount - 1
    readingLinePosition in 0 until itemCount -> readingLinePosition
    firstVisiblePosition in 0 until itemCount -> firstVisiblePosition
    else -> RecyclerView.NO_POSITION
}

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
    private var wasAtAbsoluteBottom = false
    // Bumped on movement and lifecycle pauses; a deferred scroll restore captures the value at
    // request time and is dropped if it no longer matches, so it cannot teleport the view back.
    private var restoreToken = 0
    private var pendingRestoreListener: RecyclerView.OnChildAttachStateChangeListener? = null

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ) = FragmentReaderWebtoonBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: FragmentReaderWebtoonBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        with(binding.recyclerView) {
            setHasFixedSize(true)
            // Match the mature Mihon webtoon viewer: keep nearby decoded holders available across
            // direction changes and disable dataset animations that move variable-height pages.
            setItemViewCacheSize(context.retainedPageCount(WEBTOON_VIEW_CACHE_SIZE))
            itemAnimator = null
            adapter = readerAdapter
            addOnPageScrollListener(this@WebtoonReaderFragment)
            recyclerLifecycleDispatcher = RecyclerViewLifecycleDispatcher().also {
                addOnScrollListener(it)
            }
            setOnPullGestureListener(this@WebtoonReaderFragment)
        }
        val producer = viewModel.readerSettingsProducer
        val memory = producer.zoomMemory
        val remembered = memory.get(ReaderZoomScope.WEBTOON)?.takeIf { producer.isWebtoonZoomEnabled }
        binding.frame.isZoomEnable = producer.isWebtoonZoomEnabled
        remembered?.coerceIn(.5f, 2.5f)?.let { binding.frame.zoom = it }
        binding.frame.onUserZoomChanged = { zoom ->
            if (binding.frame.isZoomEnable && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                memory.record(ReaderZoomScope.WEBTOON, zoom)
            }
        }
        viewModel.isWebtoonZooEnabled.observe(viewLifecycleOwner) {
            // Its lazily started StateFlow initially holds false; use the current saved preference.
            binding.frame.isZoomEnable = producer.isWebtoonZoomEnabled
        }
        viewModel.defaultWebtoonZoomOut.take(1).observe(viewLifecycleOwner) {
            if (remembered == null) binding.frame.zoom = 1f - it
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
        requireViewBinding().frame.onUserZoomChanged = null
        invalidatePendingRestore()
        recyclerLifecycleDispatcher = null
        requireViewBinding().recyclerView.adapter = null
        super.onDestroyView()
    }

    override fun onPause() {
        // An image can finish decoding while the screen is off. Disarm every holder/listener restore
        // before saving the actual visible state so a late callback cannot move the retained view.
        invalidatePendingRestore()
        super.onPause()
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
        // Any requested movement makes a pending deferred restore stale.
        invalidatePendingRestore()
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
        viewModel.updateScrollProgress(progress, adapterContentGeneration)
        if (firstPage != null) {
            viewModel.updateScrollOffset(
                chapterId = firstPage.chapterId,
                page = firstPage.index,
                offset = if (progress >= 0f) {
                    (progress * 10000).toInt()
                } else {
                    getSavedScrollOffset(firstPage.chapterId, firstPage.index)
                },
                contentGeneration = adapterContentGeneration,
            )
        }
        val itemCount = adapter?.itemCount ?: 0
        val atAbsoluteBottom = itemCount > 0 && recyclerView.isScrolledToAbsoluteBottom()
        val positionsChanged = firstVisiblePosition != lastFirstPos || lastVisiblePosition != lastLastPos
        if (shouldReportAbsoluteBottom(atAbsoluteBottom, wasAtAbsoluteBottom)) {
            lastFirstPos = firstVisiblePosition
            lastLastPos = lastVisiblePosition
            // Reaching the end inside a tall final image does not necessarily change visible adapter
            // positions. Report the transition independently so live and persisted progress reach 100%.
            val lastIndex = itemCount - 1
            viewModel.onCurrentPageChanged(
                lastIndex,
                lastIndex,
                1f,
                10000,
                contentGeneration = adapterContentGeneration,
            )
        } else if (!atAbsoluteBottom && positionsChanged) {
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
            viewModel.onCurrentPageChanged(
                firstVisiblePosition,
                lastVisiblePosition,
                progress,
                scrollPercent,
                contentGeneration = adapterContentGeneration,
            )
        }
        wasAtAbsoluteBottom = atAbsoluteBottom
    }

    override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) = coroutineScope {
        wasAtAbsoluteBottom = false
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
                val restoreTarget = WebtoonPageKey(pages[position].chapterId, pages[position].id)
                with(requireViewBinding().recyclerView) {
                    firstVisibleItemPosition = position
                    postRestoreScroll(this, position, restoreTarget, pendingState.scroll)
                }
                viewModel.onCurrentPageChanged(
                    position,
                    position,
                    scrollProgress = pendingState.scroll / 10000f,
                    scrollOffset = pendingState.scroll,
                    contentGeneration = adapterContentGeneration,
                )
            } else {
                Snackbar.make(requireView(), R.string.not_found_404, Snackbar.LENGTH_SHORT)
                    .show()
            }
        } else {
            setItems.join()
        }
    }

    override fun findActionPageAt(rawX: Float, rawY: Float): ReaderPage? {
        val rv = viewBinding?.recyclerView ?: return null
        val point = rv.screenToLocal(rawX, rawY) ?: return null
        val child = rv.findChildViewUnder(point.x, point.y) ?: return null
        val position = rv.getChildAdapterPosition(child)
        return readerAdapter?.getItemOrNull(position)
    }

    override fun getCurrentState(): ReaderState? = viewBinding?.run {
        val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            ?: return@run null
        val adapter = recyclerView.adapter as? BaseReaderAdapter<*>
        val itemCount = adapter?.itemCount ?: 0
        if (itemCount > 0 && recyclerView.isScrolledToAbsoluteBottom()) {
            // At the very end, persist the last page at full scroll so resuming lands at the end and
            // reading progress reads 100% (the first-visible page can lag behind the last index here).
            val lastPage = adapter?.getItemOrNull(itemCount - 1)
            if (lastPage != null) {
                return@run ReaderState(chapterId = lastPage.chapterId, page = lastPage.index, scroll = 10000)
            }
        }
        val currentItem = lm.findFirstVisibleItemPosition()
        if (currentItem == RecyclerView.NO_POSITION) return@run null
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

    override fun getModeSwitchState(): ReaderState? = viewBinding?.run {
        val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            ?: return@run null
        val adapter = recyclerView.adapter as? BaseReaderAdapter<*>
        val itemCount = adapter?.itemCount ?: 0
        val isAtAbsoluteBottom = itemCount > 0 && recyclerView.isScrolledToAbsoluteBottom()
        val currentItem = resolveWebtoonModeSwitchPosition(
            firstVisiblePosition = lm.findFirstVisibleItemPosition(),
            readingLinePosition = recyclerView.findCurrentPagePosition(),
            itemCount = itemCount,
            isAtAbsoluteBottom = isAtAbsoluteBottom,
        )
        val page = adapter?.getItemOrNull(currentItem) ?: return@run null
        if (isAtAbsoluteBottom) {
            return@run ReaderState(chapterId = page.chapterId, page = page.index, scroll = 10000)
        }
        val holder = recyclerView.findViewHolderForAdapterPosition(currentItem) as? WebtoonHolder
        val fallbackScroll = getSavedScrollOffset(page.chapterId, page.index)
        val progress = holder?.getScrollProgress() ?: getSavedScrollPercent(page.chapterId, page.index)
        ReaderState(
            chapterId = page.chapterId,
            page = page.index,
            scroll = if (progress >= 0f) (progress * 10000).toInt() else fallbackScroll,
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

    private fun postRestoreScroll(
        rv: RecyclerView,
        position: Int,
        target: WebtoonPageKey,
        scrollPercent: Int,
    ) {
        // Replace any earlier restore that hasn't fired yet, so listeners don't pile up across rapid
        // chapter switches / re-anchors.
        removePendingRestoreListener()
        val token = restoreToken
        if (restoreAttachedHolder(rv, position, target, scrollPercent, token)) {
            return
        }
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: android.view.View) {
                val holder = rv.getChildViewHolder(view) as? WebtoonHolder
                if (holder != null && holder.bindingAdapterPosition == position) {
                    rv.removeOnChildAttachStateChangeListener(this)
                    if (pendingRestoreListener === this) {
                        pendingRestoreListener = null
                    }
                    // Skip if the user has scrolled since this restore was requested. The holder
                    // re-checks the same token when its image becomes ready (deferred restore).
                    restoreHolder(holder, target, scrollPercent, token)
                }
            }
            override fun onChildViewDetachedFromWindow(view: android.view.View) {}
        }
        pendingRestoreListener = listener
        rv.addOnChildAttachStateChangeListener(listener)
        // scrollToPositionWithOffset may leave the requested holder attached without producing a new
        // attach callback. Check once after layout so this listener cannot remain armed indefinitely.
            rv.post {
            if (pendingRestoreListener === listener &&
                restoreAttachedHolder(rv, position, target, scrollPercent, token)
            ) {
                rv.removeOnChildAttachStateChangeListener(listener)
                pendingRestoreListener = null
            }
        }
    }

    private fun restoreAttachedHolder(
        rv: RecyclerView,
        position: Int,
        target: WebtoonPageKey,
        scrollPercent: Int,
        token: Int,
    ): Boolean {
        val holder = rv.findViewHolderForAdapterPosition(position) as? WebtoonHolder ?: return false
        if (holder.bindingAdapterPosition != position) return false
        restoreHolder(holder, target, scrollPercent, token)
        return true
    }

    private fun restoreHolder(
        holder: WebtoonHolder,
        target: WebtoonPageKey,
        scrollPercent: Int,
        token: Int,
    ) {
        val isValid = {
            val page = holder.boundData
            token == restoreToken && isRestoreTarget(target, page?.chapterId, page?.id)
        }
        if (isValid()) {
            holder.restoreScrollPercent(scrollPercent, isValid)
        }
    }

    private fun invalidatePendingRestore() {
        restoreToken++
        removePendingRestoreListener()
    }

    private fun removePendingRestoreListener() {
        pendingRestoreListener?.let { listener ->
            viewBinding?.recyclerView?.removeOnChildAttachStateChangeListener(listener)
        }
        pendingRestoreListener = null
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
        private const val WEBTOON_VIEW_CACHE_SIZE = 4
    }
}
