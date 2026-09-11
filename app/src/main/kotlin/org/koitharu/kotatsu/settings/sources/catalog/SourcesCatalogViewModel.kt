package org.koitharu.kotatsu.settings.sources.catalog

import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_SOURCES
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.mapSortedByCount
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.EnumSet
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
	private val repository: MangaSourcesRepository,
	db: MangaDatabase,
	settings: AppSettings,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()

	private val refreshTrigger = MutableStateFlow(0)
	private val searchQuery = MutableStateFlow<String?>(null)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			types = emptySet(),
			locale = null,
			isNewOnly = false,
			mihonMode = SourceCatalogFilterMode.NONE,
			pluginMode = SourceCatalogFilterMode.NONE,
		),
	)

	val hasNewSources = repository.observeHasNewSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val contentTypes = MutableStateFlow(getContentTypes(settings.isNsfwContentDisabled))

	private val sourcesSnapshot = MutableStateFlow<List<MangaSourcesRepository.ParserSourceSnapshot>?>(null)
	private val mutableContent = MutableStateFlow<List<ListModel>>(listOf(LoadingState))

	val content: StateFlow<List<ListModel>> = mutableContent

	val locales: Set<String?>
		get() = sourcesSnapshot.value
			?.mapTo(HashSet<String?>()) { source -> source.locale }
			?.also { it.add(null) }
			?: repository.allMangaSources.mapTo(HashSet<String?>()) { it.locale }.also { it.add(null) }

	init {
		repository.clearNewSourcesBadge()
		launchJob(Dispatchers.IO) {
			combine(
				db.invalidationTracker.createFlow(
					tables = arrayOf(TABLE_SOURCES),
					emitInitialState = true,
				),
				repository.observeInstalledMihonSources().onStart { emit(emptyList()) },
				repository.observeInstalledPluginSources().onStart { emit(emptyList()) },
				refreshTrigger,
			) { _, _, _, _ -> Unit 			}.mapLatest {
				runCatching {
					repository.getParserSourcesSnapshot()
				}.onFailure { error ->
					if (error is CancellationException) {
						throw error
					}
					error.printStackTraceDebug()
					errorEvent.call(error)
					if (sourcesSnapshot.value == null) {
						mutableContent.value = buildErrorHintList()
					}
				}.getOrElse { sourcesSnapshot.value }
			}.collect { snapshot ->
				if (snapshot != null) {
					sourcesSnapshot.value = snapshot
				}
			}
		}
		launchJob(Dispatchers.IO) {
			combine(
				searchQuery.debounce(SEARCH_DEBOUNCE_TIMEOUT).distinctUntilChanged(),
				appliedFilter,
				sourcesSnapshot.filterNotNull(),
			) { q, f, snapshot ->
				Triple(q, f, snapshot)
			}.conflate().mapLatest { (q, f, snapshot) ->
				runCatching {
					buildSourcesList(
						filter = f,
						query = q,
						snapshot = snapshot,
					)
				}.onFailure { error ->
					// Cancellation here only means a newer query superseded this one; surfacing it
					// as an error dialog would show "An error occurred" with no details.
					if (error is CancellationException) {
						throw error
					}
					error.printStackTraceDebug()
					errorEvent.call(error)
				}.getOrElse {
					mutableContent.value.takeUnless { list -> list == listOf(LoadingState) } ?: buildErrorHintList()
				}
			}.distinctUntilChanged().collect { list ->
				mutableContent.value = list
			}
		}
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun setLocale(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(locale = value)
	}

	fun addSource(source: MangaSource) {
		launchJob(Dispatchers.Default) {
			// Enable every source that is really the same one, not just every source sharing the
			// title: a plugin jar and a builtin source can carry the same name, and enabling both
			// would leave a duplicate entry in Explore.
			val plugin = (source as? PluginMangaSource)
				?: (source as? MangaSourceInfo)?.mangaSource as? PluginMangaSource
			val all = repository.allMangaSources.filter { s ->
				val p = (s as? PluginMangaSource) ?: (s as? MangaSourceInfo)?.mangaSource as? PluginMangaSource
				p?.jarName == plugin?.jarName &&
					s.isExternalSource() == source.isExternalSource() &&
					s.title.equals(sourceTitle(source), true)
			}.ifEmpty { listOf(source) }
			val rollback = repository.setSourcesEnabled(all, true)
			onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
		}
	}

	private fun sourceTitle(source: MangaSource): String = when (val s = source.unwrap()) {
		is PluginMangaSource -> s.title
		is MangaParserSource -> s.title
		else -> s.name
	}

	fun setContentType(value: ContentType, isAdd: Boolean) {
		val filter = appliedFilter.value
		val types = EnumSet.noneOf(ContentType::class.java)
		types.addAll(filter.types)
		if (isAdd) {
			types.add(value)
		} else {
			types.remove(value)
		}
		appliedFilter.value = filter.copy(types = types)
	}

	fun setNewOnly(value: Boolean) {
		appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
	}

	fun cycleMihonMode() {
		val filter = appliedFilter.value
		appliedFilter.value = filter.copy(mihonMode = filter.mihonMode.next())
	}

	fun cyclePluginMode() {
		val filter = appliedFilter.value
		appliedFilter.value = filter.copy(pluginMode = filter.pluginMode.next())
	}

	fun cycleNovelMode() {
		val filter = appliedFilter.value
		appliedFilter.value = filter.copy(novelMode = filter.novelMode.next())
	}

	fun refreshSources() {
		launchJob(Dispatchers.IO) {
			repository.refreshInstalledMihonSources()
			repository.refreshInstalledPluginSources()
			refreshTrigger.value += 1
		}
	}

	private suspend fun buildSourcesList(
		filter: SourcesCatalogFilter,
		query: String?,
		snapshot: List<MangaSourcesRepository.ParserSourceSnapshot>,
	): List<SourceCatalogItem> {
		val sources = repository.queryParserSources(
			isDisabledOnly = true,
			isNewOnly = filter.isNewOnly,
			excludeBroken = false,
			types = filter.types,
			query = query,
			locale = filter.locale,
			includeMihon = filter.mihonMode == SourceCatalogFilterMode.INCLUDE,
			excludeMihon = filter.mihonMode == SourceCatalogFilterMode.EXCLUDE,
			includePlugins = filter.pluginMode == SourceCatalogFilterMode.INCLUDE,
			excludePlugins = filter.pluginMode == SourceCatalogFilterMode.EXCLUDE,
			includeNovel = filter.novelMode == SourceCatalogFilterMode.INCLUDE,
			excludeNovel = filter.novelMode == SourceCatalogFilterMode.EXCLUDE,
			sortOrder = SourcesSortOrder.ALPHABETIC,
			snapshot = snapshot,
		)
		return if (sources.isEmpty()) {
			listOf(
				if (query == null) {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.no_manga_sources,
						text = R.string.no_manga_sources_catalog_text,
					)
				} else {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.nothing_found,
						text = R.string.no_manga_sources_found,
					)
				},
			)
		} else {
			sources.map {
				SourceCatalogItem.Source(source = it)
			}
		}
	}

	@WorkerThread
	private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
		val result = repository.allMangaSources.mapSortedByCount { it.contentType }
		return if (isNsfwDisabled) {
			result.filterNot { it == ContentType.HENTAI }
		} else {
			result
		}
	}

	private fun buildErrorHintList(): List<SourceCatalogItem> = listOf(
		SourceCatalogItem.Hint(
			icon = R.drawable.ic_empty_feed,
			title = R.string.error_occurred,
			text = R.string.error_details,
		),
	)

	private companion object {
		private const val SEARCH_DEBOUNCE_TIMEOUT = 180L
	}
}

private fun SourceCatalogFilterMode.next(): SourceCatalogFilterMode = when (this) {
	SourceCatalogFilterMode.NONE -> SourceCatalogFilterMode.INCLUDE
	SourceCatalogFilterMode.INCLUDE -> SourceCatalogFilterMode.EXCLUDE
	SourceCatalogFilterMode.EXCLUDE -> SourceCatalogFilterMode.NONE
}
