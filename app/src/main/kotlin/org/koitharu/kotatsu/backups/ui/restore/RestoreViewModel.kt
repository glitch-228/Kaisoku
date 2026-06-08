package org.koitharu.kotatsu.backups.ui.restore

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.data.model.BackupIndex
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.backups.domain.SourceRemap
import org.koitharu.kotatsu.backups.domain.SourceRemapPreference
import org.koitharu.kotatsu.backups.domain.SourceRemapResolver
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Date
import java.util.EnumMap
import java.util.EnumSet
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	@ApplicationContext context: Context,
	private val backupRepository: BackupRepository,
	private val sourceRemapResolver: SourceRemapResolver,
	private val settings: AppSettings,
) : BaseViewModel() {

	val uri = savedStateHandle.get<String>(AppRouter.KEY_FILE)?.toUriOrNull()
	private val appContext = context
	private val contentResolver = context.contentResolver

	val availableEntries = MutableStateFlow<List<BackupSectionModel>>(emptyList())
	val backupDate = MutableStateFlow<Date?>(null)
	val isMergeEnabled = MutableStateFlow(false)
	val sourcePreference = MutableStateFlow(settings.backupRestoreSourcePreference)
	val hasAmbiguousSources = MutableStateFlow(false)
	val sourceRemapItems = MutableStateFlow<List<RestoreRemapItem>>(emptyList())
	val perTitlePrompt = MutableEventFlow<PerTitlePrompt>()
	private var remapPlan: Map<String, SourceRemapResolver.SourceCandidates> = emptyMap()
	private val perSourceOverrides = LinkedHashMap<String, String>()
	private val perMangaOverrides = LinkedHashMap<String, String>()

	fun onMergeToggle(isChecked: Boolean) {
		isMergeEnabled.value = isChecked
	}

	fun onSourcePreferenceChange(preference: SourceRemapPreference) {
		sourcePreference.value = preference
		settings.backupRestoreSourcePreference = preference
		perSourceOverrides.clear()
		rebuildRemapItems()
	}

	fun onSourceTargetSelected(source: String, targetName: String) {
		perSourceOverrides[source] = targetName
		rebuildRemapItems()
	}

	fun onMangaTargetSelected(source: String, url: String, targetName: String) {
		val key = SourceRemap.key(source, url)
		if (targetName == source) perMangaOverrides.remove(key) else perMangaOverrides[key] = targetName
		rebuildRemapItems()
	}

	fun openPerTitle(item: RestoreRemapItem) {
		val u = uri ?: return
		launchJob(Dispatchers.Default) {
			val refs = withContext(Dispatchers.IO) {
				ZipInputStream(contentResolver.openInputStream(u)).use { stream ->
					backupRepository.collectMangaForSource(stream, item.source)
				}
			}
			val perSourceTarget = perSourceOverrides[item.source]
				?: sourceRemapResolver.pickByPreference(remapPlan[item.source] ?: return@launchJob, sourcePreference.value)
					?.name?.takeIf { sourcePreference.value != SourceRemapPreference.KEEP }
				?: item.source
			val titles = refs.map { ref ->
				PerTitlePrompt.TitleChoice(
					url = ref.url,
					title = ref.title,
					currentTargetName = perMangaOverrides[SourceRemap.key(item.source, ref.url)] ?: perSourceTarget,
				)
			}
			perTitlePrompt.call(PerTitlePrompt(item.source, item.title, item.options, titles))
		}
	}

	fun buildSourceRemap(): SourceRemap {
		val base = sourceRemapResolver.defaultRemap(remapPlan, sourcePreference.value).perSource.toMutableMap()
		// When the original extension isn't installed, the built-in is the only working target, so
		// default to it (the prompt still lets the user choose "As saved"). Sources that are
		// available in two installed places keep the "As saved" default — no presumptuous remap.
		for (group in remapPlan.values) {
			if (group.needsResolution && group.original !in perSourceOverrides) {
				builtinOf(group)?.let { base[group.original] = it }
			}
		}
		for ((src, target) in perSourceOverrides) {
			if (target == src) base.remove(src) else base[src] = target
		}
		return if (base.isEmpty() && perMangaOverrides.isEmpty()) {
			SourceRemap.IDENTITY
		} else {
			SourceRemap(perSource = base, perManga = perMangaOverrides.toMap())
		}
	}

	private fun builtinOf(group: SourceRemapResolver.SourceCandidates): String? =
		group.candidates.firstOrNull { it is MangaParserSource }?.name

	private fun rebuildRemapItems() {
		val preference = sourcePreference.value
		val keepLabel = appContext.getString(R.string.backup_source_pref_keep)
		val builtinWord = appContext.getString(R.string.backup_source_pref_builtin)
		val extensionWord = appContext.getString(R.string.backup_source_pref_extension)
		sourceRemapItems.value = remapPlan.values.filter { it.isAmbiguous || it.needsResolution }.map { group ->
			val options = buildList {
				add(RestoreRemapItem.Option(group.original, keepLabel))
				group.candidates.forEach { candidate ->
					val hint = if (candidate is MangaParserSource) builtinWord else extensionWord
					add(RestoreRemapItem.Option(candidate.name, "${candidate.getTitle(appContext)} ($hint)"))
				}
			}.distinctBy { it.targetName }
			val selected = perSourceOverrides[group.original]
				?: group.takeIf { it.needsResolution }?.let { builtinOf(it) }
				?: sourceRemapResolver.pickByPreference(group, preference)?.name?.takeIf { preference != SourceRemapPreference.KEEP }
				?: group.original
			RestoreRemapItem(
				source = group.original,
				title = MangaSource(group.original).getTitle(appContext),
				options = options,
				selectedTargetName = selected,
				customTitleCount = perMangaOverrides.keys.count { it.substringBefore(' ') == group.original },
				hint = if (group.needsResolution) appContext.getString(R.string.backup_source_extension_missing) else null,
			)
		}
	}

	init {
		launchLoadingJob(Dispatchers.Default) {
			loadBackupInfo()
			runCatchingCancellable { loadRemapPlan() }.onFailure { it.printStackTraceDebug() }
		}
	}

	private suspend fun loadRemapPlan() {
		val u = uri ?: return
		val samples = withContext(Dispatchers.IO) {
			ZipInputStream(contentResolver.openInputStream(u)).use { stream ->
				backupRepository.collectSourceSamples(stream)
			}
		}
		val plan = sourceRemapResolver.buildPlan(samples)
		remapPlan = plan
		hasAmbiguousSources.value = plan.values.any { it.isAmbiguous || it.needsResolution }
		rebuildRemapItems()
	}

	private suspend fun loadBackupInfo() {
		val sections = runInterruptible(Dispatchers.IO) {
			if (uri == null) throw FileNotFoundException()
			ZipInputStream(contentResolver.openInputStream(uri)).use { stream ->
				val result = EnumSet.noneOf(BackupSection::class.java)
				var entry = stream.nextEntry
				while (entry != null) {
					val s = BackupSection.of(entry)
					if (s != null) {
						result.add(s)
						if (s == BackupSection.INDEX) {
							backupDate.value = stream.readDate()
						}
					}
					stream.closeEntry()
					entry = stream.nextEntry
				}
				result
			}
		}
		availableEntries.value = BackupSection.entries.mapNotNull { entry ->
			if (entry == BackupSection.INDEX || entry !in sections) {
				return@mapNotNull null
			}
			BackupSectionModel(
				section = entry,
				isChecked = true,
				isEnabled = true,
			)
		}
	}

	fun onItemClick(item: BackupSectionModel) {
		val map = availableEntries.value.associateByTo(EnumMap(BackupSection::class.java)) { it.section }
		map[item.section] = item.copy(isChecked = !item.isChecked)
		map.validate()
		availableEntries.value = map.values.sortedBy { it.section.ordinal }
	}

	fun getCheckedSections(): Set<BackupSection> = availableEntries.value
		.mapNotNullTo(EnumSet.noneOf(BackupSection::class.java)) {
			if (it.isChecked) it.section else null
		}

	/**
	 * Check for inconsistent user selection
	 * Favorites cannot be restored without categories
	 */
	private fun MutableMap<BackupSection, BackupSectionModel>.validate() {
		val favorites = this[BackupSection.FAVOURITES] ?: return
		val categories = this[BackupSection.CATEGORIES]
		if (categories?.isChecked == true) {
			if (!favorites.isEnabled) {
				this[BackupSection.FAVOURITES] = favorites.copy(isEnabled = true)
			}
		} else {
			if (favorites.isEnabled) {
				this[BackupSection.FAVOURITES] = favorites.copy(isEnabled = false, isChecked = false)
			}
		}
	}

	private fun InputStream.readDate(): Date? = runCatching {
		val index = Json.decodeFromStream<List<BackupIndex>>(this)
		Date(index.single().createdAt)
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrNull()
}
