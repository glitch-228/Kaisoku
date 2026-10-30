package org.koitharu.kotatsu.scrobbling.anilist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.scrobbling.common.data.TrackerLibraryRepository
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.search.domain.SearchRequest
import org.koitharu.kotatsu.search.domain.SearchTrackerAccount
import org.koitharu.kotatsu.search.unified.*
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

@HiltViewModel
class AniListLibraryViewModel @Inject constructor(
    val repository: TrackerLibraryRepository,
    val searchRepository: TrackerSearchRepository,
    private val savedState: SavedStateHandle,
    private val settings: AppSettings,
    private val sources: MangaSourcesRepository,
    private val local: LocalMangaRepository,
) : BaseViewModel() {
    val service = MutableStateFlow(savedState.get<ScrobblerService>("library_service") ?: ScrobblerService.ANILIST)
    val filter = MutableStateFlow(savedState.get<String>("tracker_filter_v1")?.let {
        runCatching { Json.decodeFromString(TrackerLibraryFilterState.serializer(), it) }.getOrNull()
    } ?: TrackerLibraryFilterState())
    val cards = MutableStateFlow<List<TrackerSearchCard>>(emptyList())
    val account = MutableStateFlow<SearchTrackerAccount?>(null)
    val error = MutableStateFlow<Throwable?>(null)
    val isConnected = MutableStateFlow(false)
    val userName = MutableStateFlow<String?>(null)
    val syncMessage = MutableStateFlow<Pair<Int, String?>>(0 to null)
    val availableSources = MutableStateFlow<List<org.koitharu.kotatsu.parsers.model.MangaSource>>(emptyList())
    private val enabledSources = MutableStateFlow<Set<String>>(emptySet())
    private val pinnedSources = MutableStateFlow<Set<String>>(emptySet())
    private val downloadedIds = MutableStateFlow<Set<Long>>(emptySet())
    val content = combine(cards, filter, enabledSources, pinnedSources, downloadedIds, ::filterTrackerCards)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var job: Job? = null
    private var watcher: Job? = null
    private var generation = 0L
    private var savedTitles = emptyList<SavedTrackerTitle>()
    private val cardRevision = AtomicLong()

    init {
        watchAccount()
        viewModelScope.launch {
            sources.observeRecommendationVersion().collect { refreshSources(); rebuildCards() }
        }
        viewModelScope.launch {
            settings.observe(AppSettings.KEY_DISABLE_NSFW, AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED,
                AppSettings.KEY_SOURCE_CONTENT_CLASSES).collect { refreshSources(); rebuildCards() }
        }
        viewModelScope.launch { searchRepository.tools.localChanges.collect { rebuildCards() } }
    }

    fun updateFilter(value: TrackerLibraryFilterState) {
        filter.value = value
        savedState["tracker_filter_v1"] = Json.encodeToString(TrackerLibraryFilterState.serializer(), value)
    }
    fun setSearch(query: String) = updateFilter(filter.value.copy(query = query))
    fun request(query: String) = SearchRequest(query, sources = filter.value.sources,
        tracker = account.value.takeIf { settings.useCombinedTrackerSearch })

    fun selectService(value: ScrobblerService) {
        if (service.value == value) return
        generation++; job?.cancel(); service.value = value; savedState["library_service"] = value
        account.value = null; cards.value = emptyList(); savedTitles = emptyList(); error.value = null
        watchAccount(); refreshIfStale()
    }
    fun refreshIfStale() = load(false)
    fun refresh() = load(true)

    private fun watchAccount() {
        watcher?.cancel()
        val selected = service.value
        watcher = viewModelScope.launch {
            repository.scrobbler(selected).trackerRepository.storage.sessionChanges.collect {
                if (service.value != selected) return@collect
                val tracker = repository.scrobbler(selected).trackerRepository
                if (!tracker.isAuthorized || account.value?.let { !searchRepository.isCurrent(it) } == true) {
                    generation++; job?.cancel(); account.value = null; cards.value = emptyList(); savedTitles = emptyList()
                    error.value = null; syncMessage.value = 0 to null
                }
                isConnected.value = tracker.isAuthorized
                userName.value = tracker.cachedUser?.nickname
            }
        }
    }

    private suspend fun refreshSources() = withContext(Dispatchers.IO) {
        val enabled = sources.getEnabledSources()
        enabledSources.value = enabled.map { it.name }.toSet()
        pinnedSources.value = sources.getPinnedSources().map { it.name }.toSet()
        availableSources.value = (enabled + sources.getDisabledSources()).distinctBy { it.name }
    }

    private suspend fun rebuildCards() = withContext(Dispatchers.IO) {
        val revision = cardRevision.incrementAndGet()
        val owner = account.value ?: return@withContext
        val snapshot = savedTitles
        val value = searchRepository.cards(owner, snapshot.map { it.work }, snapshot)
        val downloaded = value.flatMap { it.editions }.distinctBy { it.id }.filter {
            local.findIndexedSavedManga(it, withDetails = false) != null
        }.map { it.id }.toSet()
        withContext(Dispatchers.Main.immediate) {
            if (revision == cardRevision.get() && account.value == owner && savedTitles === snapshot && searchRepository.isCurrent(owner)) {
                cards.value = value
                downloadedIds.value = downloaded
            }
        }
    }

    private suspend fun cachedTitles(owner: SearchTrackerAccount): List<SavedTrackerTitle> {
        val current = searchRepository.cachedLibrary(owner)
        if (current.isNotEmpty()) return current
        val storage = repository.scrobbler(ScrobblerService.valueOf(owner.provider)).trackerRepository.storage
        return repository.cached(ScrobblerService.valueOf(owner.provider), storage.snapshot()).orEmpty().map {
            SavedTrackerTitle(CatalogueTitle(it.mediaId.toString(), it.title, it.aliases, it.coverUrl, it.url,
                it.format, chapters = it.chapters?.toString()), it.listEntryId.toString(), it.status,
                it.progress.toString(), score = it.score.toString(), updatedAt = it.updatedAt.toString())
        }
    }

    private fun load(force: Boolean) {
        if (job?.isActive == true && !force) return
        job?.cancel()
        val revision = ++generation
        val selected = service.value
        job = launchLoadingJob(Dispatchers.Main.immediate) {
            error.value = null
            val tracker = repository.scrobbler(selected).trackerRepository
            isConnected.value = tracker.isAuthorized
            if (!tracker.isAuthorized) return@launchLoadingJob
            try {
                refreshSources()
                searchRepository.cachedAccount(selected)?.let { cached ->
                    account.value = cached; savedTitles = withContext(Dispatchers.IO) { cachedTitles(cached) }; rebuildCards()
                }
                val owner = withContext(Dispatchers.IO) { searchRepository.connect(selected) }
                if (revision != generation || service.value != selected) return@launchLoadingJob
                account.value = owner; userName.value = tracker.cachedUser?.nickname
                savedTitles = withContext(Dispatchers.IO) { cachedTitles(owner) }; rebuildCards()
                val fresh = withContext(Dispatchers.IO) { searchRepository.refreshLibrary(owner, force) }
                if (revision != generation || !searchRepository.isCurrent(owner)) return@launchLoadingJob
                savedTitles = fresh; rebuildCards()
                syncMessage.value = tracker.storage.progress.pendingCount(owner.localAccount) to
                    tracker.storage.progress.error(owner.localAccount)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (revision == generation) error.value = e }
        }
    }
}
