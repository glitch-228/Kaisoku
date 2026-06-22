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
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

abstract class BaseReaderFragment<B : ViewBinding> : BaseFragment<B>(), ZoomControl.ZoomControlListener {

	protected val viewModel by activityViewModels<ReaderViewModel>()

	protected var readerAdapter: BaseReaderAdapter<*>? = null
		private set

	override fun onViewBindingCreated(binding: B, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		readerAdapter = onCreateAdapter()

		viewModel.content.observe(viewLifecycleOwner) {
			val currentState = viewModel.getCurrentState()
			val pendingState = when {
				// Loading the prev/next chapter re-emits content with a null state while items already
				// exist, and the page list shifts (front-trim on next, prepend on prev). Re-anchor to the
				// page the user is on, otherwise the pager keeps a now-stale index and jumps to a
				// different page (e.g. back to the start of the chapter). Guard on non-empty pages so the
				// transient empty-list "clear" emitted by switchChapter() isn't treated as a navigation
				// target (which would otherwise show "content not found").
				readerAdapter?.hasItems == true -> if (it.state == null && it.pages.isNotEmpty()) currentState else null
				it.state == null
					&& it.pages.isNotEmpty() -> currentState
				it.state != currentState
					&& currentState != null
					&& it.pages.any { page -> page.chapterId == currentState.chapterId } -> currentState
				else -> it.state
			}
			onPagesChanged(it.pages, pendingState)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onPause() {
		super.onPause()
		val state = getCurrentState()
		viewModel.saveCurrentState(state)
	}

	override fun onDestroyView() {
		viewModel.saveCurrentState(getCurrentState())
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

	protected abstract fun onCreateAdapter(): BaseReaderAdapter<*>

	protected abstract suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?)
}
