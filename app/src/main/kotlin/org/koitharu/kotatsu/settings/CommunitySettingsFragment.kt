package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.text.InputType
import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.community.data.CommunityRepository
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import javax.inject.Inject

@AndroidEntryPoint
class CommunitySettingsFragment : BasePreferenceFragment(R.string.community) {

	@Inject
	lateinit var community: CommunityRepository

	private lateinit var enabled: SwitchPreferenceCompat
	private lateinit var telemetry: SwitchPreferenceCompat

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_community)
		enabled = checkNotNull(findPreference("community_enabled"))
		telemetry = checkNotNull(findPreference("community_telemetry"))
		enabled.isChecked = community.isEnabled
		telemetry.isChecked = community.isTelemetryEnabled
		enabled.setOnPreferenceChangeListener { _, value ->
			community.setEnabled(value as Boolean)
			telemetry.isChecked = community.isTelemetryEnabled
			if (value) verifyIdentity()
			true
		}
		telemetry.setOnPreferenceChangeListener { _, value ->
			community.setTelemetryEnabled(value as Boolean)
			true
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		bindIdentitySummary()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
		"community_identity" -> {
			verifyIdentity()
			true
		}
		"community_nickname" -> {
			showNicknameDialog()
			true
		}
		"community_recovery" -> {
			showRecoveryDialog()
			true
		}
		"community_delete" -> {
			confirmDelete()
			true
		}
		"community_notifications" -> {
			showNotifications()
			true
		}
		"community_export" -> {
			exportData()
			true
		}
		else -> super.onPreferenceTreeClick(preference)
	}

	private fun verifyIdentity() {
		viewLifecycleScope.launch {
			try {
				withContext(Dispatchers.IO) { community.ensureIdentity() }
				bindIdentitySummary()
				Snackbar.make(listView, R.string.community_identity_ready, Snackbar.LENGTH_SHORT).show()
			} catch (error: Throwable) {
				if (error is CancellationException) throw error
				Snackbar.make(listView, getString(R.string.community_setup_failed, error.getDisplayMessage(resources)), Snackbar.LENGTH_LONG).show()
			}
		}
	}

	private fun bindIdentitySummary() {
		findPreference<Preference>("community_identity")?.summary = when {
			!community.isEnabled -> getString(R.string.disabled)
			community.hasIdentity -> getString(R.string.enabled)
			else -> getString(R.string.community_identity_summary)
		}
		viewLifecycleScope.launch {
			val identity = withContext(Dispatchers.IO) { community.identityOrNull() }
			findPreference<Preference>("community_nickname")?.summary = identity?.displayName
				?.takeIf { it.isNotBlank() }
				?: getString(R.string.community_nickname_summary)
		}
	}

	private fun showNicknameDialog() {
		if (!community.isEnabled) {
			Snackbar.make(listView, R.string.community_enabled_summary, Snackbar.LENGTH_LONG).show()
			return
		}
		val input = EditText(requireContext()).apply {
			inputType = InputType.TYPE_CLASS_TEXT
			hint = getString(R.string.community_nickname_hint)
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.community_nickname)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.save) { _, _ ->
				val nickname = input.text.toString()
				viewLifecycleScope.launch {
					try {
						val identity = withContext(Dispatchers.IO) {
							community.ensureIdentity()
							community.setNickname(nickname)
						}
						findPreference<Preference>("community_nickname")?.summary = identity.displayName
						Snackbar.make(listView, R.string.community_nickname_saved, Snackbar.LENGTH_SHORT).show()
					} catch (error: Throwable) {
						if (error is CancellationException) throw error
						Snackbar.make(listView, getString(R.string.community_setup_failed, error.getDisplayMessage(resources)), Snackbar.LENGTH_LONG).show()
					}
				}
			}
			.show()
	}

	private fun showNotifications() {
		if (!community.isEnabled) {
			Snackbar.make(listView, R.string.community_enabled_summary, Snackbar.LENGTH_LONG).show()
			return
		}
		viewLifecycleScope.launch {
			try {
				val replies = withContext(Dispatchers.IO) {
					community.ensureIdentity()
					community.getNotifications()
				}
				val message = replies.takeIf { it.isNotEmpty() }?.joinToString("\n\n") {
					"${it.author}: ${it.preview}"
				} ?: getString(R.string.community_no_notifications)
				MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.community_notifications)
					.setMessage(message)
					.setPositiveButton(android.R.string.ok, null)
					.show()
			} catch (error: Throwable) {
				if (error is CancellationException) throw error
				Snackbar.make(listView, getString(R.string.community_setup_failed, error.getDisplayMessage(resources)), Snackbar.LENGTH_LONG).show()
			}
		}
	}

	private fun exportData() {
		if (!community.isEnabled) {
			Snackbar.make(listView, R.string.community_enabled_summary, Snackbar.LENGTH_LONG).show()
			return
		}
		viewLifecycleScope.launch {
			try {
				val data = withContext(Dispatchers.IO) {
					community.ensureIdentity()
					community.exportData()
				}
				startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
					type = "application/json"
					putExtra(Intent.EXTRA_TEXT, data)
				}, getString(R.string.community_export_title)))
			} catch (error: Throwable) {
				if (error is CancellationException) throw error
				Snackbar.make(listView, getString(R.string.community_export_failed, error.getDisplayMessage(resources)), Snackbar.LENGTH_LONG).show()
			}
		}
	}

	private fun showRecoveryDialog() {
		val key = community.exportRecoveryKey()
		val input = EditText(requireContext()).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
			setText(key.orEmpty())
			setSelection(length())
			hint = getString(R.string.community_import_hint)
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.community_recovery_title)
			.setMessage(R.string.community_recovery_explanation)
			.setView(input)
			.setNeutralButton(R.string.community_copy_key) { _, _ ->
				community.exportRecoveryKey()?.let { requireContext().copyToClipboard(getString(R.string.community_recovery_title), it) }
				Snackbar.make(listView, R.string.community_key_copied, Snackbar.LENGTH_SHORT).show()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.community_import_key) { _, _ ->
				val recoveryKey = input.text.toString()
				viewLifecycleScope.launch {
					try {
						withContext(Dispatchers.IO) { community.importRecoveryKey(recoveryKey) }
						bindIdentitySummary()
						Snackbar.make(listView, R.string.community_identity_ready, Snackbar.LENGTH_SHORT).show()
					} catch (error: Throwable) {
						if (error is CancellationException) throw error
						Snackbar.make(listView, getString(R.string.community_setup_failed, error.getDisplayMessage(resources)), Snackbar.LENGTH_LONG).show()
					}
				}
			}
			.show()
	}

	private fun confirmDelete() {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.community_delete)
			.setMessage(R.string.community_delete_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.delete) { _, _ ->
				viewLifecycleScope.launch {
					try {
						withContext(Dispatchers.IO) { community.deleteIdentity() }
						community.setEnabled(false)
						enabled.isChecked = false
						telemetry.isChecked = false
						bindIdentitySummary()
						Snackbar.make(listView, R.string.community_deleted, Snackbar.LENGTH_SHORT).show()
					} catch (error: Exception) {
						if (error is CancellationException) throw error
						Snackbar.make(listView, error.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
					}
				}
			}
			.show()
	}
}
