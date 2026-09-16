package org.koitharu.kotatsu.settings.sources.lnreader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.settings.sources.ExtensionLanguageFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.lnreader.LNReaderPluginInfo
import org.koitharu.kotatsu.core.parser.lnreader.LNReaderPluginInstaller
import org.koitharu.kotatsu.core.parser.lnreader.LNReaderRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceManager
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class LnReaderPluginsViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repoRepository: LnReaderPluginReposRepository,
	private val lnReaderSourceManager: LnReaderSourceManager,
	private val mangaSourcesRepository: MangaSourcesRepository,
	private val db: MangaDatabase,
	savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

	private val repoUrl = checkNotNull(savedStateHandle.get<String>(AppRouter.KEY_URL))
	private val refreshTrigger = MutableStateFlow(0)
	private val searchQuery = MutableStateFlow<String?>(null)

	val onMessage = MutableEventFlow<String>()

	val screenTitle = MutableStateFlow(
		repoRepository.findRepo(repoUrl)?.label
			?: savedStateHandle.get<String>(AppRouter.KEY_TITLE).orEmpty().ifBlank { context.getString(R.string.lnreader_plugins) },
	)

	val languageFilter = ExtensionLanguageFilter(context, "lnreader")
	private val rawContent: StateFlow<List<ListModel>> = refreshTrigger.mapLatest {
		withLoading {
			runCatchingCancellable { loadContent(null) }.getOrElse { error ->
				errorEvent.call(error)
				listOf(LnReaderPluginListItem.Hint(R.drawable.ic_error_large, R.string.error, R.string.try_again))
			}
		}
	}.stateIn(
		viewModelScope + Dispatchers.IO, SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS), listOf(LoadingState),
	)

	val availableLanguages = rawContent.map { items ->
		items.filterIsInstance<LnReaderPluginListItem.Extension>().flatMap {
			listOf(it.descriptor.plugin.lang)
		}.map(ExtensionLanguageFilter::normalize).distinct().sorted()
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS), emptyList())

	val content: StateFlow<List<ListModel>> = combine(
		rawContent,
		searchQuery.debounce(SEARCH_DEBOUNCE_TIMEOUT).distinctUntilChanged(),
		languageFilter.selected,
	) { items, query, languages ->
		val extensions = items.filterIsInstance<LnReaderPluginListItem.Extension>()
		if (extensions.isEmpty()) items else {
			val filtered = extensions.filter {
				(query.isNullOrEmpty() || it.descriptor.plugin.matchesQuery(query)) &&
					ExtensionLanguageFilter.matches(languages, listOf(it.descriptor.plugin.lang))
			}
			filtered.ifEmpty { listOf(LnReaderPluginListItem.Hint(R.drawable.ic_empty_feed, R.string.nothing_found, R.string.no_repo_extensions_found)) }
		}
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS), listOf(LoadingState))

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()?.takeIf { it.isNotEmpty() }
	}

	fun refresh() {
		refreshTrigger.value += 1
	}

	fun onPluginClick(descriptor: LnReaderPluginDescriptor) {
		launchLoadingJob(Dispatchers.IO) {
			when {
				descriptor.hasUpdate || !descriptor.isInstalled -> {
					val result = lnReaderRepository().installPlugin(descriptor.plugin)
					result.onSuccess {
						mangaSourcesRepository.refreshInstalledLnReaderSources()
						onMessage.call(context.getString(R.string.extension_installed))
						refresh()
					}.onFailure {
						errorEvent.call(it)
					}
				}

				descriptor.isInstalled -> {
					if (lnReaderSourceManager.uninstall(descriptor.plugin.id)) {
						mangaSourcesRepository.refreshInstalledLnReaderSources()
						onMessage.call(context.getString(R.string.extension_removed))
						refresh()
					}
				}
			}
		}
	}

	private fun lnReaderRepository(): LNReaderRepository = LNReaderRepository(
		httpClient = lnReaderSourceManager.httpClient,
		installer = LNReaderPluginInstaller { jsContent, metadata ->
			lnReaderSourceManager.install(jsContent, metadata)
		},
	)

	private suspend fun loadContent(query: String?): List<ListModel> {
		val repo = repoRepository.findRepo(repoUrl) ?: return listOf(
			LnReaderPluginListItem.Hint(
				icon = R.drawable.ic_empty_feed,
				title = R.string.extension_repo_not_found,
				text = R.string.no_repo_extensions_text,
			),
		)
		val installed = db.getLnReaderSourcesDao().findAll().associateBy { it.pluginId }
		val plugins = lnReaderRepository().fetchPluginIndex(repo.url).getOrThrow()
		val filtered = plugins.asSequence()
			.filter { plugin -> query.isNullOrEmpty() || plugin.matchesQuery(query) }
			.map { plugin ->
				LnReaderPluginListItem.Extension(
					LnReaderPluginDescriptor(
						plugin = plugin,
						isInstalled = plugin.id in installed,
						hasUpdate = installed[plugin.id]?.let { it.version != plugin.version } == true,
					),
				)
			}
			.sortedWith(
				compareByDescending<LnReaderPluginListItem.Extension> { it.descriptor.hasUpdate }
					.thenByDescending { it.descriptor.isInstalled }
					.thenBy { it.descriptor.plugin.name.lowercase() },
			)
			.toList()
		return if (filtered.isEmpty()) {
			listOf(
				LnReaderPluginListItem.Hint(
					icon = R.drawable.ic_empty_feed,
					title = R.string.nothing_found,
					text = R.string.nothing_found,
				),
			)
		} else {
			filtered
		}
	}

	private fun LNReaderPluginInfo.matchesQuery(query: String): Boolean {
		return name.contains(query, ignoreCase = true) ||
			id.contains(query, ignoreCase = true) ||
			lang.contains(query, ignoreCase = true) ||
			site.contains(query, ignoreCase = true)
	}

	private companion object {
		private const val SEARCH_DEBOUNCE_TIMEOUT = 180L
		private const val CONTENT_STOP_TIMEOUT_MS = 5000L
	}
}

data class LnReaderPluginDescriptor(
	val plugin: LNReaderPluginInfo,
	val isInstalled: Boolean,
	val hasUpdate: Boolean,
)
