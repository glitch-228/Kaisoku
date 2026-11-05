package org.koitharu.kotatsu.top.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.view.ActionMode
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.top.domain.TopFilter
import org.koitharu.kotatsu.top.domain.TopRanking
import org.koitharu.kotatsu.top.domain.TopWindow

/** The most popular titles of the day, week or month on the reader's own sources. Off the main tabs until chosen. */
@AndroidEntryPoint
class TopFragment : MangaListFragment() {

    override val viewModel by viewModels<TopViewModel>()
    override val isSwipeRefreshEnabled = true

    override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        addMenuProvider(TopMenuProvider())
    }

    override fun onScrolledToEnd() = Unit

    override fun onCreateActionMode(
        controller: ListSelectionController,
        menuInflater: MenuInflater,
        menu: Menu,
    ): Boolean {
        menuInflater.inflate(R.menu.mode_top, menu)
        return super.onCreateActionMode(controller, menuInflater, menu)
    }

    override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
        if (item.itemId == R.id.action_not_interested) {
            viewModel.hide(selectedItems)
            mode?.finish()
            return true
        }
        return super.onActionItemClicked(controller, mode, item)
    }

    private fun showWindowDialog() {
        val windows = TopWindow.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.top_period)
            .setSingleChoiceItems(
                windows.map { getString(it.titleRes) }.toTypedArray(),
                windows.indexOf(viewModel.window.value),
            ) { dialog, index ->
                viewModel.setWindow(windows[index])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFilterDialog() {
        val current = viewModel.filter.value
        val types = viewModel.contentTypes
        val typeNames = types.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.top_filter_type)
            .setSingleChoiceItems(
                (listOf(getString(R.string.top_filter_any)) + typeNames).toTypedArray(),
                types.indexOf(current.type) + 1,
            ) { dialog, index ->
                dialog.dismiss()
                showGenreDialog(current.copy(type = types.getOrNull(index - 1)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGenreDialog(base: TopFilter) {
        val genres = viewModel.genres
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.top_filter_genre)
            .setSingleChoiceItems(
                (listOf(getString(R.string.top_filter_any)) + genres.map(TopRanking::genreTitle)).toTypedArray(),
                genres.indexOfFirst { it.equals(base.genre, ignoreCase = true) } + 1,
            ) { dialog, index ->
                viewModel.setFilter(base.copy(genre = genres.getOrNull(index - 1)))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class TopMenuProvider : MenuProvider {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.opt_top, menu)
        }

        override fun onPrepareMenu(menu: Menu) {
            menu.findItem(R.id.action_top_reset_hidden)?.isEnabled = viewModel.hiddenCount > 0
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
            R.id.action_top_period -> {
                showWindowDialog()
                true
            }

            R.id.action_top_filter -> {
                showFilterDialog()
                true
            }

            R.id.action_update -> {
                onRefresh()
                true
            }

            R.id.action_top_reset_hidden -> {
                viewModel.clearHidden()
                true
            }

            else -> false
        }
    }
}
