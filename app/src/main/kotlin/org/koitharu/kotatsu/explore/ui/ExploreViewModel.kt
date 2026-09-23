package org.koitharu.kotatsu.explore.ui

import androidx.collection.LongSet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.combine
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.customsource.data.CustomSourcesRepository
import org.koitharu.kotatsu.customsource.domain.CustomSource
import org.koitharu.kotatsu.customsource.domain.CustomSourceType
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.domain.ExploreRepository
import org.koitharu.kotatsu.explore.ui.model.BrowserSourceItem
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.RecommendationsItem
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.list.ui.model.EmptyHint
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
	private val settings: AppSettings,
	private val suggestionRepository: SuggestionRepository,
	private val exploreRepository: ExploreRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val customSourcesRepository: CustomSourcesRepository,
	private val shortcutManager: AppShortcutManager,
) : BaseViewModel() {

	val isGrid = settings.observeAsStateFlow(
		key = AppSettings.KEY_SOURCES_GRID,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { isSourcesGridMode },
	)

	val isAllSourcesEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_SOURCES_ENABLED_ALL,
		valueProducer = { isAllSourcesEnabled },
	)

	private val novelSourcesFirst = settings.observeAsStateFlow(
		key = AppSettings.KEY_NOVEL_SOURCES_FIRST,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { settings.isNovelSourcesFirst },
	)

	private val isSuggestionsEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_SUGGESTIONS,
		valueProducer = { isSuggestionsEnabled },
	)

	val onOpenManga = MutableEventFlow<Manga>()
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onShowSuggestionsTip = MutableEventFlow<Unit>()
	private val isRandomLoading = MutableStateFlow(false)
	private val mutableContent = MutableStateFlow(buildExploreLoadingStateList(isRandomLoading.value))
	private var contentJob: Job? = null

	val content: StateFlow<List<ListModel>> = mutableContent

	init {
		if (shouldShowSuggestionsTip(settings)) {
			onShowSuggestionsTip.call(Unit)
		}
		val loading = isLoading
		val contentFlow = createContentFlow()
		val randomLoading = isRandomLoading
		val contentState = mutableContent
		contentJob = launchJob(Dispatchers.Default) {
			collectExploreContent(loading, contentFlow, randomLoading, contentState)
		}
	}

	override fun onCleared() {
		contentJob?.cancel()
		contentJob = null
		super.onCleared()
	}

	fun openRandom() {
		if (isRandomLoading.value) {
			return
		}
		launchJob(Dispatchers.Default) {
			isRandomLoading.value = true
			try {
				val manga = exploreRepository.findRandomManga(tagsLimit = 8)
				onOpenManga.call(manga)
			} finally {
				isRandomLoading.value = false
			}
		}
	}

	fun disableSources(sources: Collection<MangaSource>) {
		launchJob(Dispatchers.Default) {
			val rollback = sourcesRepository.setSourcesEnabled(sources, isEnabled = false)
			val message = if (sources.size == 1) R.string.source_disabled else R.string.sources_disabled
			onActionDone.call(ReversibleAction(message, rollback))
		}
	}

	fun requestPinShortcut(source: MangaSource) {
		launchLoadingJob(Dispatchers.Default) {
			shortcutManager.requestPinShortcut(source)
		}
	}

	fun setSourcesPinned(sources: Collection<MangaSource>, isPinned: Boolean) {
		launchJob(Dispatchers.Default) {
			sourcesRepository.setIsPinned(sources, isPinned)
			val message = if (sources.size == 1) {
				if (isPinned) R.string.source_pinned else R.string.source_unpinned
			} else {
				if (isPinned) R.string.sources_pinned else R.string.sources_unpinned
			}
			onActionDone.call(ReversibleAction(message, null))
		}
	}

	fun setSourcesNsfw(sources: Collection<MangaSource>, isNsfw: Boolean) {
		launchJob(Dispatchers.Default) {
			val rollback = sourcesRepository.setNsfwOverride(sources, isNsfw)
			val message = if (sources.size == 1) {
				if (isNsfw) R.string.source_marked_nsfw else R.string.source_unmarked_nsfw
			} else {
				if (isNsfw) R.string.sources_marked_nsfw else R.string.sources_unmarked_nsfw
			}
			onActionDone.call(ReversibleAction(message, rollback))
		}
	}

	fun respondSuggestionTip(isAccepted: Boolean) {
		settings.isSuggestionsEnabled = isAccepted
		settings.closeTip(TIP_SUGGESTIONS)
	}

	fun sourcesSnapshot(ids: LongSet): List<MangaSourceInfo> {
		return content.value.mapNotNull {
			(it as? MangaSourceItem)?.takeIf { x -> x.id in ids }?.source
		}
	}

	fun removeBrowserSource(id: Long) {
		customSourcesRepository.remove(id)
	}

	private fun createContentFlow(): Flow<List<ListModel>> {
		return createExploreContentFlow(
			enabledSources = sourcesRepository.observeEnabledSources().combine(novelSourcesFirst) { sources, novelsFirst ->
				if (novelsFirst) sources.sortedBy { it.mangaSource !is LnReaderMangaSource } else sources
			},
			browserSources = customSourcesRepository.sources,
			isSuggestionsEnabled = isSuggestionsEnabled,
			suggestionRepository = suggestionRepository,
			suggestionsCount = SUGGESTIONS_COUNT,
			isGrid = isGrid,
			isRandomLoading = isRandomLoading,
			isAllSourcesEnabled = isAllSourcesEnabled,
			hasNewSources = sourcesRepository.observeHasNewSourcesForBadge(),
			onError = errorEvent,
		)
	}

	companion object {

		private const val TIP_SUGGESTIONS = "suggestions"
		private const val SUGGESTIONS_COUNT = 8
	}
}

private fun buildExploreContentList(
	sources: List<MangaSourceInfo>,
	browserSources: List<CustomSource>,
	recommendation: List<Manga>,
	isGrid: Boolean,
	randomLoading: Boolean,
	allSourcesEnabled: Boolean,
	hasNewSources: Boolean,
): List<ListModel> {
	val result = ArrayList<ListModel>(sources.size + browserSources.size + 4)
	result += ExploreButtons(randomLoading)
	if (recommendation.isNotEmpty()) {
		result += ListHeader(R.string.suggestions, R.string.more, R.id.nav_suggestions)
		result += RecommendationsItem(recommendation.toRecommendationList())
	}
	if (browserSources.isNotEmpty()) {
		result += ListHeader(textRes = R.string.browser_sources)
		browserSources.mapTo(result) { BrowserSourceItem(it) }
	}
	if (sources.isNotEmpty()) {
		result += ListHeader(
			textRes = R.string.remote_sources,
			buttonTextRes = if (allSourcesEnabled) R.string.manage else R.string.catalog,
			badge = if (!allSourcesEnabled && hasNewSources) "" else null,
		)
		sources.mapTo(result) { MangaSourceItem(it, isGrid) }
	} else if (browserSources.isEmpty()) {
		result += EmptyHint(
			icon = R.drawable.ic_empty_common,
			textPrimary = R.string.no_manga_sources,
			textSecondary = R.string.no_manga_sources_text,
			actionStringRes = R.string.catalog,
		)
	}
	return result
}

private fun buildExploreLoadingStateList(randomLoading: Boolean) = listOf(
	ExploreButtons(randomLoading),
	LoadingState,
)

private fun shouldShowSuggestionsTip(settings: AppSettings): Boolean {
	return !settings.isSuggestionsEnabled && settings.isTipEnabled("suggestions")
}

private fun createExploreContentFlow(
	enabledSources: Flow<List<MangaSourceInfo>>,
	browserSources: Flow<List<CustomSource>>,
	isSuggestionsEnabled: Flow<Boolean>,
	suggestionRepository: SuggestionRepository,
	suggestionsCount: Int,
	isGrid: Flow<Boolean>,
	isRandomLoading: Flow<Boolean>,
	isAllSourcesEnabled: Flow<Boolean>,
	hasNewSources: Flow<Boolean>,
	onError: MutableEventFlow<Throwable>,
): Flow<List<ListModel>> = combine(
	enabledSources,
	browserSources.map { sources ->
		sources.filter { it.isEnabled && it.type == CustomSourceType.BROWSER_SOURCE }
	},
	observeExploreSuggestions(isSuggestionsEnabled, suggestionRepository, suggestionsCount),
	isGrid,
	isRandomLoading,
	isAllSourcesEnabled,
	hasNewSources,
	::buildExploreContentList,
).catch { error ->
	error.printStackTraceDebug()
	onError.call(error)
}

private fun observeExploreSuggestions(
	isSuggestionsEnabled: Flow<Boolean>,
	suggestionRepository: SuggestionRepository,
	suggestionsCount: Int,
): Flow<List<Manga>> = isSuggestionsEnabled.mapLatest { isEnabled ->
	if (isEnabled) {
		runCatching {
			suggestionRepository.getRandomList(suggestionsCount)
		}.getOrDefault(emptyList())
	} else {
		emptyList()
	}
}

private suspend fun collectExploreContent(
	isLoading: Flow<Boolean>,
	content: Flow<List<ListModel>>,
	isRandomLoading: Flow<Boolean>,
	target: MutableStateFlow<List<ListModel>>,
) {
	combine(isLoading, content, isRandomLoading, ::mergeExploreContent).collect {
		target.value = it
	}
}

private fun mergeExploreContent(
	loading: Boolean,
	content: List<ListModel>,
	randomLoading: Boolean,
): List<ListModel> = if (loading) {
	buildExploreLoadingStateList(randomLoading)
} else {
	content
}

private fun List<Manga>.toRecommendationList() = map { manga ->
	MangaCompactListModel(
		manga = manga,
		override = null,
		subtitle = manga.tags.joinToString { it.title },
		counter = 0,
	)
}
