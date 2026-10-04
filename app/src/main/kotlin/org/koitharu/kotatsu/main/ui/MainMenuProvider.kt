package org.koitharu.kotatsu.main.ui

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog

class MainMenuProvider(
	private val context: Context,
	private val settings: AppSettings,
	private val router: AppRouter,
	private val viewModel: MainViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_main, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_incognito)?.isChecked =
			viewModel.isIncognitoModeEnabled.value
		menu.findItem(R.id.action_discord_status)?.isVisible = settings.isDiscordRpcEnabled
		val hasAppUpdate = viewModel.appUpdate.value != null
		menu.findItem(R.id.action_app_update)?.isVisible = hasAppUpdate
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_settings -> {
			router.openSettings()
			true
		}

		R.id.action_incognito -> {
			viewModel.setIncognitoMode(!menuItem.isChecked)
			true
		}

		R.id.action_discord_status -> {
			showDiscordStatusDialog()
			true
		}

		R.id.action_app_update -> {
			router.openAppUpdate()
			true
		}

		R.id.action_downloads -> {
			router.openDownloads()
			true
		}

		else -> false
	}

	private fun showDiscordStatusDialog() {
		val values = context.resources.getStringArray(R.array.discord_rpc_status_values)
		val entries = context.resources.getStringArray(R.array.discord_rpc_status_entries)
		buildAlertDialog(context, isCentered = true) {
			setTitle(R.string.discord_rpc_status)
			setSingleChoiceItems(entries, values.indexOf(settings.discordRpcStatus)) { dialog, which ->
				settings.discordRpcStatus = values[which]
				dialog.dismiss()
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}
}
