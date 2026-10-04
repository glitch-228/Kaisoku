package org.koitharu.kotatsu.settings.discord

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordAuthActivity
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordOauthActivity

private const val PRESENCE_SCOPE = "sdk.social_layer_presence"

@AndroidEntryPoint
class DiscordSettingsFragment : BasePreferenceFragment(R.string.discord) {

	private val viewModel by viewModels<DiscordSettingsViewModel>()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_discord)
		findPreference<EditTextPreference>(AppSettings.KEY_DISCORD_TOKEN)?.let { pref ->
			pref.dialogMessage = pref.context.getString(
				R.string.discord_token_description,
				pref.context.getString(R.string.sign_in),
			)
			pref.setOnBindEditTextListener {
				it.setHint(R.string.discord_token_hint)
				it.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
			}
		}
		findPreference<Preference>(AppSettings.KEY_DISCORD_OAUTH_SIGNIN)?.setOnPreferenceClickListener {
			startActivity(Intent(context, DiscordOauthActivity::class.java))
			true
		}
		findPreference<ListPreference>(AppSettings.KEY_DISCORD_RPC_STATUS)?.let { pref ->
			// Show the effective status (online unless changed) instead of an empty choice.
			// Not a defaultValue in XML, so a status migrated from the old invisible switch is kept.
			if (pref.value == null) {
				pref.value = settings.discordRpcStatus
			}
		}
		findPreference<SwitchPreferenceCompat>(AppSettings.KEY_DISCORD_RPC_OAUTH)?.let { pref ->
			updateAuthMethodVisibility(pref.isChecked)
			pref.setOnPreferenceChangeListener { _, newValue ->
				updateAuthMethodVisibility(newValue as Boolean)
				true
			}
		}
	}

	private fun updateAuthMethodVisibility(isOauth: Boolean) {
		findPreference<EditTextPreference>(AppSettings.KEY_DISCORD_TOKEN)?.isVisible = !isOauth
		findPreference<Preference>(AppSettings.KEY_DISCORD_OAUTH_SIGNIN)?.isVisible = isOauth
		findPreference<Preference>(AppSettings.KEY_DISCORD_OAUTH_BROWSER)?.isVisible = isOauth
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.tokenState.observe(viewLifecycleOwner) { (state, token) ->
			bindTokenPreference(state, token)
		}
	}

	override fun onDisplayPreferenceDialog(preference: Preference) {
		if (preference is EditTextPreference && preference.key == AppSettings.KEY_DISCORD_TOKEN) {
			if (parentFragmentManager.findFragmentByTag(TokenDialogFragment.DIALOG_FRAGMENT_TAG) != null) {
				return
			}
			val f = TokenDialogFragment.newInstance(preference.key)
			@Suppress("DEPRECATION")
			f.setTargetFragment(this, 0)
			f.show(parentFragmentManager, TokenDialogFragment.DIALOG_FRAGMENT_TAG)
			return
		}
		super.onDisplayPreferenceDialog(preference)
	}

	private fun bindOauthState(state: TokenState, label: String?) {
		val pref = findPreference<Preference>(AppSettings.KEY_DISCORD_OAUTH_SIGNIN) ?: return
		when (state) {
			TokenState.VALID -> {
				val hasPresence = settings.discordScopes.orEmpty().contains(PRESENCE_SCOPE)
				if (hasPresence) {
					pref.icon = null
					pref.summary = getString(R.string.discord_signed_in_as, label.orEmpty())
				} else {
					pref.icon = getWarningIcon()
					pref.summary = getString(R.string.discord_signed_in_no_presence, label.orEmpty())
				}
			}

			TokenState.CHECKING -> {
				pref.icon = null
				pref.setSummary(R.string.loading_)
			}

			TokenState.INVALID -> {
				pref.icon = getWarningIcon()
				pref.setSummary(R.string.discord_oauth_reconnect)
			}

			TokenState.REQUIRED, TokenState.EMPTY -> {
				pref.icon = null
				pref.setSummary(R.string.discord_rpc_oauth_summary)
			}
		}
	}

	private fun bindTokenPreference(state: TokenState, token: String?) {
		if (settings.isDiscordRpcOauth) {
			bindOauthState(state, token)
			return
		}
		val pref = findPreference<EditTextPreference>(AppSettings.KEY_DISCORD_TOKEN) ?: return
		when (state) {
			TokenState.EMPTY -> {
				pref.icon = null
				pref.setSummary(R.string.discord_token_summary)
			}

			TokenState.REQUIRED -> {
				pref.icon = getWarningIcon()
				pref.setSummary(R.string.discord_token_summary)
			}

			TokenState.INVALID -> {
				pref.icon = getWarningIcon()
				pref.summary = getString(R.string.invalid_token, token)
			}

			TokenState.VALID -> {
				pref.icon = null
				pref.summary = token
			}

			TokenState.CHECKING -> {
				pref.icon = null
				pref.setSummary(R.string.loading_)
			}
		}
	}

	class TokenDialogFragment : EditTextPreferenceDialogFragmentCompat() {

		override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
			super.onPrepareDialogBuilder(builder)
			builder.setNeutralButton(R.string.sign_in) { _, _ ->
				openSignIn()
			}
		}

		private fun openSignIn() {
			activity?.run {
				startActivity(Intent(this, DiscordAuthActivity::class.java))
			}
		}

		companion object {

			const val DIALOG_FRAGMENT_TAG: String = "androidx.preference.PreferenceFragment.DIALOG"

			fun newInstance(key: String) = TokenDialogFragment().withArgs(1) {
				putString(ARG_KEY, key)
			}
		}
	}
}
