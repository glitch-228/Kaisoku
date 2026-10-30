package org.koitharu.kotatsu.details.ui.related

import android.view.Menu
import android.view.MenuInflater
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.list.ui.MangaListFragment

@AndroidEntryPoint
class RelatedListFragment : MangaListFragment() {

	override val viewModel by viewModels<RelatedListViewModel>()
	override val isSwipeRefreshEnabled get() = !settings.useLegacySuggestions

    override fun onViewBindingCreated(binding: org.koitharu.kotatsu.databinding.FragmentListBinding, state: android.os.Bundle?) {
        super.onViewBindingCreated(binding, state)
        settings.observe(AppSettings.KEY_SUGGESTIONS_TITLE_ONLY, AppSettings.KEY_SUGGESTIONS_LEGACY)
            .observe(viewLifecycleOwner) { updateSuggestionTitle() }
        addMenuProvider(object : androidx.core.view.MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                menu.add(Menu.NONE, R.id.action_recommendation_provider_match, Menu.NONE, R.string.recommendation_public_match)
            }
            override fun onPrepareMenu(menu: Menu) {
                menu.findItem(R.id.action_recommendation_provider_match)?.isVisible = settings.publicRecommendations && !settings.useLegacySuggestions
            }
            override fun onMenuItemSelected(item: android.view.MenuItem): Boolean {
                if (item.itemId != R.id.action_recommendation_provider_match) return false
                recommendationUi.confirmProvider(viewModel.seed, binding.root, viewLifecycleOwner.lifecycleScope) { viewModel.onRefresh() }
                return true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateSuggestionTitle()
    }

    private fun updateSuggestionTitle() {
        requireActivity().setTitle(if (!settings.useLegacySuggestions && settings.useTitleOnlySuggestions)
            R.string.recommendations_similar_beta else R.string.related_manga)
    }

	override fun onScrolledToEnd() = Unit

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_remote, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}
}

