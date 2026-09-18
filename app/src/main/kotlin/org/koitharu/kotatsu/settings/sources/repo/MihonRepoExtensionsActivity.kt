package org.koitharu.kotatsu.settings.sources.repo

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner

@AndroidEntryPoint
class MihonRepoExtensionsActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	OnListItemClickListener<MihonRepoExtensionListItem.Extension>,
	AppBarOwner,
	MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<MihonRepoExtensionsViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.title = intent.getStringExtra(org.koitharu.kotatsu.core.nav.AppRouter.KEY_TITLE)
			?: getString(R.string.extensions)
		val adapter = MihonRepoExtensionsAdapter(this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			this.adapter = adapter
		}
		viewBinding.scrollViewChips.isVisible = false
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		viewModel.content.observe(this, adapter)
		viewModel.availableLanguages.observe(this) { invalidateOptionsMenu() }
		viewModel.screenTitle.observe(this) {
			supportActionBar?.title = it
		}
		viewModel.onMessage.observeEvent(this, ::showMessage)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		addMenuProvider(object : MenuProvider {
			override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
				menuInflater.inflate(R.menu.opt_sources_catalog, menu)
				menu.add(Menu.NONE, R.id.action_languages, Menu.NONE, R.string.languages)
				val searchMenuItem = menu.findItem(R.id.action_search)
				searchMenuItem.setOnActionExpandListener(this@MihonRepoExtensionsActivity)
				val searchView = searchMenuItem.actionView as SearchView
				searchView.setOnQueryTextListener(this@MihonRepoExtensionsActivity)
				searchView.setIconifiedByDefault(false)
				searchView.queryHint = searchMenuItem.title
			}

			override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
				return when (menuItem.itemId) {
					R.id.action_languages -> {
						viewModel.languageFilter.showDialog(this@MihonRepoExtensionsActivity, viewModel.availableLanguages.value)
						true
					}

					R.id.action_refresh -> {
						viewModel.refresh()
						true
					}

					else -> false
				}
			}
		})
	}

	override fun onItemClick(item: MihonRepoExtensionListItem.Extension, view: View) {
		viewModel.onExtensionClick(item.descriptor)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		viewModel.performSearch((item.actionView as? SearchView)?.query?.trim()?.toString())
		appBar.setExpanded(true, true)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		(item.actionView as? SearchView)?.setQuery("", false)
		viewModel.performSearch(null)
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.performSearch(newText)
		return true
	}

	private fun showMessage(message: String) {
		Snackbar.make(viewBinding.recyclerView, message, Snackbar.LENGTH_SHORT).show()
	}
}
