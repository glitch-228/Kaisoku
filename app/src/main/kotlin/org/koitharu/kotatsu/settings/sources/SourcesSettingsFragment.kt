package org.koitharu.kotatsu.settings.sources

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.parsers.util.names

@AndroidEntryPoint
class SourcesSettingsFragment : BasePreferenceFragment(R.string.remote_sources),
	SharedPreferences.OnSharedPreferenceChangeListener {

	private val viewModel by viewModels<SourcesSettingsViewModel>()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_sources)
		findPreference<ListPreference>(AppSettings.KEY_SOURCES_ORDER)?.run {
			entryValues = SourcesSortOrder.entries.names()
			entries = SourcesSortOrder.entries.map { context.getString(it.titleResId) }.toTypedArray()
			setDefaultValueCompat(SourcesSortOrder.MANUAL.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_DEFAULT_BROWSE_SORT)?.setDefaultValueCompat(
			AppSettings.BROWSE_SORT_AUTOMATIC,
		)
        findPreference<ListPreference>(AppSettings.KEY_INCOGNITO_NSFW)?.run {
            entryValues = TriStateOption.entries.names()
            setDefaultValueCompat(TriStateOption.ASK.name)
        }
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		findPreference<Preference>(AppSettings.KEY_REMOTE_SOURCES)?.let { pref ->
			viewModel.enabledSourcesCount.observe(viewLifecycleOwner) {
				pref.summary = if (it >= 0) {
					resources.getQuantityStringSafe(R.plurals.items, it, it)
				} else {
					null
				}
			}
		}
		findPreference<Preference>(AppSettings.KEY_SOURCES_CATALOG)?.let { pref ->
			viewModel.availableSourcesCount.observe(viewLifecycleOwner) {
				pref.summary = when {
					it == 0 -> getString(R.string.all_sources_enabled)
					it > 0 -> getString(R.string.available_d, it)
					else -> null
				}
			}
		}
		findPreference<TwoStatePreference>(AppSettings.KEY_HANDLE_LINKS)?.let { pref ->
			viewModel.isLinksEnabled.observe(viewLifecycleOwner) {
				pref.isChecked = it
			}
		}
		viewModel.onBrokenSourcesLoaded.observeEvent(viewLifecycleOwner, ::showBrokenSources)
		viewModel.onExtensionErrorsLoaded.observeEvent(viewLifecycleOwner, ::showExtensionErrors)
		viewModel.onRepairIdsLoaded.observeEvent(viewLifecycleOwner) { ids ->
			router.openSourceReplacement(ids.asList())
		}
		updateEnableAllDependencies()
		settings.subscribe(this)
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
		AppSettings.KEY_SOURCES_CATALOG -> {
			router.openSourcesCatalog()
			true
		}

		AppSettings.KEY_HANDLE_LINKS -> {
			viewModel.setLinksEnabled((preference as TwoStatePreference).isChecked)
			true
		}

		KEY_REPAIR_BROKEN_SOURCES -> {
			viewModel.loadBrokenSources()
			true
		}

		KEY_EXTENSION_ERRORS -> {
			viewModel.loadExtensionErrors()
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_SOURCES_ENABLED_ALL -> updateEnableAllDependencies()
			AppSettings.KEY_USE_ANDROID_EXTENSIONS -> viewModel.refreshInstalledSources()
		}
	}

	private fun updateEnableAllDependencies() {
		findPreference<Preference>(AppSettings.KEY_SOURCES_CATALOG)?.isEnabled = !settings.isAllSourcesEnabled
	}

	private fun showBrokenSources(items: List<SourcesSettingsViewModel.BrokenSourceItem>) {
		if (items.isEmpty()) {
			MaterialAlertDialogBuilder(requireContext())
				.setMessage(R.string.repair_broken_sources_none)
				.setPositiveButton(android.R.string.ok, null)
				.show()
			return
		}
		val checked = BooleanArray(items.size) { true }
		val labels = items.map { item ->
			getString(
				R.string.repair_broken_sources_item,
				item.title,
				item.mangaCount,
				if (item.isUnavailable) getString(R.string.repair_broken_sources_unavailable) else "",
			)
		}.toTypedArray()
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.repair_broken_sources_select)
			.setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.next) { _, _ ->
				viewModel.loadRepairIds(
					items.indices.filter { checked[it] }.mapTo(hashSetOf()) { items[it].source },
				)
			}
			.show()
	}

	private fun showExtensionErrors(errors: List<String>) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.mihon_extension_errors)
			.setMessage(errors.takeIf { it.isNotEmpty() }?.joinToString("\n\n") ?: getString(R.string.no_mihon_extension_errors))
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private companion object {
		const val KEY_REPAIR_BROKEN_SOURCES = "repair_broken_sources"
		const val KEY_EXTENSION_ERRORS = "mihon_extension_errors"
	}
}
