package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.view.View
import org.koitharu.kotatsu.reader.ui.pager.PageRotation
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.databinding.ItemPageWebtoonBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState

class WebtoonHolder internal constructor(
    owner: LifecycleOwner,
    binding: ItemPageWebtoonBinding,
    loader: PageLoader,
    readerSettingsProducer: ReaderSettings.Producer,
    networkState: NetworkState,
    exceptionResolver: ExceptionResolver,
    private val pageSizeCache: WebtoonPageSizeCache,
) : BasePageHolder<ItemPageWebtoonBinding>(
    binding = binding,
    loader = loader,
    readerSettingsProducer = readerSettingsProducer,
    networkState = networkState,
    exceptionResolver = exceptionResolver,
    lifecycleOwner = owner,
) {

    override val ssiv = binding.ssiv

    private var scrollToRestore = 0
    private var scrollPercentToRestore = -1 // percentage * 10000, or -1 if none
    private var isInitialScrollApplied = false
    private var boundPageKey: WebtoonPageKey? = null
    // Guards a deferred percent restore: if the user scrolls before the image becomes ready, this
    // returns false and the (now stale) restore is dropped instead of teleporting the view back.
    private var restoreValidator: (() -> Boolean)? = null

    init {
        bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
    }

    override fun onBind(data: org.koitharu.kotatsu.reader.ui.pager.ReaderPage) {
        super.onBind(data)
        val newPageKey = WebtoonPageKey(data.chapterId, data.id)
        if (boundPageKey != null && boundPageKey != newPageKey) {
            // A cached holder can be rebound without entering the recycled pool first. Do not leave
            // the previous panel visible below the new page's loading UI.
            binding.ssiv.recycle()
        }
        boundPageKey = newPageKey
        binding.ssiv.setPlaceholderSize(pageSizeCache[newPageKey]?.let { size ->
            if (PageRotation.orientation(size.width, size.height, settings.widePageRotation) != 0) {
                WebtoonImageSize(size.height, size.width)
            } else size
        })
        binding.ssiv.setCompactPlaceholderEnabled(false)
        scrollPercentToRestore = -1
        scrollToRestore = 0
        isInitialScrollApplied = false
        restoreValidator = null
    }

    override fun onStateChanged(state: PageState) {
        if (state is PageState.Error || state is PageState.Hidden) {
            val recyclerView = itemView.parent as? RecyclerView
            val isAlreadyAboveViewport = recyclerView != null && itemView.bottom <= recyclerView.paddingTop
            if (!isAlreadyAboveViewport) {
                // Once an unresolved image has failed, keep its retry/loading slot compact. Expanding
                // it again for a retry would push all following panels down, while shrinking a failed
                // holder that is already above the viewport would pull the current panel upward.
                binding.ssiv.setCompactPlaceholderEnabled(true)
            }
        }
        super.onStateChanged(state)
    }

    override fun onPagePresentationChanging() {
        if (scrollPercentToRestore >= 0) return
        val recyclerView = itemView.parent as? WebtoonRecyclerView ?: return
        // Only the panel covering the top edge owns a layout-change anchor. Anchoring every
        // attached panel would make adjacent/preloaded pages move the current reader position.
        if (itemView.top > 0 || itemView.bottom <= 0) return
        val pageKey = boundPageKey
        val scrollGeneration = recyclerView.scrollGeneration
        scrollPercentToRestore = if (isPresentationReady) {
            (getScrollProgress() * 10_000).toInt()
        } else {
            calculateUnloadedPageScrollPercent(itemView.top, itemView.height)
        }
        restoreValidator = { boundPageKey == pageKey && recyclerView.scrollGeneration == scrollGeneration }
    }

    override fun onAnimatedImageShown(width: Int, height: Int) {
        boundPageKey?.let { key ->
            val drawable = (binding.animatedView.drawable as? org.koitharu.kotatsu.reader.ui.pager.RotatedPageDrawable)?.source
                ?: binding.animatedView.drawable
            pageSizeCache.put(key, drawable?.intrinsicWidth ?: width, drawable?.intrinsicHeight ?: height)
        }
        val pageKey = boundPageKey
        itemView.doOnNextLayout {
            if (boundPageKey == pageKey && isAnimatedPageDisplayed) applyReadyScroll()
        }
        itemView.requestLayout()
    }

    override fun onPageReady() {
        if (isAnimatedPageDisplayed || !binding.ssiv.isReady) return
        binding.ssiv.adjustScale()
        binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
        boundPageKey?.let { key ->
            pageSizeCache.put(key, binding.ssiv.sWidth, binding.ssiv.sHeight)
        }
        val key = boundPageKey
        itemView.doOnLayout {
            if (boundPageKey == key && !isAnimatedPageDisplayed) applyReadyScroll()
        }
    }

    private val isPresentationReady: Boolean
        get() = if (isAnimatedPageDisplayed) binding.animatedView.drawable != null else binding.ssiv.isReady

    private fun applyReadyScroll() {
        val pageKey = boundPageKey
        when {
            scrollPercentToRestore >= 0 -> {
                val percent = scrollPercentToRestore
                val validator = restoreValidator
                scrollPercentToRestore = -1
                scrollToRestore = 0
                restoreValidator = null
                isInitialScrollApplied = true
                if (validator == null || validator()) {
                    itemView.post {
                        if (boundPageKey == pageKey && (validator == null || validator())) {
                            applyScrollPercent(percent)
                        }
                    }
                }
            }

            scrollToRestore != 0 -> {
                val scroll = scrollToRestore
                scrollToRestore = 0
                isInitialScrollApplied = true
                itemView.post {
                    if (boundPageKey == pageKey && !isAnimatedPageDisplayed) binding.ssiv.scrollTo(scroll)
                }
            }

            !isInitialScrollApplied -> {
                val recyclerView = itemView.parent as? WebtoonRecyclerView
                val scrollGeneration = recyclerView?.scrollGeneration
                val wasAboveViewport = itemView.bottom <= 0
                val wasBelowViewport = recyclerView != null && itemView.top >= recyclerView.height
                val percent = calculateUnloadedPageScrollPercent(itemView.top, itemView.height)
                isInitialScrollApplied = true
                itemView.post {
                    if (boundPageKey != pageKey) return@post
                    when {
                        wasAboveViewport -> if (!isAnimatedPageDisplayed) {
                            binding.ssiv.scrollTo(binding.ssiv.getScrollRange())
                        }
                        wasBelowViewport || percent == 0 -> if (!isAnimatedPageDisplayed) binding.ssiv.scrollTo(0)
                        recyclerView != null && recyclerView.scrollGeneration != scrollGeneration -> {
                            // The user kept moving during the ready/layout frame. A stale re-anchor is
                            // worse than leaving the newly decoded image at its current position.
                            Unit
                        }
                        else -> applyScrollPercent(percent)
                    }
                }
            }
            else -> {
            }
        }
    }

    fun getScrollY() = binding.ssiv.getScroll()

    fun restoreScroll(scroll: Int) {
        if (binding.ssiv.isReady) {
            binding.ssiv.scrollTo(scroll)
        } else {
            scrollToRestore = scroll
        }
    }

    /**
     * Combined offset: total content pixels above viewport top.
     * = SSIV internal scroll + how far the item has scrolled above the RV top.
     */
    fun getFullScrollOffset(): Int {
        val rvScrollAbove = (-itemView.top).coerceAtLeast(0)
        return (if (isAnimatedPageDisplayed) 0 else binding.ssiv.getScroll()) + rvScrollAbove
    }

    /**
     * Scroll progress as fraction 0.0-1.0 of total page content height.
     */
    fun getScrollProgress(): Float {
        val scrollRange = if (isAnimatedPageDisplayed) 0 else binding.ssiv.getScrollRange()
        val totalHeight = scrollRange + itemView.height
        if (totalHeight <= 0) return 0f
        return (getFullScrollOffset().toFloat() / totalHeight).coerceIn(0f, 1f)
    }

    /**
     * Restore scroll from percentage (encoded as percentage * 10000).
     * Applies when the current presentation is ready, including animated pages without SSIV.
     */
    fun restoreScrollPercent(percentTimes10000: Int, isValid: () -> Boolean = { true }) {
        val normalized = percentTimes10000.coerceIn(0, 10_000)
        if (isPresentationReady && !itemView.isLayoutRequested) {
            // Applied synchronously while still current, so no staleness check is needed.
            applyScrollPercent(normalized)
            scrollPercentToRestore = -1
            restoreValidator = null
        } else {
            scrollPercentToRestore = normalized
            restoreValidator = isValid
            if (isPresentationReady) {
                val pageKey = boundPageKey
                itemView.doOnLayout { if (boundPageKey == pageKey) applyReadyScroll() }
            }
        }
    }

    private fun applyScrollPercent(percentTimes10000: Int) {
        val position = calculateWebtoonScrollPosition(
            percentTimes10000,
            if (isAnimatedPageDisplayed) 0 else binding.ssiv.getScrollRange(),
            itemView.height,
        )

        val adapterPosition = bindingAdapterPosition
        if (adapterPosition != RecyclerView.NO_POSITION) {
            val layoutManager = (itemView.parent as? RecyclerView)?.layoutManager as? LinearLayoutManager
            layoutManager?.scrollToPositionWithOffset(adapterPosition, position.itemTop)
        }
        if (!isAnimatedPageDisplayed) binding.ssiv.scrollTo(position.internalScroll)
        isInitialScrollApplied = true
    }
}
