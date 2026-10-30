package org.koitharu.kotatsu.search.ui.multi

import androidx.collection.ArraySet
import androidx.collection.LongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.drop
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

private const val MAX_PARALLELISM = 4

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val mangaListMapper: MangaListMapper,
    private val searchHelperFactory: SearchV2Helper.Factory,
    private val sourcesRepository: MangaSourcesRepository,
    private val historyRepository: HistoryRepository,
    private val favouritesRepository: FavouritesRepository,
    private val settings: AppSettings,
    val trackerSearch: org.koitharu.kotatsu.search.unified.TrackerSearchRepository,
) : BaseViewModel() {

    var query = savedStateHandle.get<String>(AppRouter.KEY_QUERY).orEmpty()
        private set
    val kind = savedStateHandle.get<SearchKind>(AppRouter.KEY_KIND) ?: SearchKind.SIMPLE

    private var request = org.koitharu.kotatsu.search.domain.SearchRequest.decode(
        savedStateHandle.get(org.koitharu.kotatsu.search.domain.SearchRequest.EXTRA))
        ?: org.koitharu.kotatsu.search.domain.SearchRequest(query, kind)
    val sourceScope get() = request.sources
    val trackerAccount get() = request.tracker
    val availableSources = MutableStateFlow<List<MangaSource>>(emptyList())
    val warnings = MutableStateFlow<List<Throwable>>(emptyList())
    private val trackerCards = MutableStateFlow<List<org.koitharu.kotatsu.search.unified.TrackerSearchCard>>(emptyList())
    private var catalogueTitles = emptyList<org.koitharu.kotatsu.search.unified.CatalogueTitle>()
    private val catalogueNext = MutableStateFlow<String?>(null)
    private var catalogueJob: Job? = null
    @Volatile private var catalogueRevision = 0L
    private var observedCombined = settings.useCombinedTrackerSearch
    private var observedPolicy = settings.sourceContentPolicy

    private var includeDisabledSources = MutableStateFlow(false)
    private var pinnedOnly = MutableStateFlow(false)
    private var hideEmpty = MutableStateFlow(true)
    private val results = MutableStateFlow<List<SearchResultsListModel>>(emptyList())

    private var searchJob: Job? = null
    private var continuationJob: Job? = null
    private val searchGeneration = AtomicLong()

    private val sourceList: StateFlow<List<ListModel>> = observeSearchResults(
        combine(results, favouritesRepository.observeMembershipChanges(),
            settings.observe(AppSettings.KEY_SEARCH_FAVORITE_CATEGORIES, AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED,
                AppSettings.KEY_SOURCE_CONTENT_CLASSES, AppSettings.KEY_DISABLE_NSFW).onStart { emit(null) }) { groups, _, _ ->
            groups.filter { it.titleResId != 0 || settings.sourceContentPolicy.allows(it.source) }
                .map { group -> group.copy(list = mangaListMapper.withFavoriteCategories(
                    group.list.filter { settings.sourceContentPolicy.allows(it.manga) },
                )) }
        },
        isLoading,
        includeDisabledSources,
        hideEmpty,
    ).stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

    val list = combine(sourceList, trackerCards, catalogueNext) { sourceRows, cards, next ->
        org.koitharu.kotatsu.search.unified.unifiedSearchSections(sourceRows, cards, next != null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(LoadingState))

    init {
        includeDisabledSources.value = sourceScope.kind == org.koitharu.kotatsu.search.domain.SourceScopeKind.SINGLE
        doSearch()
        observeSourcePolicy()
        viewModelScope.launch {
            settings.observe(AppSettings.KEY_COMBINED_TRACKER_SEARCH).collect {
                val enabled = settings.useCombinedTrackerSearch
                if (enabled != observedCombined) {
                    observedCombined = enabled
                    startCatalogue()
                }
            }
        }
        viewModelScope.launch {
            trackerSearch.tools.localChanges.drop(1).collect { startCatalogue(discover = false) }
        }
        viewModelScope.launch {
            trackerSearch.sessionChanges.collect {
                request.tracker?.let { owner -> if (!trackerSearch.isCurrent(owner)) {
                    catalogueRevision++; catalogueJob?.cancel(); trackerCards.value = emptyList(); catalogueNext.value = null
                } }
            }
        }
    }
    private fun observeSourcePolicy() {
        viewModelScope.launch {
            settings.observe(AppSettings.KEY_DISABLE_NSFW, AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED,
                AppSettings.KEY_SOURCE_CONTENT_CLASSES).collect {
                val policy = settings.sourceContentPolicy
                if (policy != observedPolicy) {
                    observedPolicy = policy
                    results.value = emptyList()
                    retry()
                }
            }
        }
        viewModelScope.launch {
            sourcesRepository.observeRecommendationVersion().drop(1).collect { results.value = emptyList(); retry() }
        }
    }

    fun getItems(ids: LongSet): Set<Manga> {
        val snapshot = results.value
        val result = ArraySet<Manga>(ids.size)
        snapshot.forEach { x ->
            for (item in x.list) {
                if (item.id in ids) {
                    result.add(item.manga)
                }
            }
        }
        return result
    }

    fun retry() {
        warnings.value = emptyList()
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        continuationJob?.cancel()
        includeDisabledSources.value = sourceScope.kind == org.koitharu.kotatsu.search.domain.SourceScopeKind.SINGLE
        doSearch()
    }

    fun search(newQuery: String) {
        val trimmed = newQuery.trim()
        if (trimmed.isEmpty() || trimmed == query) return
        query = trimmed
        savedStateHandle[AppRouter.KEY_QUERY] = trimmed
        request = request.copy(query = trimmed)
        saveRequest()
        results.value = emptyList()
        retry()
    }

    fun setPinnedOnly(value: Boolean) {
        pinnedOnly.value = value
        setSourceScope(org.koitharu.kotatsu.search.domain.ReadingSourceScope(
            if (value) org.koitharu.kotatsu.search.domain.SourceScopeKind.PINNED
            else org.koitharu.kotatsu.search.domain.SourceScopeKind.ENABLED))
    }
    fun isPinnedOnlyEnabled() = sourceScope.kind == org.koitharu.kotatsu.search.domain.SourceScopeKind.PINNED

    fun setHideEmpty(value: Boolean) {
        hideEmpty.value = value
    }

    fun isHideEmptyEnabled(): Boolean = hideEmpty.value

    fun continueSearch() {
        if (sourceScope.kind != org.koitharu.kotatsu.search.domain.SourceScopeKind.ENABLED || includeDisabledSources.value) {
            return
        }
        val prevJob = searchJob
        val generation = searchGeneration.get()
        val searchQuery = query
        val searchPinnedOnly = pinnedOnly.value
        includeDisabledSources.value = true
        continuationJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.join()
            val sources = if (searchPinnedOnly) {
                emptyList()
            } else {
                sourcesRepository.getDisabledSources()
                    .sortedByDescending { it.priority() }
            }
            val semaphore = Semaphore(MAX_PARALLELISM)
            sources.map { source ->
                launch {
                    semaphore.withPermit {
                        appendResult(searchSource(source, searchQuery), generation)
                    }
                }
            }.joinAll()
        }
    }

    private fun doSearch() {
        val previousJobs = listOfNotNull(searchJob, continuationJob)
        continuationJob = null
        val generation = searchGeneration.incrementAndGet()
        val searchQuery = query
        val searchPinnedOnly = pinnedOnly.value
        startCatalogue()
        searchJob = launchLoadingJob(Dispatchers.Default) {
            cancelSearchTasks(previousJobs)
            runSearchTasks(
                localTasks = listOf(
                    { appendResult(searchHistory(searchQuery), generation) },
                    { appendResult(searchFavorites(searchQuery), generation) },
                    { appendResult(searchLocal(searchQuery), generation) },
                ),
                remoteTasks = {
                    val enabled = sourcesRepository.getEnabledSources()
                    val all = (enabled + sourcesRepository.getDisabledSources()).distinctBy { it.name }
                    availableSources.value = all
                    val sources = when (sourceScope.kind) {
                        org.koitharu.kotatsu.search.domain.SourceScopeKind.SINGLE -> all.filter { it.name == sourceScope.sourceName }
                        org.koitharu.kotatsu.search.domain.SourceScopeKind.PINNED -> sourcesRepository.getPinnedSources().toList()
                        else -> if (searchPinnedOnly) sourcesRepository.getPinnedSources().toList() else enabled
                    }
                    val semaphore = Semaphore(MAX_PARALLELISM)
                    sources.map { source ->
                        launch {
                            semaphore.withPermit {
                                appendResult(searchSource(source, searchQuery), generation)
                            }
                        }
                    }.joinAll()
                },
            )
        }
    }

    // impl

    private suspend fun searchSource(source: MangaSource, searchQuery: String): SearchResultsListModel? = runCatchingCancellable {
        val searchHelper = searchHelperFactory.create(source)
        searchHelper(searchQuery, kind)
    }.fold(
        onSuccess = { result ->
            if (result == null || result.manga.isEmpty()) {
                null
            } else {
                val list = mangaListMapper.toListModelList(
                    manga = result.manga,
                    mode = ListMode.GRID,
                )
                SearchResultsListModel(
                    titleResId = 0,
                    source = source,
                    list = list,
                    error = null,
                    listFilter = result.listFilter,
                    sortOrder = result.sortOrder,
                )
            }
        },
        onFailure = { error ->
            error.printStackTraceDebug()
            if (source is MangaParserSource && source.isBroken) {
                null
            } else {
                SearchResultsListModel(0, source, null, null, emptyList(), error)
            }
        },
    )

    private suspend fun searchHistory(searchQuery: String): SearchResultsListModel? = runCatchingCancellable {
        historyRepository.search(searchQuery, kind, Int.MAX_VALUE)
    }.fold(
        onSuccess = { result ->
            if (result.isNotEmpty()) {
                SearchResultsListModel(
                    titleResId = R.string.history,
                    source = UnknownMangaSource,
                    list = mangaListMapper.toListModelList(manga = result, mode = ListMode.GRID),
                    error = null,
                    listFilter = null,
                    sortOrder = null,
                )
            } else {
                null
            }
        },
        onFailure = { error ->
            SearchResultsListModel(
                titleResId = R.string.history,
                source = UnknownMangaSource,
                list = emptyList(),
                error = error,
                listFilter = null,
                sortOrder = null,
            )
        },
    )

    private suspend fun searchFavorites(searchQuery: String): SearchResultsListModel? = runCatchingCancellable {
        favouritesRepository.search(searchQuery, kind, Int.MAX_VALUE)
    }.fold(
        onSuccess = { result ->
            if (result.isNotEmpty()) {
                SearchResultsListModel(
                    titleResId = R.string.favourites,
                    source = UnknownMangaSource,
                    list = mangaListMapper.toListModelList(
                        manga = result,
                        mode = ListMode.GRID,
                        flags = MangaListMapper.NO_FAVORITE,
                    ),
                    error = null,
                    listFilter = null,
                    sortOrder = null,
                )
            } else {
                null
            }
        },
        onFailure = { error ->
            SearchResultsListModel(
                titleResId = R.string.favourites,
                source = UnknownMangaSource,
                list = emptyList(),
                error = error,
                listFilter = null,
                sortOrder = null,
            )
        },
    )

    private suspend fun searchLocal(searchQuery: String): SearchResultsListModel? = runCatchingCancellable {
        searchHelperFactory.create(LocalMangaSource).invoke(searchQuery, kind)
    }.fold(
        onSuccess = { result ->
            if (!result?.manga.isNullOrEmpty()) {
                SearchResultsListModel(
                    titleResId = 0,
                    source = LocalMangaSource,
                    list = mangaListMapper.toListModelList(
                        manga = result.manga,
                        mode = ListMode.GRID,
                        flags = MangaListMapper.NO_SAVED,
                    ),
                    error = null,
                    listFilter = result.listFilter,
                    sortOrder = result.sortOrder,
                )
            } else {
                null
            }
        },
        onFailure = { error ->
            SearchResultsListModel(
                titleResId = 0,
                source = LocalMangaSource,
                list = emptyList(),
                error = error,
                listFilter = null,
                sortOrder = null,
            )
        },
    )

    private fun appendResult(item: SearchResultsListModel?, generation: Long) {
        if (item == null || generation != searchGeneration.get()) return
        item.error?.let { error -> warnings.update { (it + error).takeLast(16) } }
        val scoped = if (sourceScope.kind == org.koitharu.kotatsu.search.domain.SourceScopeKind.SINGLE)
            item.copy(list = item.list.filter { it.manga.source.name == sourceScope.sourceName }) else item
        results.update { previous ->
            if (generation != searchGeneration.get()) previous else {
                val old = previous.firstOrNull { it.source == item.source && it.titleResId == item.titleResId }
                val retained = if (item.error != null && old != null) old else scoped.copy(error = null)
                (previous.filterNot { it.source == item.source && it.titleResId == item.titleResId } + retained)
                    .sortedWith(compareBy<SearchResultsListModel> { if (it.titleResId == 0) 1 else 0 }
                        .thenBy { it.titleResId }.thenBy { it.source.name })
            }
        }
    }

    fun setSourceScope(scope: org.koitharu.kotatsu.search.domain.ReadingSourceScope) {
        request = request.copy(sources = scope); saveRequest(); results.value = emptyList(); retry()
    }
    fun setTracker(account: org.koitharu.kotatsu.search.domain.SearchTrackerAccount?) {
        request = request.copy(tracker = account, trackerTarget = null); saveRequest(); startCatalogue()
    }
    fun scopedRequest() = request.copy(query = query)
    private fun saveRequest() { savedStateHandle[org.koitharu.kotatsu.search.domain.SearchRequest.EXTRA] = request.encode() }

    fun loadCatalogueMore() { if (catalogueJob?.isActive != true) startCatalogue(append = true) }

    private fun startCatalogue(append: Boolean = false, discover: Boolean = true) {
        catalogueRevision++
        catalogueJob?.cancel()
        val revision = catalogueRevision
        val owner = request.tracker
        if (!settings.useCombinedTrackerSearch || owner == null || request.trackerTarget != null ||
            !trackerSearch.isCurrent(owner) || kind !in setOf(SearchKind.SIMPLE, SearchKind.TITLE)) {
            trackerCards.value = emptyList(); catalogueNext.value = null; catalogueTitles = emptyList(); return
        }
        val searchQuery = query
        val cursor = if (append) catalogueNext.value ?: return else null
        val previousIds = catalogueTitles.map { it.id }.toSet()
        if (!append) { catalogueTitles = emptyList(); trackerCards.value = emptyList(); catalogueNext.value = null }
        catalogueJob = viewModelScope.launch(Dispatchers.IO) {
            val saved = trackerSearch.cachedLibrary(owner).filter { org.koitharu.kotatsu.search.unified.trackerQueryMatches(it.work, searchQuery) }
            suspend fun publish() {
                val enabled = sourcesRepository.getEnabledSources().map { it.name }.toSet()
                val pinned = sourcesRepository.getPinnedSources().map { it.name }.toSet()
                val unique = (saved.map { it.work } + catalogueTitles).distinctBy { it.id }
                val cards = trackerSearch.cards(owner, unique, saved).map { card ->
                    card.copy(editions = card.editions.filter { manga -> sourceScope.contains(manga.source.name, enabled, pinned) })
                }
                if (revision == catalogueRevision && trackerSearch.isCurrent(owner) && settings.useCombinedTrackerSearch)
                    trackerCards.value = cards
            }
            try {
                trackerSearch.cachedCatalogue(owner, searchQuery, cursor, stale = true)?.let {
                    if (revision == catalogueRevision) {
                        catalogueTitles = (catalogueTitles + it.titles).distinctBy { title -> title.id }
                        catalogueNext.value = it.nextCursor
                    }
                }
                publish()
                if (!discover || searchQuery.trim().length !in 2..100) return@launch
                val page = trackerSearch.catalogue(owner, searchQuery, cursor)
                if (revision != catalogueRevision) return@launch
                catalogueTitles = (catalogueTitles + page.titles).distinctBy { it.id }
                catalogueNext.value = if (append && page.titles.isNotEmpty() && page.titles.all { it.id in previousIds }) {
                    warnings.update { (it + IllegalStateException("Tracker catalogue pagination did not advance")).takeLast(16) }
                    null
                } else page.nextCursor
                publish()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { if (revision == catalogueRevision) warnings.update { (it + e).takeLast(16) } }
        }
    }

    private fun MangaSource.priority(): Int {
        var res = 0
        if (this is MangaParserSource) {
            if (locale.toLocale() == Locale.getDefault()) res += 2
        }
        return res
    }
}
