package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.github.VersionId
import org.koitharu.kotatsu.core.github.isStable
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.KeepAndroidOpenCampaign
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent

@AndroidEntryPoint
class AboutSettingsFragment : BasePreferenceFragment(R.string.about) {

	private val viewModel by viewModels<AboutSettingsViewModel>()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_about)
		findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.run {
			title = getString(R.string.app_version, BuildConfig.VERSION_NAME)
		}
		findPreference<SwitchPreferenceCompat>(AppSettings.KEY_UPDATES_UNSTABLE)?.run {
			isEnabled = VersionId(BuildConfig.VERSION_NAME).isStable
			if (!isEnabled) isChecked = true
		}
		findPreference<Preference>(AppSettings.KEY_LINK_DISCORD)?.isEnabled = getString(R.string.url_discord).isHttpUrl()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		combine(viewModel.isUpdateSupported, viewModel.isLoading, ::Pair)
			.observe(viewLifecycleOwner) { (isUpdateSupported, isLoading) ->
				findPreference<Preference>(AppSettings.KEY_UPDATES_UNSTABLE)?.isVisible = isUpdateSupported
				findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.isEnabled = isUpdateSupported && !isLoading

			}
		viewModel.onUpdateAvailable.observeEvent(viewLifecycleOwner, ::onUpdateAvailable)
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_APP_VERSION -> {
				viewModel.checkForUpdates()
				true
			}

			AppSettings.KEY_LINK_WEBLATE -> {
				openLink(R.string.url_weblate, preference.title)
				true
			}

			AppSettings.KEY_LINK_GITHUB -> {
				openLink(R.string.url_github, preference.title)
				true
			}

			AppSettings.KEY_LINK_MANUAL -> {
				openLink(R.string.url_user_manual, preference.title)
				true
			}

			AppSettings.KEY_LINK_DISCORD -> {
				if (getString(R.string.url_discord).isHttpUrl()) {
					openLink(R.string.url_discord, preference.title)
				}
				true
			}

			"keep_android_open" -> {
				val url = KeepAndroidOpenCampaign.url(resources.configuration.locales[0].language)
				if (!router.openExternalBrowser(url, preference.title)) {
					Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
				}
				true
			}

			AppSettings.KEY_DONATION_TON,
			AppSettings.KEY_DONATION_ETH,
			AppSettings.KEY_DONATION_SOL,
			AppSettings.KEY_DONATION_BTC,
			-> {
				val address = when (preference.key) {
					AppSettings.KEY_DONATION_TON -> getString(R.string.wallet_ton)
					AppSettings.KEY_DONATION_ETH -> getString(R.string.wallet_eth)
					AppSettings.KEY_DONATION_SOL -> getString(R.string.wallet_sol)
					else -> getString(R.string.wallet_btc)
				}
				requireContext().copyToClipboard(preference.title.toString(), address)
				Snackbar.make(listView, R.string.donation_copied, Snackbar.LENGTH_SHORT).show()
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(listView, R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}

	private fun openLink(
		@StringRes url: Int,
		title: CharSequence?
	): Boolean = if (router.openExternalBrowser(getString(url), title)) {
		true
	} else {
		Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		false
	}
}
