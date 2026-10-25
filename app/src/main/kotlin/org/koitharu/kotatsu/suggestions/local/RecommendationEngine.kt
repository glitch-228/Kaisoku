package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.core.parser.hasChapterText

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.contentClass
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationEngine @Inject constructor(
    private val store: RecommendationStore,
    private val library: MangaDatabase,
    private val sources: MangaSourcesRepository,
    private val repositories: MangaRepository.Factory,
    val settings: AppSettings,
    private val publicProviders: PublicRecommendationProviders,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = RecommendationJobs(scope)
    private val sourcesReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile private var sourceGeneration = ""
    @Volatile private var identityGeneration = ""
    private val generations = MutableStateFlow("")
    val changes get() = store.changes

    init {
        scope.launch {
            combine(settings.observe(*KEYS).onStart { emit(null) }, sources.observeRecommendationVersion()) { _, version -> version }
                .collect { version ->
                sourceGeneration = version
                sourcesReady.complete(Unit)
                // A settings change invalidates publishers as well as observers, including provider calls.
                jobs.invalidate(jobGeneration())
                generations.value = jobGeneration()
                store.changed()
            }
        }
        scope.launch {
            library.getScrobblingDao().observeAll(2).map(RecommendationIdentity::trackerVersion)
                .distinctUntilChanged().collect { version ->
                    identityGeneration = version
                    if (settings.publicRecommendations) {
                        jobs.invalidate(jobGeneration())
                        generations.value = jobGeneration()
                    }
                    store.changed()
                }
        }
    }

    fun observe(request: RecommendationRequest): Flow<RecommendationSnapshot> = flow {
        coroutineScope {
            launch { generations.filter { it.isNotEmpty() }.collectLatest { refresh(request, force = false) } }
            emitAll(observeCached(request))
        }
    }

    private fun observeCached(request: RecommendationRequest): Flow<RecommendationSnapshot> = combine(
        store.dao.observe(contextKey(request)),
        library.getMangaDao().recommendationTaste().distinctUntilChangedBy { rows -> rows.map {
            listOf(it.manga, it.tags, it.favorite, it.lastRead / 86400000, it.durationMillis / 300000, it.progress >= 1f, it.historyChapters)
        } },
        store.dao.dismissals(),
        combine(store.changes, library.invalidationTracker.createFlow("manga", "manga_tags", "preferences",
            "chapter_read_policy", "chapter_read_count")) { change, _ -> change },
        settings.observe(*KEYS).onStart { emit(null) },
    ) { row, taste, dismissed, _, _ -> Triple(row, taste, dismissed.toSet()) }
        .mapLatest { (row, taste, dismissed) ->
            if (settings.useLegacySuggestions) return@mapLatest RecommendationSnapshot()
            val available = availableSources().associateBy { it.name }
            val versions = available.values.associate { it.name to sourceVersion(it) }
            val publicScope = cacheScope(request)
            val input = RecommendationIdentity.deduplicate(publicProviders.withTrustedIdentities(
                store.results(row, versions).filter { it.target is RecommendationTarget.SourceTitle ||
                    (settings.publicRecommendations && row?.scope == publicScope) }
                    .ifEmpty { store.cachedCandidates(versions) },
            ))
            val baselines = store.dao.baselines().associate { it.key to RecommendationCodec.decodeBaseline(it.payload) }
            val progress = publicProviders.readingStates(input, baselines)
            val candidates = input.mapNotNull { item ->
                val current = (item.target as? RecommendationTarget.SourceTitle)?.let { target ->
                    available[target.manga.source.name]?.let { source -> item.copy(target = RecommendationTarget.SourceTitle(target.manga.copy(source = source))) }
                } ?: item.takeIf { it.target is RecommendationTarget.PublicTitle && settings.publicRecommendations && row?.scope == publicScope }
                current?.takeIf { eligible(it, request, dismissed, progress, baselines) }
            }
            val profile = taste.mapNotNull { title -> available[title.manga.source]?.let { source ->
                title.manga.toManga(title.tags.toMangaTags(), null).copy(source = source)
                    .takeUnless { excludeNsfw() && RecommendationContentPolicy.isNsfw(it, settings.useSeparateSourceContentClasses) }
                    ?.let { TasteTitle(it, title.favorite, title.lastRead, title.durationMillis) }
            } }
            val ranked = RecommendationRanker.rank(candidates, profile, request, settings.recommendationDiversity,
                row?.refreshed ?: System.currentTimeMillis()).map { value ->
                if (progress[value.key].orEmpty().any { it.lastRead > 0 }) value.copy(reason = RecommendationReason.NEW_CHAPTERS)
                else value
            }
            RecommendationSnapshot(ranked, error = row?.error)
        }.flowOn(Dispatchers.Default)

    suspend fun refresh(request: RecommendationRequest, force: Boolean = false) {
        if (settings.useLegacySuggestions || !enabled(request)) return
        sourcesReady.await()
        val key = contextKey(request)
        val generation = jobGeneration()
        jobs.run(key, generation) {
                if (!networkAllowed()) return@run
                // Filtering/settings changes rescore the cache rather than bypassing the cadence.
                val versions = availableSources().associate { it.name to sourceVersion(it) }
                val accepted = store.claimRefresh(key, force, System.currentTimeMillis(), versions)
                if (!accepted) return@run
                val scopeKey = cacheScope(request)
                refreshImpl(request, key, scopeKey, generation)
        }
    }

    suspend fun dismiss(value: Recommendation): ReversibleHandle {
        val keys = RecommendationIdentity.feedbackKeys(value)
        store.database.withTransaction { keys.forEach { store.dao.dismiss(RecommendationDismissalEntity(it, System.currentTimeMillis())) } }
        return ReversibleHandle { store.database.withTransaction { keys.forEach { store.dao.undo(it) } } }
    }
    suspend fun clearDismissals() = store.dao.clearDismissals()
    suspend fun cachedExplore(): List<Recommendation> = observeCached(RecommendationRequest.Explore).first().results

    private suspend fun refreshImpl(request: RecommendationRequest, key: String, scopeKey: String, generation: String) = coroutineScope {
        val old = store.dao.result(key)
        val now = System.currentTimeMillis()
        val maxSources = if (request is RecommendationRequest.Details) 1 else 8
        val maxOperations = if (request is RecommendationRequest.Details) {
            if (settings.publicRecommendations) 8 else 1
        } else 16
        val budget = RecommendationBudget(maxOperations)
        val sourceBudgets = mutableMapOf<String, RecommendationBudget>()
        val found = mutableListOf<Recommendation>()
        val failures = RecommendationFailures(old?.error)
        val sourceNames = mutableMapOf("public:ANILIST" to "AniList", "public:MANGAUPDATES" to "MangaUpdates")
        val resultLock = Mutex()
        suspend fun <T> operation(source: String, block: suspend () -> T): T? {
            if ((store.dao.cooldown(source)?.until ?: 0) > System.currentTimeMillis()) return null
            if (sourceBudgets[source]?.claim() == false) return null
            if (!budget.claim()) return null
            suspend fun execute(): T? {
                // Another title may have failed while this operation waited for a shared slot.
                if ((store.dao.cooldown(source)?.until ?: 0) > System.currentTimeMillis()) return null
                return failures.capture(source, sourceNames[source] ?: source, onFailure = { sourceFailed(source) }) {
                    withTimeout(20_000) { block() }
                }
            }
            return if (source.startsWith("public:")) execute() else RecommendationOperations.run { execute() }
        }
        try {
            withTimeout(120_000) {
                val tasteRows = library.getMangaDao().recommendationTaste().first()
                val seed = (request as? RecommendationRequest.Details)?.seed
                val permitted = availableSources()
                val permittedByName = permitted.associateBy { it.name }
                permitted.forEach { source ->
                    sourceNames[source.name] = runCatching { source.getTitle(context) }.getOrDefault(source.name)
                }
                failures.retainSources(permittedByName.keys + if (settings.publicRecommendations)
                    setOf("public:ANILIST", "public:MANGAUPDATES") else emptySet())
                val taste = tasteRows.mapNotNull { row -> permittedByName[row.manga.source]?.let { source ->
                    row.manga.toManga(row.tags.toMangaTags(), null).copy(source = source)
                        .takeUnless { excludeNsfw() && RecommendationContentPolicy.isNsfw(it, settings.useSeparateSourceContentClasses) }
                } }
                val ordered = permitted.sortedWith(compareBy<MangaSource> { it.name != seed?.source?.name }
                    .thenBy { source -> taste.indexOfFirst { it.source.name == source.name }.let { if (it < 0) Int.MAX_VALUE else it } }
                    .thenBy { it.name })
                    .filter { source -> when {
                        seed != null -> source.name == seed.source.name
                        settings.recommendationMedia == "NOVEL" -> isNovelSource(source)
                        settings.recommendationMedia == "MANGA" -> !isNovelSource(source)
                        else -> true
                    } }
                val sourceOrder = if (seed == null && settings.recommendationMedia == "ALL") {
                    (ordered.filter { isNovelSource(it) }.take(maxSources / 2) + ordered.filterNot { isNovelSource(it) }.take(maxSources / 2) + ordered)
                        .distinctBy { it.name }.take(maxSources).sortedBy { ordered.indexOf(it) }
                } else ordered.take(maxSources)
                sourceOrder.forEachIndexed { index, source ->
                    // Optional providers need room in the shared budget; never add calls above its limit.
                    val limit = if (seed != null || (settings.publicRecommendations && index >= 2)) 1 else 2
                    sourceBudgets[source.name] = RecommendationBudget(limit)
                }
                val versions = permitted.associate { it.name to sourceVersion(it) }
                found.addAll(store.results(old, permitted.associate { it.name to sourceVersion(it) })
                    .filter { it.target is RecommendationTarget.SourceTitle ||
                        (settings.publicRecommendations && old?.scope == scopeKey) })
                found.addAll(store.cachedCandidates(versions))
                // Cached favorites and history retain metadata without a full chapter crawl.
                taste.take(100).mapTo(found) { Recommendation(RecommendationTarget.SourceTitle(it)) }
                sourceOrder.map { source -> async {
                    val local = mutableListOf<Recommendation>()
                    try {
                        failures.capture(source.name, sourceNames[source.name] ?: source.name,
                                onFailure = { sourceFailed(source.name) }, recordSuccess = false) {
                            val repository = repositories.create(source)
                            val lookup = seed ?: taste.firstOrNull { it.source.name == source.name }
                            if (seed != null) {
                                if (repository.supportsRelatedDiscovery) {
                                    operation(source.name) { repository.discoverRelated(seed) }.orEmpty().take(60).mapTo(local) {
                                        Recommendation(RecommendationTarget.SourceTitle(it), provenance = RecommendationProvenance.RELATED)
                                    }
                                } else if (repository.filterCapabilities.isSearchSupported) {
                                    operation(source.name) { repository.discover(
                                        SortOrder.RELEVANCE.takeIf { it in repository.sortOrders } ?: repository.defaultSortOrder,
                                        MangaListFilter(query = seed.title.take(120)),
                                    ) }.orEmpty().take(35).mapTo(local) {
                                        Recommendation(RecommendationTarget.SourceTitle(it), provenance = RecommendationProvenance.SEARCH)
                                    }
                                }
                                return@capture
                            }
                            val orders = if (sourceOrder.indexOf(source) % 2 == 0) listOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST)
                                else listOf(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY)
                            val order = orders.firstOrNull { it in repository.sortOrders }
                            if (order != null) {
                                val tags = if (sourceOrder.indexOf(source) < 2 && repository.supportsGenreDiscovery)
                                    operation(source.name) { repository.getFilterOptions().availableTags }.orEmpty() else emptySet()
                                val wanted = (lookup?.tags.orEmpty() + taste.filter { it.recommendationKind() ==
                                    if (isNovelSource(source)) RecommendationKind.NOVEL else RecommendationKind.MANGA }
                                    .flatMap { it.tags }).map { RecommendationRanker.normalize(it.title) }.toSet()
                                val genre = tags.firstOrNull { RecommendationRanker.normalize(it.title) in wanted }
                                operation(source.name) { repository.discover(order, MangaListFilter(tags = setOfNotNull(genre))) }.orEmpty().take(50).mapTo(local) {
                                    Recommendation(RecommendationTarget.SourceTitle(it), provenance = if (genre != null) RecommendationProvenance.GENRE else
                                        if (order == SortOrder.UPDATED || order == SortOrder.NEWEST) RecommendationProvenance.LATEST else RecommendationProvenance.POPULAR)
                                }
                                if (lookup == null && sourceOrder.indexOf(source) < 2 && SortOrder.UPDATED in repository.sortOrders && order != SortOrder.UPDATED) {
                                    operation(source.name) { repository.discover(SortOrder.UPDATED, MangaListFilter()) }.orEmpty().take(20).mapTo(local) {
                                        Recommendation(RecommendationTarget.SourceTitle(it), provenance = RecommendationProvenance.LATEST)
                                    }
                                }
                            }
                            if (lookup != null && repository.filterCapabilities.isSearchSupported) {
                                (listOf(lookup.title) + lookup.altTitles.sorted()).distinct().take(if (seed == null) 1 else 2).forEach { query ->
                                    operation(source.name) { repository.discover(SortOrder.RELEVANCE.takeIf { it in repository.sortOrders } ?: order ?: repository.defaultSortOrder,
                                        MangaListFilter(query = query.take(120))) }.orEmpty().take(35).mapTo(local) {
                                        Recommendation(RecommendationTarget.SourceTitle(it), provenance = RecommendationProvenance.SEARCH)
                                    }
                                }
                            }
                            // Only adapters with a metadata-only endpoint may enrich; never getDetails().
                            if (repository.supportsLightweightMetadata && sourceOrder.indexOf(source) < 2) local.firstOrNull { it.manga.tags.isEmpty() }?.let { item ->
                                operation(source.name) {
                                    if (repositories.isMetadataRefreshPaused(item.manga)) null
                                    else repository.getLightweightMetadata(item.manga)
                                }?.let { metadata ->
                                    val index = local.indexOf(item)
                                    local[index] = item.copy(target = RecommendationTarget.SourceTitle(metadata.copy(chapters = null)))
                                }
                            }
                        }
                    } finally {
                        // A later filter/metadata failure must not discard an earlier successful discovery.
                        withContext(NonCancellable) { resultLock.withLock { found.addAll(local) } }
                    }
                } }.forEach { it.await() }
                if (settings.publicRecommendations) {
                    val publicSeed = if (seed != null) seed.takeIf {
                        it.source.name in permittedByName &&
                            (!excludeNsfw() || !RecommendationContentPolicy.isNsfw(it, settings.useSeparateSourceContentClasses))
                    } else taste.firstOrNull {
                        settings.recommendationMedia == "ALL" || it.recommendationKind().name == settings.recommendationMedia
                    }
                    if (publicSeed != null) publicProviders.links(publicSeed).take(2).forEach { link ->
                        val source = "public:${link.provider}"
                        failures.capture(source, sourceNames[source] ?: link.provider,
                            onFailure = { sourceFailed(source) }, recordSuccess = false) {
                            publicProviders.related(link) { operationKey, call -> operation(operationKey, call) }
                        }?.let { found.addAll(it) }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failures.failed(RecommendationFailures.REFRESH, "Local recommendations", e, "Recommendation refresh timed out")
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures.failed(RecommendationFailures.REFRESH, "Local recommendations", e) }
        if (settings.useLegacySuggestions || jobGeneration() != generation || cacheScope(request) != scopeKey) return@coroutineScope
        val pool = RecommendationIdentity.deduplicate(found).sortedWith(compareBy<Recommendation> {
            when (it.provenance) {
                RecommendationProvenance.RELATED, RecommendationProvenance.ANILIST, RecommendationProvenance.MANGAUPDATES -> 0
                RecommendationProvenance.CACHE -> 2
                else -> 1
            }
        }.thenBy { it.key }).take(500)
        // Manual ratings are applied when presenting cards; never bake an override into discovery metadata.
        val unique = RecommendationIdentity.deduplicate(publicProviders.withTrustedIdentities(pool, contentOverrides = false))
        // A failed/empty refresh does not destroy the last successful cache.
        val error = failures.encode()
        val content = if (unique.isEmpty() && error != null) old?.payload ?: "[]" else org.json.JSONArray(unique.map { it.key }).toString()
        store.database.withTransaction {
            store.dao.saveCandidates(unique.map { item -> RecommendationCandidateEntity(item.key, RecommendationCodec.encode(item).toString(), now,
                if (item.target is RecommendationTarget.PublicTitle) "public-v1" else sourceVersion(item.manga.source)) })
            store.dao.trim()
            store.dao.result(RecommendationResultEntity(key, content, now, scopeKey, error))
            // Keep the cadence even when the bounded result cache later evicts this context.
            store.dao.cooldown(RecommendationCooldown("refreshed:$key", now + RecommendationRefreshPolicy.INTERVAL))
            store.dao.trimCooldowns(now)
            store.dao.trimResults()
        }
    }

    private suspend fun sourceFailed(source: String) {
        store.database.withTransaction {
            val until = maxOf(store.dao.cooldown(source)?.until ?: 0,
                System.currentTimeMillis() + RecommendationRefreshPolicy.FAILURE_COOLDOWN)
            store.dao.cooldown(RecommendationCooldown(source, until))
        }
    }

    private fun eligible(item: Recommendation, request: RecommendationRequest, dismissed: Set<String>,
        progress: Map<String, List<RecommendationReadingState>>, baselines: Map<String, ChapterBaseline?>): Boolean {
        val seed = (request as? RecommendationRequest.Details)?.seed
        if (RecommendationIdentity.feedbackKeys(item).any { it in dismissed } || (seed != null && (item.manga.source.name == seed.source.name && item.manga.id == seed.id))) return false
        if (seed != null && item.manga.recommendationKind() != seed.recommendationKind()) return false
        if (request == RecommendationRequest.Explore && settings.recommendationMedia != "ALL" && item.manga.recommendationKind().name != settings.recommendationMedia) return false
        if (!RecommendationContentPolicy.allowed(item.manga, excludeNsfw(), settings.useSeparateSourceContentClasses)) return false
        val excluded = settings.suggestionsTagsBlacklist.map(RecommendationRanker::normalize).toSet()
        if (item.manga.tags.any { RecommendationRanker.normalize(it.title) in excluded }) return false
        return RecommendationEligibility.readingAllowed(item, progress[item.key].orEmpty(), baselines,
            settings.recommendationReleaseThreshold, settings.recommendStartedTitles)
    }

    suspend fun availableSources(): List<MangaSource> = sources.getRecommendationSources(settings.isSuggestionsIncludeDisabledSources)
        .map { it.unwrap() }.filter { source ->
            settings.sourceContentPolicy.allows(source) &&
                (!settings.isSuggestionsExcludeNsfw || (if (settings.useSeparateSourceContentClasses)
                    source.contentClass() != org.koitharu.kotatsu.core.model.SourceContentClass.ADULT else !source.isNsfw())) &&
                (settings.recommendationLanguages.isEmpty() || source.getLocale()?.language in settings.recommendationLanguages || source.getLocale() == null)
        }
    private fun excludeNsfw() = settings.isNsfwContentDisabled || settings.isSuggestionsExcludeNsfw
    private fun isNovelSource(source: MangaSource) = source.hasChapterText()
    private fun sourceVersion(source: MangaSource): String = "v1:${BuildConfig.VERSION_CODE}:${source.name}:" + when (source) {
        is org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource -> source.resolved().recommendationVersion
        is org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource -> source.recommendationVersion
        is org.koitharu.kotatsu.core.model.PluginMangaSource -> java.io.File(context.filesDir, "plugins/${source.jarName}")
            .let { "${it.length()}:${it.lastModified()}" }
        else -> "builtin"
    }
    private fun enabled(request: RecommendationRequest) = if (request == RecommendationRequest.Explore) settings.isSuggestionsEnabled else settings.isRelatedMangaEnabled
    fun networkAllowed(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork != null && (!settings.isSuggestionsWiFiOnly || !manager.isActiveNetworkMetered)
    }
    private suspend fun cacheScope(request: RecommendationRequest): String {
        val seed = (request as? RecommendationRequest.Details)?.seed ?: library.getMangaDao().recommendationTaste().first().firstOrNull()
            ?.let { it.manga.toManga(it.tags.toMangaTags(), null) }
        val providers = seed?.let { publicProviders.links(it) }.orEmpty().sortedBy { it.provider }
        return discoveryFingerprint() + ":" + providers.joinToString { "${it.provider}:${it.identifier}:${it.kind}" }
    }
    private fun discoveryFingerprint() = "v1:$sourceGeneration:${settings.publicRecommendations}:" +
        "${settings.isNsfwContentDisabled}:${settings.isSuggestionsExcludeNsfw}:${settings.isSuggestionsIncludeDisabledSources}:" +
        "${settings.recommendationMedia}:${settings.recommendationLanguages.sorted()}:${settings.useAndroidInstalledExtensions}:${settings.suggestionsTagsBlacklist.sorted()}:" +
        "${settings.isAllSourcesEnabled}:${settings.isBrokenSourcesHidden}:${settings.sourceContentPolicy}"
    private fun jobGeneration() = discoveryFingerprint() +
        ":${settings.useLegacySuggestions}:${settings.isSuggestionsEnabled}:${settings.isRelatedMangaEnabled}:${settings.isSuggestionsWiFiOnly}:" +
        if (settings.publicRecommendations) identityGeneration else ""


    companion object {
        val KEYS = arrayOf(AppSettings.KEY_SUGGESTIONS_LEGACY, AppSettings.KEY_SUGGESTIONS_DIVERSITY, AppSettings.KEY_SUGGESTIONS_PUBLIC,
            AppSettings.KEY_SUGGESTIONS_STARTED, AppSettings.KEY_SUGGESTIONS_THRESHOLD, AppSettings.KEY_SUGGESTIONS_MEDIA,
            AppSettings.KEY_SUGGESTIONS_LANGUAGES, AppSettings.KEY_SUGGESTIONS, AppSettings.KEY_RELATED_MANGA,
            AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW, AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, AppSettings.KEY_DISABLE_NSFW,
            AppSettings.KEY_USE_ANDROID_EXTENSIONS, AppSettings.KEY_SUGGESTIONS_DISABLED_SOURCES, AppSettings.KEY_SUGGESTIONS_WIFI_ONLY,
            AppSettings.KEY_SOURCES_ENABLED_ALL, AppSettings.KEY_SOURCES_HIDE_BROKEN,
            AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED, AppSettings.KEY_SOURCE_CONTENT_CLASSES)
        fun contextKey(request: RecommendationRequest) = when (request) {
            RecommendationRequest.Explore -> "explore"
            is RecommendationRequest.Details -> "details:${request.seed.source.name}:${request.seed.id}"
        }
    }
}
