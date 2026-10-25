package org.koitharu.kotatsu.details.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.LayoutInlineChaptersBinding
import org.koitharu.kotatsu.details.ui.adapter.ChaptersAdapter
import org.koitharu.kotatsu.details.ui.adapter.ChaptersSelectionDecoration
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChapterPagesMenuProvider
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet
import org.koitharu.kotatsu.details.ui.pager.chapters.ChapterGridSpanHelper
import org.koitharu.kotatsu.details.ui.pager.chapters.ChaptersSelectionCallback
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.reader.ui.ReaderState

/** Shares the details VM with the sheet. This controller performs no source or history requests. */
internal class InlineChaptersController(
    private val activity: DetailsActivity,
    private val viewModel: DetailsViewModel,
    settings: AppSettings,
    scrollView: NestedScrollView,
) : OnListItemClickListener<ChapterListItem> {
    val recyclerView = RecyclerView(activity).apply {
        id = R.id.recycler_details_inline
        clipToPadding = false
        isVerticalScrollBarEnabled = true
        layoutManager = LinearLayoutManager(activity)
        itemAnimator = null
    }
    private val controls = LayoutInlineChaptersBinding.inflate(activity.layoutInflater, recyclerView, false)
    private val chaptersAdapter = ChaptersAdapter(this).apply {
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT
    }
    private val menuProvider = ChapterPagesMenuProvider(viewModel, settings,
        currentTab = { ChaptersPagesSheet.TAB_CHAPTERS },
        showExactCompletion = {
            viewModel.getMangaOrNull()?.id?.let {
                org.koitharu.kotatsu.details.ui.pager.ExactCompletionDialog.show(activity.supportFragmentManager, it)
            }
        })
    private var gridMode: Boolean? = null
    var isSelectionActive = false
        private set
    private val selection: ListSelectionController

    init {
        menuProvider.restoreSearchState(activity.savedStateRegistry.consumeRestoredStateForKey(SEARCH_STATE))
        activity.savedStateRegistry.registerSavedStateProvider(SEARCH_STATE, menuProvider::saveSearchState)
        val metadata = scrollView.getChildAt(0)
        val parent = scrollView.parent as ViewGroup
        val index = parent.indexOfChild(scrollView)
        val params = scrollView.layoutParams
        scrollView.removeView(metadata)
        parent.removeView(scrollView)
        recyclerView.setPadding(scrollView.paddingLeft, scrollView.paddingTop,
            scrollView.paddingRight, scrollView.paddingBottom)
        recyclerView.adapter = InlineChaptersAdapter.create(metadata, controls.root, chaptersAdapter)
        parent.addView(recyclerView, index, params)

        val callback = ChaptersSelectionCallback(viewModel, activity.router, recyclerView)
        selection = ListSelectionController(activity.delegate, ChaptersSelectionDecoration(activity), activity,
            object : ListSelectionController.Callback by callback {
                override fun onCreateActionMode(controller: ListSelectionController,
                    menuInflater: MenuInflater, menu: Menu): Boolean {
                    return callback.onCreateActionMode(controller, menuInflater, menu).also {
                        if (!controller.isContextMenuOpen) isSelectionActive = it
                    }
                }
                override fun onDestroyActionMode(controller: ListSelectionController, mode: ActionMode) {
                    callback.onDestroyActionMode(controller, mode)
                    isSelectionActive = false
                }
            })
        selection.attachToRecyclerView(recyclerView)
        // Fixed headers own their padding; spacing applies only to chapter/volume delegates.
        val spacing = TypedListSpacingDecoration(activity, true)
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View,
                parent: RecyclerView, state: RecyclerView.State) {
                if (parent.getChildViewHolder(view).itemViewType in InlineChaptersAdapter.headerTypes) outRect.setEmpty()
                else spacing.getItemOffsets(outRect, view, parent, state)
            }
        })
        ChapterGridSpanHelper.attach(recyclerView)
        controls.toolbarInlineChapters.addMenuProvider(menuProvider, activity, Lifecycle.State.STARTED)
        activity.onBackPressedDispatcher.addCallback(activity, menuProvider)
        controls.chipsInlineBranches.onChipClickListener =
            org.koitharu.kotatsu.core.ui.widgets.ChipsView.OnChipClickListener { _, data ->
                (data as? ListFilterOption.Branch)?.let { viewModel.setSelectedBranch(it.titleText) }
            }

        viewModel.isChaptersInGridView.observe(activity) { grid ->
            if (gridMode != grid) {
                val state = recyclerView.layoutManager?.onSaveInstanceState()
                recyclerView.layoutManager = if (grid) {
                    GridLayoutManager(activity, ChapterGridSpanHelper.getSpanCount(recyclerView)).apply {
                        spanSizeLookup = ChapterGridSpanHelper.SpanSizeLookup(recyclerView,
                            InlineChaptersAdapter.headerTypes)
                    }
                } else LinearLayoutManager(activity)
                if (state != null) recyclerView.layoutManager?.onRestoreInstanceState(state)
                gridMode = grid
            }
            updateMenu()
        }
        viewModel.isReadingOrderReversed.observe(activity) { updateMenu() }
        viewModel.isGlobalChaptersReversed.observe(activity) { updateMenu() }
        viewModel.isChaptersSortedByName.observe(activity) { updateMenu() }
        viewModel.isChaptersSortedByDate.observe(activity) { updateMenu() }
        viewModel.isDownloadedOnly.observe(activity) { updateMenu() }
        viewModel.quickFilter.observe(activity) {
            controls.chipsInlineBranches.setChips(it)
            controls.scrollInlineBranches.isVisible = it.isNotEmpty()
        }
        chaptersAdapter.addListListener { _, list ->
            // Headers alone must never consume a pending RecyclerView scroll restore.
            if (list.isNotEmpty()) allowScrollRestore()
            updateStatus()
        }
        viewModel.chapters.map { it.withVolumeHeaders(activity) }.flowOn(Dispatchers.Default)
            .observe(activity, chaptersAdapter)
        viewModel.isLoading.observe(activity) {
            controls.progressInlineChapters.isVisible = it
            updateStatus()
        }
        viewModel.emptyReason.observe(activity) { updateStatus() }
        viewModel.mangaDetails.observe(activity) {
            updateMenu()
            updateStatus()
        }
    }

    private fun updateMenu() = menuProvider.updateMenu(controls.toolbarInlineChapters.menu)

    private fun updateStatus() {
        val details = viewModel.mangaDetails.value
        val loadedEmpty = details?.isLoaded == true && details.allChapters.isEmpty() && !viewModel.isLoading.value
        if (loadedEmpty) allowScrollRestore()
        val reason = viewModel.emptyReason.value?.msgResId
            ?: R.string.chapters_empty.takeIf {
                details?.isLoaded == true && !viewModel.isLoading.value && chaptersAdapter.itemCount == 0
            } ?: 0
        controls.textInlineChaptersStatus.setTextAndVisible(reason)
    }

    private fun allowScrollRestore() {
        chaptersAdapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.ALLOW
    }

    override fun onItemClick(item: ChapterListItem, view: View) {
        if (selection.onItemClick(item.chapter.id)) return
        activity.router.openReader(ReaderIntent.Builder(activity)
            .manga(viewModel.getMangaOrNull() ?: return)
            .state(ReaderState(item.chapter.id, 0, 0)).build())
    }

    override fun onItemLongClick(item: ChapterListItem, view: View) = selection.onItemLongClick(view, item.chapter.id)
    override fun onItemContextClick(item: ChapterListItem, view: View) = selection.onItemContextClick(view, item.chapter.id)

    fun collapseSearch() = menuProvider.collapseSearch()

    fun close() {
        activity.savedStateRegistry.unregisterSavedStateProvider(SEARCH_STATE)
        controls.toolbarInlineChapters.removeMenuProvider(menuProvider)
        menuProvider.remove()
        selection.clear()
        recyclerView.adapter = null
        recyclerView.clearOnScrollListeners()
    }

    private companion object {
        const val SEARCH_STATE = "inline_chapters_search"
    }
}
