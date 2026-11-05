package org.koitharu.kotatsu.search.ui.multi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.search.unified.label
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.core.util.ShareHelper
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.invalidateNestedItemDecorations
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivitySearchBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.MangaSelectionDecoration
import org.koitharu.kotatsu.list.ui.adapter.MangaListListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.ui.multi.adapter.SearchAdapter
import javax.inject.Inject

@AndroidEntryPoint
class SearchActivity :
    BaseActivity<ActivitySearchBinding>(),
    MangaListListener,
    ListSelectionController.Callback {

    @Inject
    lateinit var settings: AppSettings

    private val viewModel by viewModels<SearchViewModel>()
    private lateinit var selectionController: ListSelectionController
    private var pickMode: Boolean = false
    private val sourcePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { setResult(Activity.RESULT_OK, result.data); finish() }
    }

    private val matchSourceKey = "tracker_search_match_source"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivitySearchBinding.inflate(layoutInflater))
        pickMode = intent.getBooleanExtra(AppRouter.KEY_PICK_MODE, false)
        title = when (viewModel.kind) {
            SearchKind.SIMPLE,
            SearchKind.TITLE -> viewModel.query

            SearchKind.AUTHOR -> getString(
                R.string.inline_preference_pattern,
                getString(R.string.author),
                viewModel.query,
            )

            SearchKind.TAG -> getString(R.string.inline_preference_pattern, getString(R.string.genre), viewModel.query)
        }

        val itemClickListener = OnListItemClickListener<SearchResultsListModel> { item, view ->
            if (viewModel.scopedRequest().trackerTarget != null) {
                val request = viewModel.scopedRequest().copy(sources = org.koitharu.kotatsu.search.domain.ReadingSourceScope(
                    org.koitharu.kotatsu.search.domain.SourceScopeKind.SINGLE, item.source.name))
                sourcePicker.launch(AppRouter.listIntent(this, item.source,
                    item.listFilter ?: org.koitharu.kotatsu.parsers.model.MangaListFilter(query = viewModel.query), item.sortOrder)
                    .putExtra(org.koitharu.kotatsu.search.domain.SearchRequest.EXTRA, request.encode()))
            } else if (intent.hasExtra(AppRouter.KEY_TRACKER_TARGET_ID)) {
                sourcePicker.launch(AppRouter.listIntent(this, item.source,
                    item.listFilter ?: org.koitharu.kotatsu.parsers.model.MangaListFilter(query = viewModel.query), item.sortOrder)
                    .putExtra(AppRouter.KEY_TRACKER_TARGET_ID, intent.getLongExtra(AppRouter.KEY_TRACKER_TARGET_ID, 0))
                    .putExtra(AppRouter.KEY_TRACKER_TITLE, intent.getStringExtra(AppRouter.KEY_TRACKER_TITLE)))
            } else if (item.listFilter == null) {
                router.openSearch(item.source, viewModel.query)
            } else {
                router.openList(item.source, item.listFilter, item.sortOrder)
            }
        }
        val sizeResolver = DynamicItemSizeResolver(resources, this, settings, adjustWidth = true)
        val selectionDecoration = MangaSelectionDecoration(this)
        selectionController = ListSelectionController(
            appCompatDelegate = delegate,
            decoration = selectionDecoration,
            registryOwner = this,
            callback = this,
        )
        val adapter = SearchAdapter(
            listener = this,
            itemClickListener = itemClickListener,
            sizeResolver = sizeResolver,
            selectionDecoration = selectionDecoration,
            trackerClick = { card ->
                org.koitharu.kotatsu.search.unified.showTrackerSearchCard(this, card, viewModel.sourceScope,
                    intent.getParcelableExtraCompat<ParcelableManga>(matchSourceKey)?.manga)
            },
            loadCatalogueMore = viewModel::loadCatalogueMore,
        )
        viewBinding.recyclerView.adapter = adapter
        viewBinding.recyclerView.setHasFixedSize(true)
        viewBinding.recyclerView.addItemDecoration(TypedListSpacingDecoration(this, true))

        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        supportActionBar?.setSubtitle(
            if (intent.hasExtra(AppRouter.KEY_TRACKER_TARGET_ID)) R.string.tracker_link_source
            else if (pickMode) R.string.manga_migration else R.string.search_results,
        )

        addMenuProvider(SearchMenuProvider(this, viewModel))
        addMenuProvider(PickModeSearchMenuProvider())
        viewBinding.chipReadingSource.setOnClickListener {
            org.koitharu.kotatsu.search.unified.showReadingSourceSelector(this, viewModel.availableSources.value) {
                viewModel.setSourceScope(it); updateSearchChips()
            }
        }
        viewBinding.chipTracker.setOnClickListener {
            val services = org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.entries.filter {
                viewModel.trackerSearch.tools.legacyRepository(it.name)?.isAuthorized == true
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tracker_search_provider)
                .setItems((listOf(getString(R.string.none)) + services.map { getString(it.titleResId) }).toTypedArray()) { _, index ->
                    if (index == 0) { viewModel.setTracker(null); updateSearchChips() }
                    else lifecycleScope.launch {
                        try {
                            val account = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                viewModel.trackerSearch.connect(services[index - 1])
                            }
                            viewModel.setTracker(account); updateSearchChips()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Exception) { org.koitharu.kotatsu.search.unified.showTrackerSearchError(this@SearchActivity, e) }
                    }
                }.show()
        }
        viewBinding.buttonSearchWarning.setOnClickListener {
            org.koitharu.kotatsu.search.unified.showTrackerSearchErrors(this, viewModel.warnings.value)
        }
        viewModel.availableSources.observe(this) { updateSearchChips() }
        viewModel.warnings.observe(this) { viewBinding.buttonSearchWarning.isVisible = it.isNotEmpty() }
        settings.observe(AppSettings.KEY_COMBINED_TRACKER_SEARCH).observe(this) { updateSearchChips() }
        updateSearchChips()

        viewModel.list.observe(this, adapter)
        viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val barsInsets = insets.systemBarsInsets
        viewBinding.toolbar.updatePadding(
            top = barsInsets.top,
            left = barsInsets.left,
            right = barsInsets.right,
        )
        viewBinding.recyclerView.setPadding(
            left = barsInsets.left,
            top = 0,
            right = barsInsets.right,
            bottom = barsInsets.bottom,
        )
        return insets.consumeAllSystemBarsInsets()
    }

    override fun onItemClick(item: MangaListModel, view: View) {
        if (pickMode) {
            confirmPick(item)
            return
        }
        if (!selectionController.onItemClick(item.id)) {
            router.openDetails(item.toMangaWithOverride())
        }
    }

    private fun confirmPick(item: MangaListModel) {
        val target = item.toMangaWithOverride()
        if (viewModel.scopedRequest().trackerTarget != null) {
            setResult(Activity.RESULT_OK, Intent().putExtra(AppRouter.KEY_MANGA, ParcelableManga(target, withDescription = false)))
            finish(); return
        }
        buildAlertDialog(this, isCentered = true) {
            setTitle(if (intent.hasExtra(AppRouter.KEY_TRACKER_TARGET_ID)) R.string.tracker_link_source else R.string.manga_migration)
            setMessage(
                getString(
                    if (intent.hasExtra(AppRouter.KEY_TRACKER_TARGET_ID)) R.string.tracker_link_source_confirmation
                    else R.string.migrate_to_manga_confirmation,
                    target.title,
                    target.source.getTitle(context),
                ),
            )
            setNegativeButton(android.R.string.cancel, null)
            setNeutralButton(R.string.details) { _, _ ->
                router.openDetails(target)
            }
            setPositiveButton(if (intent.hasExtra(AppRouter.KEY_TRACKER_TARGET_ID)) R.string.tracker_link else R.string.migrate) { _, _ ->
                val data = Intent().putExtra(
                    AppRouter.KEY_MANGA,
                    ParcelableManga(target, withDescription = false),
                )
                setResult(Activity.RESULT_OK, data)
                finish()
            }
        }.show()
    }

    override fun onItemLongClick(item: MangaListModel, view: View): Boolean {
        return selectionController.onItemLongClick(view, item.id)
    }

    override fun onItemContextClick(item: MangaListModel, view: View): Boolean {
        return selectionController.onItemContextClick(view, item.id)
    }

    override fun onReadClick(manga: Manga, view: View) {
        if (!selectionController.onItemClick(manga.id)) {
            router.openReader(manga)
        }
    }

    override fun onTagClick(manga: Manga, tag: MangaTag, view: View) {
        if (!selectionController.onItemClick(manga.id)) {
            router.openList(tag)
        }
    }

    override fun onRetryClick(error: Throwable) {
        viewModel.retry()
    }

    override fun onFilterOptionClick(view: View, option: ListFilterOption) = Unit

    override fun onFilterClick(view: View?) = Unit

    override fun onEmptyActionClick() = viewModel.continueSearch()

    override fun onListHeaderClick(item: ListHeader, view: View) = Unit

    override fun onFooterButtonClick() = viewModel.continueSearch()

    override fun onPrimaryButtonClick(tipView: TipView) = Unit

    override fun onSecondaryButtonClick(tipView: TipView) = Unit

    override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
        viewBinding.recyclerView.invalidateNestedItemDecorations()
    }

    override fun onCreateActionMode(
        controller: ListSelectionController,
        menuInflater: MenuInflater,
        menu: Menu
    ): Boolean {
        menuInflater.inflate(R.menu.mode_remote, menu)
        if (settings.useCombinedTrackerSearch && viewModel.trackerAccount != null)
            menu.add(Menu.NONE, R.id.action_tracker_search_match, Menu.NONE, R.string.tracker_search_link)
        return true
    }

    override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_tracker_search_match -> {
                val manga = collectSelectedItems().singleOrNull()
                val account = viewModel.trackerAccount
                if (manga != null && account != null) {
                    startActivity(Intent(this, SearchActivity::class.java)
                        .putExtra(AppRouter.KEY_QUERY, manga.title)
                        .putExtra(org.koitharu.kotatsu.search.domain.SearchRequest.EXTRA,
                            viewModel.scopedRequest().copy(query = manga.title, kind = SearchKind.SIMPLE, trackerTarget = null).encode())
                        .putExtra(matchSourceKey, ParcelableManga(manga, withDescription = false)))
                    mode?.finish()
                }
                true
            }
            R.id.action_share -> {
                ShareHelper(this).shareMangaLinks(collectSelectedItems())
                mode?.finish()
                true
            }

            R.id.action_favourite -> {
                router.showFavoriteDialog(collectSelectedItems())
                mode?.finish()
                true
            }

            R.id.action_save -> {
                router.showDownloadDialog(collectSelectedItems(), viewBinding.recyclerView)
                mode?.finish()
                true
            }

            else -> false
        }
    }

    private fun updateSearchChips() {
        viewBinding.chipReadingSource.text = viewModel.sourceScope.label(this, viewModel.availableSources.value)
        viewBinding.chipTracker.isVisible = settings.useCombinedTrackerSearch && viewModel.scopedRequest().trackerTarget == null
        viewBinding.chipTracker.text = viewModel.trackerAccount?.provider ?: getString(R.string.tracker_search_provider)
    }

    private fun collectSelectedItems(): Set<Manga> {
        return viewModel.getItems(selectionController.peekCheckedIds())
    }

    private inner class PickModeSearchMenuProvider :
        MenuProvider,
        SearchView.OnQueryTextListener {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.opt_search_pick, menu)
            val menuItem = menu.findItem(R.id.action_search)
            val searchView = menuItem.actionView as SearchView
            searchView.queryHint = getString(R.string.search_manga)
            searchView.setIconifiedByDefault(false)
            searchView.maxWidth = Int.MAX_VALUE
            searchView.setOnQueryTextListener(this)
            searchView.setQuery(viewModel.query, false)
            searchView.clearFocus()
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

        override fun onQueryTextSubmit(query: String?): Boolean {
            val q = query?.trim().orEmpty()
            if (q.isNotEmpty()) {
                viewModel.search(q)
                this@SearchActivity.title = q
            }
            return true
        }

        override fun onQueryTextChange(newText: String?): Boolean = false
    }
}
