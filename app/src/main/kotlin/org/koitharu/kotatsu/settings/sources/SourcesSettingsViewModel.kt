package org.koitharu.kotatsu.settings.sources

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import javax.inject.Inject

@HiltViewModel
class SourcesSettingsViewModel @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val database: MangaDatabase,
	@ApplicationContext private val context: Context,
	private val extensionManager: MihonExtensionManager,
) : BaseViewModel() {

	val onBrokenSourcesLoaded = MutableEventFlow<List<BrokenSourceItem>>()
	val onRepairIdsLoaded = MutableEventFlow<LongArray>()
	val onExtensionErrorsLoaded = MutableEventFlow<List<String>>()

	private val linksHandlerActivity = ComponentName(context, "org.koitharu.kotatsu.details.ui.DetailsByLinkActivity")

	val enabledSourcesCount = sourcesRepository.observeEnabledSourcesCount()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, -1)

	val availableSourcesCount = sourcesRepository.observeAvailableSourcesCount()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, -1)

	val isLinksEnabled = MutableStateFlow(isLinksEnabled())

	fun setLinksEnabled(isEnabled: Boolean) {
		context.packageManager.setComponentEnabledSetting(
			linksHandlerActivity,
			if (isEnabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED,
			PackageManager.DONT_KILL_APP,
		)
		isLinksEnabled.value = isLinksEnabled()
	}

	fun refreshInstalledSources() {
		launchLoadingJob(Dispatchers.IO) {
			sourcesRepository.refreshInstalledMihonSources()
		}
	}

	fun loadExtensionErrors() {
		launchLoadingJob(Dispatchers.IO) {
			sourcesRepository.refreshInstalledMihonSources()
			onExtensionErrorsLoaded.call(extensionManager.getLoadFailures())
		}
	}

	fun loadBrokenSources() {
		launchLoadingJob(Dispatchers.Default) {
			val available = sourcesRepository.getParserSourcesSnapshot().associateBy { it.source.name }
			val items = database.getMangaDao().findLibrarySourceUsage().mapNotNull { usage ->
				val current = available[usage.source]
				if (current != null && !current.isBroken) return@mapNotNull null
				BrokenSourceItem(
					source = usage.source,
					title = current?.title ?: usage.source,
					mangaCount = usage.mangaCount,
					isUnavailable = current == null,
				)
			}
			onBrokenSourcesLoaded.call(items)
		}
	}

	fun loadRepairIds(sources: Set<String>) {
		if (sources.isEmpty()) return
		launchLoadingJob(Dispatchers.IO) {
			onRepairIdsLoaded.call(
				database.getMangaDao().findLibraryMangaIdsBySources(sources).toLongArray(),
			)
		}
	}

	private fun isLinksEnabled(): Boolean {
		val state = context.packageManager.getComponentEnabledSetting(linksHandlerActivity)
		return state == COMPONENT_ENABLED_STATE_ENABLED || state == COMPONENT_ENABLED_STATE_DEFAULT
	}

	data class BrokenSourceItem(
		val source: String,
		val title: String,
		val mangaCount: Int,
		val isUnavailable: Boolean,
	)
}
