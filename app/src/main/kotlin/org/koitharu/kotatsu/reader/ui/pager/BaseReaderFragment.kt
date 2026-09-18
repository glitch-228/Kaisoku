package org.koitharu.kotatsu.reader.ui.pager

import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import androidx.viewbinding.ViewBinding
import org.koitharu.kotatsu.core.prefs.ReaderAnimation
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderStateSnapshot
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

internal fun shouldReanchorAfterPageListUpdate(oldPosition: Int, newPosition: Int): Boolean =
	oldPosition >= 0 && newPosition >= 0 && oldPosition != newPosition

abstract class BaseReaderFragment<B : ViewBinding> : BaseFragment<B>(), ZoomControl.ZoomControlListener {

	protected val viewModel by activityViewModels<ReaderViewModel>()

	protected var readerAdapter: BaseReaderAdapter<*>? = null
		private set
	protected var adapterContentGeneration: Long = NO_CONTENT_GENERATION
		private set

	override fun onViewBindingCreated(binding: B, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		readerAdapter = onCreateAdapter()

		viewModel.content.observe(viewLifecycleOwner) {
			val adapterHadItems = readerAdapter?.hasItems == true
			val currentState = viewModel.getCurrentState()
			val replacementState = viewModel.getPendingReaderReplacementState(it.replacementId)
			val currentOldPosition = currentState?.let { state ->
				readerAdapter?.indexOf(state.chapterId, state.page)
			} ?: -1
			val currentNewPosition = currentState?.let { state ->
				adapterPages(it.pages).indexOfFirst { page ->
					page.chapterId == state.chapterId && page.index == state.page
				}
			} ?: -1
			val pendingState = when {
				replacementState != null
					&& (!adapterHadItems || it.forceStateRestore)
					&& it.state == replacementState
					&& it.pages.any { page ->
						page.chapterId == replacementState.chapterId && page.index == replacementState.page
					} -> replacementState
				// Appending a preloaded next chapter does not move the current page, and RecyclerView's
				// diff keeps its visual position. Re-anchoring that unchanged page interrupts an active
				// webtoon scroll and can make holders reload. Only restore when a prepend/front trim
				// actually changed the current page's adapter position.
				adapterHadItems -> if (
					it.state == null &&
					it.pages.isNotEmpty() &&
					shouldReanchorAfterPageListUpdate(currentOldPosition, currentNewPosition)
				) {
					currentState
				} else {
					null
				}
				it.state == null
					&& it.pages.isNotEmpty() -> currentState
				it.state != currentState
					&& currentState != null
					&& it.pages.any { page -> page.chapterId == currentState.chapterId } -> currentState
				else -> it.state
			}
			onPagesChanged(it.pages, pendingState)
			// AsyncListDiffer is now known to represent this publication. Callbacks raised while the
			// diff was being applied carried the previous generation and were deliberately ignored.
			adapterContentGeneration = it.generation
			if (pendingState != null) {
				viewModel.onReaderStateRestored(it.replacementId, pendingState, it.generation)
			}
			if (!adapterHadItems || pendingState != null) {
				// Confirm initial/restored content after the adapter generation becomes authoritative.
				// This also retains bounds preloading when Continue opens on a chapter's last page.
				viewModel.onReaderContentApplied(it.generation)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onPause() {
		super.onPause()
		viewModel.saveVisibleState(getCurrentStateSnapshot())
	}

	override fun onDestroyView() {
		viewModel.saveVisibleState(getCurrentStateSnapshot())
		readerAdapter = null
		super.onDestroyView()
	}

	protected fun requireAdapter() = checkNotNull(readerAdapter) {
		"Adapter was not created or already destroyed"
	}

	protected fun isAnimationEnabled(): Boolean {
		return context?.isAnimationsEnabled == true && viewModel.pageAnimation.value != ReaderAnimation.NONE
	}

	abstract fun switchPageBy(delta: Int)

	abstract fun switchPageTo(position: Int, smooth: Boolean)

	open fun scrollBy(delta: Int, smooth: Boolean): Boolean = false

	abstract fun getCurrentState(): ReaderState?

	/**
	 * State used when handing the visible position to a different reader implementation. Most
	 * readers use the same state for persistence and mode changes, while webtoon keeps a separate
	 * precise resume anchor and visible reading-page anchor.
	 */
	open fun getModeSwitchState(): ReaderState? = getCurrentState()

	fun getCurrentStateSnapshot() = ReaderStateSnapshot(
		state = getCurrentState(),
		contentGeneration = adapterContentGeneration,
	)

	fun getModeSwitchStateSnapshot() = ReaderStateSnapshot(
		state = getModeSwitchState(),
		contentGeneration = adapterContentGeneration,
	)

	/** The exact ordering/padding submitted to the adapter, also used for anchor comparisons. */
	protected open fun adapterPages(pages: List<ReaderPage>): List<ReaderPage> = pages

	protected abstract fun onCreateAdapter(): BaseReaderAdapter<*>

	protected abstract suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?)

	private companion object {

		const val NO_CONTENT_GENERATION = -1L
	}
}
