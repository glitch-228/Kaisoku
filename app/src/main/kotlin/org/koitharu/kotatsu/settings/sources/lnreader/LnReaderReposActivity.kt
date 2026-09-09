package org.koitharu.kotatsu.settings.sources.lnreader

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
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
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.dialog.setEditText
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.settings.utils.validation.UrlValidator

@AndroidEntryPoint
class LnReaderReposActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	OnListItemClickListener<LnReaderRepoListItem.Repo>,
	AppBarOwner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<LnReaderReposViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		title = getString(R.string.lnreader_repositories)
		val adapter = LnReaderReposAdapter(this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			this.adapter = adapter
		}
		viewBinding.scrollViewChips.isVisible = false
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		viewModel.content.observe(this, adapter)
		viewModel.onMessage.observeEvent(this, ::showMessage)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		addMenuProvider(object : MenuProvider {
			override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
				menuInflater.inflate(R.menu.opt_lnreader_repos, menu)
			}

			override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
				return when (menuItem.itemId) {
					R.id.action_add -> {
						showAddRepoDialog()
						true
					}

					else -> false
				}
			}
		})
	}

	override fun onItemClick(item: LnReaderRepoListItem.Repo, view: View) {
		router.openLnReaderPlugins(item.repo.url, item.repo.label)
	}

	override fun onItemLongClick(item: LnReaderRepoListItem.Repo, view: View): Boolean {
		buildAlertDialog(this, isCentered = true) {
			setTitle(R.string.remove)
			setMessage(getString(R.string.remove_extension_repo_confirm, item.repo.label))
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.remove) { _, _ ->
				viewModel.removeRepo(item.repo)
			}
		}.show()
		return true
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

	private fun showAddRepoDialog() {
		var dialog: androidx.appcompat.app.AlertDialog? = null
		var input: android.widget.EditText? = null
		val alertDialog = buildAlertDialog(this) {
			setTitle(R.string.add_extension_repo)
			input = setEditText(
				InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
				true,
			)
			input?.hint = getString(R.string.lnreader_repo_url_hint)
			input?.let { UrlValidator().attachToEditText(it) }
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(android.R.string.ok, null)
		}
		dialog = alertDialog
		alertDialog.setOnShowListener {
			dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
				val editText = input ?: return@setOnClickListener
				val value = editText.text?.toString().orEmpty().trim()
				if (value.isEmpty()) {
					editText.error = getString(R.string.invalid_server_address_message)
					return@setOnClickListener
				}
				viewModel.addRepo(value)
				dialog?.dismiss()
			}
		}
		alertDialog.show()
	}

	private fun showMessage(message: String) {
		Snackbar.make(viewBinding.recyclerView, message, Snackbar.LENGTH_SHORT).show()
	}
}
