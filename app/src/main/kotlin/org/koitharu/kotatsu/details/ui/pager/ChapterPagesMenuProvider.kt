package org.koitharu.kotatsu.details.ui.pager

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.progress.IntPercentLabelFormatter
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_CHAPTERS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import java.lang.ref.WeakReference

class ChapterPagesMenuProvider(
    private val viewModel: ChaptersPagesViewModel,
    private val settings: AppSettings,
    private val currentTab: () -> Int,
    private val expandHost: () -> Unit = {},
    private val unlockHost: () -> Unit = {},
    private val reverseReadingOrder: (Boolean) -> Unit = viewModel::setReadingOrderReversed,
    private val showExactCompletion: () -> Unit = {},
) : OnBackPressedCallback(false), MenuProvider, SearchView.OnQueryTextListener, MenuItem.OnActionExpandListener,
    Slider.OnChangeListener {

    constructor(viewModel: ChaptersPagesViewModel, sheet: BaseAdaptiveSheet<*>, pager: ViewPager2,
        settings: AppSettings) : this(
        viewModel = viewModel,
        settings = settings,
        currentTab = {
            val page = pager.currentItem
            if (page > 0 && pager.adapter?.itemCount == 2) page + 1 else page
        },
        expandHost = sheet::expandAndLock,
        unlockHost = sheet::unlock,
        reverseReadingOrder = { reversed ->
            val reader = sheet.activity as? ReaderActivity
            if (reader != null) reader.setReadingOrderReversed(reversed)
            else viewModel.setReadingOrderReversed(reversed)
        },
        showExactCompletion = {
            viewModel.getMangaOrNull()?.id?.let { ExactCompletionDialog.show(sheet.parentFragmentManager, it) }
        },
    )

    private var expandedItemRef: WeakReference<MenuItem>? = null
    private var chapterQuery = ""
    private var restoreExpandedSearch = false
    private var expandedChapterSearch = false

    fun saveSearchState() = Bundle().apply {
        putBoolean("expanded", expandedChapterSearch && currentTab() == TAB_CHAPTERS)
        putString("query", chapterQuery)
    }

    fun restoreSearchState(state: Bundle?) {
        if (state == null) return
        restoreExpandedSearch = state.getBoolean("expanded", false)
        chapterQuery = state.getString("query").orEmpty().takeIf { restoreExpandedSearch }.orEmpty()
        if (restoreExpandedSearch) viewModel.performChapterSearch(chapterQuery)
    }

    fun collapseSearch() {
        if (isEnabled && expandedItemRef?.get()?.collapseActionView() != true) {
            expandedItemRef = null
            restoreExpandedSearch = false
            expandedChapterSearch = false
            chapterQuery = ""
            isEnabled = false
            viewModel.performChapterSearch(null)
            unlockHost()
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        val tab = currentTab()
        when (tab) {
            TAB_CHAPTERS -> {
                menuInflater.inflate(R.menu.opt_chapters, menu)
                menu.findItem(R.id.action_search)?.run {
                    setOnActionExpandListener(this@ChapterPagesMenuProvider)
                    (actionView as? SearchView)?.setupChaptersSearchView()
                    if (restoreExpandedSearch || expandedChapterSearch) {
                        restoreExpandedSearch = false
                        expandActionView()
                    }
                }
                updateMenu(menu)
            }

            TAB_PAGES, TAB_BOOKMARKS -> {
                menuInflater.inflate(R.menu.opt_pages, menu)
                menu.findItem(R.id.action_grid_size)?.run {
                    setOnActionExpandListener(this@ChapterPagesMenuProvider)
                    (actionView as? Slider)?.setupPagesSizeSlider()
                }
            }
        }
    }

    override fun onPrepareMenu(menu: Menu) = updateMenu(menu)

    fun updateMenu(menu: Menu) {
        if (currentTab() != TAB_CHAPTERS) return
        menu.findItem(R.id.action_search)?.isVisible = viewModel.emptyReason.value == null
        menu.findItem(R.id.action_reverse_chapter_list)?.isChecked = viewModel.isGlobalChaptersReversed.value == true
        menu.findItem(R.id.action_reverse_reading_order)?.isChecked = viewModel.isReadingOrderReversed.value
        menu.findItem(R.id.action_sort_by_name)?.isChecked = viewModel.isChaptersSortedByName.value
        menu.findItem(R.id.action_sort_by_date)?.isChecked = viewModel.isChaptersSortedByDate.value
        menu.findItem(R.id.action_grid_view)?.isChecked = viewModel.isChaptersInGridView.value == true
        menu.findItem(R.id.action_downloaded)?.let { menuItem ->
            menuItem.isVisible = viewModel.mangaDetails.value?.local != null
            menuItem.isChecked = viewModel.isDownloadedOnly.value == true
    }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        R.id.action_exact_completion -> {
            showExactCompletion()
            true
        }

        R.id.action_reverse_chapter_list -> {
            settings.isChaptersReverse = !settings.isChaptersReverse
            true
        }

        R.id.action_reverse_reading_order -> {
            reverseReadingOrder(!viewModel.isReadingOrderReversed.value)
            true
        }

        R.id.action_sort_by_name -> {
            viewModel.setChaptersSortedByName(!viewModel.isChaptersSortedByName.value)
            true
        }

        R.id.action_sort_by_date -> {
            viewModel.setChaptersSortedByDate(!viewModel.isChaptersSortedByDate.value)
            true
        }

        R.id.action_grid_view -> {
            viewModel.setChaptersInGridView(!menuItem.isChecked)
            true
        }

        R.id.action_downloaded -> {
            viewModel.isDownloadedOnly.value = !menuItem.isChecked
            true
        }

        else -> false
    }

    override fun handleOnBackPressed() {
        collapseSearch()
    }

    override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        expandedItemRef = WeakReference(item)
        expandedChapterSearch = item.actionView is SearchView
        expandHost()
        isEnabled = true
        return true
    }

    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
        expandedItemRef = null
        restoreExpandedSearch = false
        expandedChapterSearch = false
        chapterQuery = ""
        isEnabled = false
        (item.actionView as? SearchView)?.setQuery("", false)
        viewModel.performChapterSearch(null)
        unlockHost()
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean = false

    override fun onQueryTextChange(newText: String?): Boolean {
        chapterQuery = newText.orEmpty()
        viewModel.performChapterSearch(newText)
        return true
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        if (fromUser) {
            settings.gridSizePages = value.toInt()
        }
    }

    private fun SearchView.setupChaptersSearchView() {
        setIconifiedByDefault(false)
        queryHint = context.getString(R.string.search_chapters)
        setQuery(chapterQuery, false)
        setOnQueryTextListener(this@ChapterPagesMenuProvider)
    }

    private fun Slider.setupPagesSizeSlider() {
        valueFrom = 50f
        valueTo = 150f
        stepSize = 5f
        tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
        labelBehavior = LabelFormatter.LABEL_FLOATING
        setLabelFormatter(IntPercentLabelFormatter(context))
        setValueRounded(settings.gridSizePages.toFloat())
        addOnChangeListener(this@ChapterPagesMenuProvider)
    }

}
