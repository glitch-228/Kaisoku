package org.koitharu.kotatsu.favourites.ui.list

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.core.view.MenuProvider
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.list.domain.ListFilterOption
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.util.ext.sortedByOrdinal
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.MangaListFragment

@AndroidEntryPoint
class FavouritesListFragment : MangaListFragment(), PopupMenu.OnMenuItemClickListener {

    override val viewModel by viewModels<FavouritesListViewModel>()

    override val isSwipeRefreshEnabled = true

    val categoryId
        get() = viewModel.categoryId

    override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.recyclerView.isVP2BugWorkaroundEnabled = true
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                menu.add(Menu.NONE, R.id.action_favorites_filters, 30, R.string.favorites_filters)
                menu.add(Menu.NONE, R.id.action_favorites_reorder, 31, R.string.favorites_reorder)
            }
            override fun onPrepareMenu(menu: Menu) {
                val visible = (parentFragment as? org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment)
                    ?.isCategoryOverviewVisible != true
                menu.findItem(R.id.action_favorites_filters)?.isVisible = visible
                menu.findItem(R.id.action_favorites_reorder)?.isVisible = visible
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                if (item.itemId == R.id.action_favorites_reorder) { viewModel.arrangeFavorites(); return true }
                if (item.itemId != R.id.action_favorites_filters) return false
                showFilterPanel()
                return true
            }
        })
        viewModel.arrangement.observeEvent(viewLifecycleOwner) { values ->
            if (isResumed) showFavoriteOrderDialog(requireContext(), values) { viewModel.saveArrangement(it) }
        }
    }

    private fun showFilterPanel() {
        viewLifecycleScope.launch {
            val options = viewModel.filterPanelOptions()
            if (viewBinding == null || !isAdded) return@launch
            val selected = viewModel.selectedFilters().toMutableSet()
            val labels = options.map { option ->
                when {
                    option is ListFilterOption.Source -> option.mangaSource.getTitle(requireContext())
                    option.titleText != null -> option.titleText.toString()
                    option.titleResId != 0 -> getString(option.titleResId)
                    else -> option.groupKey
                }
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.favorites_filters)
                .setMultiChoiceItems(labels, options.map { it in selected }.toBooleanArray()) { dialog, index, checked ->
                    val option = options[index]
                    if (checked) {
                        selected.removeAll { candidate ->
                            (option is ListFilterOption.State && candidate is ListFilterOption.State) ||
                                (option is ListFilterOption.Inverted && candidate == option.option) ||
                                (candidate is ListFilterOption.Inverted && candidate.option == option)
                        }
                        selected.add(option)
                    } else selected.remove(option)
                    val list = (dialog as androidx.appcompat.app.AlertDialog).listView
                    options.forEachIndexed { i, value -> list.setItemChecked(i, value in selected) }
                }
                .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.applyFilters(selected) }
                .setNeutralButton(R.string.reset_filter) { _, _ -> viewModel.applyFilters(emptyList()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onScrolledToEnd() = viewModel.requestMoreItems()

    override fun onEmptyActionClick() = viewModel.clearFilter()

    override fun onFilterClick(view: View?) {
        val menu = PopupMenu(view?.context ?: return, view)
        menu.setOnMenuItemClickListener(this)
        val orders = ListSortOrder.FAVORITES.sortedByOrdinal()
        for ((i, item) in orders.withIndex()) {
            menu.menu.add(Menu.NONE, Menu.NONE, i, item.titleResId)
        }
        menu.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val order = ListSortOrder.FAVORITES.sortedByOrdinal().getOrNull(item.order) ?: return false
        viewModel.setSortOrder(order)
        return true
    }

    override fun onCreateActionMode(
        controller: ListSelectionController,
        menuInflater: MenuInflater,
        menu: Menu
    ): Boolean {
        menuInflater.inflate(R.menu.mode_favourites, menu)
        return super.onCreateActionMode(controller, menuInflater, menu)
    }

    override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_tracker_bulk -> {
                val snapshot = selectedItemsIds.toLongArray()
                if (snapshot.size !in 1..100) {
                    MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.tracker_bulk_notice)
                        .setPositiveButton(android.R.string.ok, null).show()
                } else {
                    val services = org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.entries
                    val providers = services.map { it.name } + listOf("COMICK", "ANIME_PLANET")
                    val labels = services.map { getString(it.titleResId) } + listOf("Comick", "Anime-Planet")
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_bulk_title)
                        .setItems(labels.toTypedArray()) { _, index ->
                            org.koitharu.kotatsu.scrobbling.v2.bulk.BulkTrackerSheet.show(
                                parentFragmentManager, providers[index], snapshot)
                            mode?.finish()
                        }.setNegativeButton(R.string.cancel, null).show()
                }
                true
            }
            R.id.action_remove -> {
                viewModel.removeFromFavourites(selectedItemsIds)
                mode?.finish()
                true
            }

            R.id.action_mark_current -> {
                val itemsSnapshot = selectedItems
                MaterialAlertDialogBuilder(context ?: return false)
                    .setTitle(item.title)
                    .setMessage(R.string.mark_as_completed_prompt)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.markAsRead(itemsSnapshot)
                        mode?.finish()
                    }.show()
                true
            }

            else -> super.onActionItemClicked(controller, mode, item)
        }
    }

    companion object {

        const val NO_ID = 0L

        fun newInstance(categoryId: Long) = FavouritesListFragment().withArgs(1) {
            putLong(AppRouter.KEY_ID, categoryId)
        }
    }
}
