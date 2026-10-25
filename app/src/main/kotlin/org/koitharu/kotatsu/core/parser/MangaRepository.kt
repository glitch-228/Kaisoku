package org.koitharu.kotatsu.core.parser

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.MangaSourceRegistry
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.UnresolvedMangaSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.tsuki.TsukiLoaderContext
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.parser.external.ExternalMangaRepository
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceManager
import org.koitharu.kotatsu.core.parser.lnreader.UnresolvedLnReaderSource
import org.koitharu.kotatsu.core.parser.lnreader.toMangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonNovelRepository
import org.koitharu.kotatsu.browsersource.data.BrowserSourceMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.customsource.domain.CustomMangaSource
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.lang.ref.WeakReference
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal fun findBuiltInParserSource(sourceName: String): MangaParserSource? =
    MangaParserSource.entries.firstOrNull { it.name == sourceName }

interface MangaRepository {

    val source: MangaSource

    val sortOrders: Set<SortOrder>

    var defaultSortOrder: SortOrder

    val filterCapabilities: MangaListFilterCapabilities

    suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga>

    suspend fun getDetails(manga: Manga): Manga

    /** A deliberate, one-shot remote refresh. Automatic calls, including WRITE_ONLY, use getDetails. */
    suspend fun refreshDetails(manga: Manga): Manga = getDetails(manga)

    suspend fun getPages(chapter: MangaChapter): List<MangaPage>

    /** Some extension chapter URLs are relative to a title rather than unique within a source. */
    suspend fun getPages(manga: Manga, chapter: MangaChapter): List<MangaPage> = getPages(chapter)

    /** Optional website link only; implementations must not fetch chapter pages or text to resolve it. */
    suspend fun getChapterUrl(manga: Manga, chapter: MangaChapter): String? = null

    suspend fun getPageUrl(page: MangaPage): String

    suspend fun getFilterOptions(): MangaListFilterOptions

    suspend fun getRelated(seed: Manga): List<Manga>

    /** Recommendation calls must not mutate browse pagination or the user's selected filters. */
    suspend fun discover(order: SortOrder, filter: MangaListFilter): List<Manga> = emptyList()

    /** Optional metadata-only endpoint. Never fall back to full chapter/page retrieval here. */
    val supportsLightweightMetadata: Boolean get() = false

    val supportsRelatedDiscovery: Boolean get() = false

    val supportsGenreDiscovery: Boolean get() = false
    suspend fun getLightweightMetadata(manga: Manga): Manga? = null

    /** Source-defined relationships only; generic keyword fallback is bounded by the engine. */
    suspend fun discoverRelated(seed: Manga): List<Manga> = emptyList()

    fun getImageClient(): OkHttpClient? = null

    fun createPageRequest(pageUrl: String, page: MangaPage): Request = Request.Builder()
        .url(pageUrl)
        .get()
        .header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
        .cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
        .tag(MangaSource::class.java, page.source)
        .build()

    /**
     * Builds the page Request the downloader will fire for [page]; uses [getPageUrl] unless this
     * repository has a cheaper in-memory path (e.g. a cached `Page` for a Mihon extension).
     */
    /**
     * Resolves and fetches the canonical page bytes through the image-proxy connector (when one is
     * set up), mirroring `ImageProxyInterceptor.interceptPageRequest`.
     */
    suspend fun getPageResponse(
        page: MangaPage,
        okHttp: OkHttpClient,
        imageProxyInterceptor: org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor,
    ): Response {
        return imageProxyInterceptor.interceptPageRequest(getPageRequest(page), okHttp)
    }

    suspend fun getPageRequest(page: MangaPage): Request = createPageRequest(getPageUrl(page), page)

    /**
     * Source-defined filter/search UI descriptors surfaced at the list root (e.g. the TsukiMix-style
     * `filter` map for Mihon extensions, or `SetupFields` for Content-Provider plugins).
     * `null` when the source doesn't declare any.
     */
    suspend fun getExternalFilters(): Any? = null

    /** Returns whether source-defined filters currently differ from their defaults. */
    fun hasExternalFiltersApplied(): Boolean = false

    /** Restores source-defined filters to their extension-provided defaults. */
    fun resetExternalFilters(): Boolean = false

    suspend fun find(manga: Manga): Manga? {
        val list = getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
        return list.find { x -> x.id == manga.id }
    }

    @Singleton
    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
        private val localMangaRepository: LocalMangaRepository,
        private val loaderContext: MangaLoaderContext,
        private val contentCache: MemoryContentCache,
        private val mirrorSwitcher: MirrorSwitcher,
        private val mihonExtensionManager: MihonExtensionManager,
        private val lnReaderSourceManager: LnReaderSourceManager,
        private val appSettings: AppSettings,
        private val recommendationStore: org.koitharu.kotatsu.suggestions.local.RecommendationStore,
        private val metadataRefreshSettings: MetadataRefreshSettings,
        private val savedMangaMetadata: SavedMangaMetadata,
        private val tsukiLoaderContext: dagger.Lazy<TsukiLoaderContext>,
        private val remoteLibraries: org.koitharu.kotatsu.remote.library.RemoteLibraryManager,
    ) {

        private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()

        @AnyThread
        fun create(source: MangaSource): MangaRepository {
            when (source) {
                is MangaSourceInfo -> return create(source.mangaSource)
                LocalMangaSource -> return localMangaRepository
                UnknownMangaSource -> return emptyRepository(source)
            }
            if (source is org.koitharu.kotatsu.remote.library.RemoteLibrarySource) {
                return if (remoteLibraries.connection(source.connectionId)?.enabled == true) {
                    SavedMetadataMangaRepository(
                        remoteLibraries.repository(source.resolved()),
                        { seed -> if (isMetadataRefreshPaused(seed)) savedMangaMetadata.getDetails(seed) else null },
                        { recommendationStore.recordChapters(it) },
                    )
                } else emptyRepository(source)
            }
            val resolved = resolveRepositorySource(source)
            if (resolved !== source) return create(resolved)
            if (source is PluginMangaSource && !isInstalledPlugin(source)) {
                return synchronized(cache) {
                    cache.remove(source)
                    contentCache.clear(source)
                    emptyRepository(source)
                }
            }
            if (source is MihonMangaSource) {
                // Resolve before consulting the repository cache: ignored, removed or replaced
                // packages must not keep being served by an obsolete extension instance.
                val loaded = mihonExtensionManager.resolve(source)
                return synchronized(cache) {
                    if (loaded?.catalogueSource == null) {
                        cache.remove(source)
                        contentCache.clear(source)
                        emptyRepository(source)
                    } else {
                        val current = cache[source]?.get() as? MihonMangaRepository
                        if (current?.usesSource(loaded) == true) {
                            current
                        } else {
                            contentCache.clear(source)
                            createMihonRepository(loaded).also {
                                attachEvidence(it)
                                cache[source] = WeakReference(it)
                            }
                        }
                    }
                }
            }
            return synchronized(cache) {
                if (source is PluginMangaSource) {
                    // Registry replacement changes enum identity even when the JAR/name stay equal.
                    cache.keys.filter { it is PluginMangaSource && it.name == source.name && it !== source }
                        .forEach {
                            cache.remove(it)
                            contentCache.clear(it)
                        }
                }
                cache[source]?.get()?.let { current ->
                    if (current !is EmptyMangaRepository || !hasRestoredAdapter(source)) return@synchronized current
                    cache.remove(source)
                    contentCache.clear(source)
                }
                val repository = createRepository(source) ?: emptyRepository(source)
                attachEvidence(repository)
                cache[source] = WeakReference(repository)
                repository
            }
        }

        fun isMetadataRefreshPaused(manga: Manga): Boolean {
            if (metadataRefreshSettings.isSavedMetadataOnly(manga.id)) return true
            if (!metadataRefreshSettings.useSavedMetadataForUnavailableSources) return false
            val source = resolveRepositorySource(manga.source.unwrap())
            if (source == LocalMangaSource || isDisabledAdapter(source)) return false
            return when (source) {
                is MangaParserSource -> source.isBroken || create(source) is EmptyMangaRepository
                is PluginMangaSource -> source.isBroken || !isInstalledPlugin(source) || create(source) is EmptyMangaRepository
                else -> create(source) is EmptyMangaRepository
            }
        }

        private fun attachEvidence(repository: MangaRepository) {
            val guard: suspend (Manga) -> Manga? = { seed ->
                if (isMetadataRefreshPaused(seed)) savedMangaMetadata.getDetails(seed) else null
            }
            when (repository) {
                is CachingMangaRepository -> {
                    repository.beforeDetails = guard
                    repository.onFreshDetails = { recommendationStore.recordChapters(it) }
                }
                is EmptyMangaRepository -> repository.beforeDetails = guard
            }
        }

        private fun emptyRepository(source: MangaSource): EmptyMangaRepository =
            EmptyMangaRepository(source).also(::attachEvidence)

        private fun resolveRepositorySource(source: MangaSource): MangaSource = when (source) {
            is UnresolvedLnReaderSource -> lnReaderSourceManager.peekEntity(source.pluginId)?.toMangaSource() ?: source
            is PluginMangaSource -> MangaSourceRegistry.entries.firstOrNull { it.name == source.name } ?: source
            is UnresolvedMangaSource -> MangaSourceRegistry.entries.firstOrNull { it.name == source.name } ?: source
            else -> source
        }

        private fun hasRestoredAdapter(source: MangaSource): Boolean = when (source) {
            is LnReaderMangaSource -> lnReaderSourceManager.peekEntity(source.pluginId) != null
            is PluginMangaSource -> isInstalledPlugin(source)
            is ExternalMangaSource -> source.isAvailable(context)
            else -> false
        }

        private fun isInstalledPlugin(source: PluginMangaSource): Boolean =
            MangaSourceRegistry.entries.any { it.name == source.name } &&
                File(PluginFileLoader.pluginsDir(context), source.jarName).isFile

        private fun isDisabledAdapter(source: MangaSource): Boolean = when (source) {
            is MihonMangaSource -> !appSettings.useAndroidInstalledExtensions &&
                MihonExtensionPackageUtil.getPackageInfoOrNull(context.packageManager, source.packageName)
                    ?.let(MihonExtensionPackageUtil::isMihonExtension) == true
            is ExternalMangaSource -> context.packageManager.resolveContentProvider(
                source.authority, PackageManager.MATCH_DISABLED_COMPONENTS,
            )?.let { it.packageName == source.packageName && (!it.isEnabled || !it.applicationInfo.enabled) } == true
            else -> false
        }

        private fun createRepository(source: MangaSource): MangaRepository? = when (source) {
            // Constructing a built-in parser can still throw (a malformed source, a removed symbol, or
            // an unavailable source picked up from an old backup). Contain it so the source degrades to
            // an empty one instead of taking down the whole app.
            is MangaParserSource -> createBuiltInRepository(source)

            is PluginMangaSource -> createPluginRepository(source)

            TestMangaSource -> TestMangaRepository(
                loaderContext = loaderContext,
                cache = contentCache,
            )

            is ExternalMangaSource -> if (source.isAvailable(context)) {
                ExternalMangaRepository(
                    contentResolver = context.contentResolver,
                    source = source,
                    cache = contentCache,
                    appSettings = appSettings,
                )
            } else {
                emptyRepository(source)
            }

            is MihonMangaSource -> mihonExtensionManager.resolve(source)?.let {
                if (it.catalogueSource != null) createMihonRepository(it) else null
            } ?: emptyRepository(source)

            // Installed LNReader novel plugin: resolve the entity from the manager's
            // registry-backed snapshot; a missing plugin degrades to an empty source.
            is LnReaderMangaSource -> runCatchingCancellable {
                lnReaderSourceManager.peekEntity(source.pluginId)?.let { entity ->
                    LnReaderMangaRepository(
                        entity = entity,
                        httpClient = lnReaderSourceManager.httpClient,
                        cache = contentCache,
                        diskCacheDir = java.io.File(context.cacheDir, "lnreader_chapters"),
                        storage = org.koitharu.kotatsu.core.parser.lnreader.LNReaderStorage(
                            context.getSharedPreferences("lnreader_" + android.net.Uri.encode(entity.pluginId), Context.MODE_PRIVATE),
                        ),
                        appSettings = appSettings,
                    )
                }
            }.getOrNull() ?: emptyRepository(source)

            // User-defined in-app browser source: pages are stashed by BrowserSourceActivity and served
            // back from the page store (only BROWSER_SOURCE custom sources exist in this build).
            is CustomMangaSource -> BrowserSourceMangaRepository(source)

            else -> null
        }

        private fun createMihonRepository(loaded: MihonExtensionManager.LoadedSource): MihonMangaRepository =
            org.koitharu.kotatsu.core.parser.mihon.mihonExtensionCall {
                if (loaded.wrapper.isNovelSource) MihonNovelRepository(loaded, contentCache, appSettings)
                else MihonMangaRepository(loaded, contentCache, appSettings)
            }

        private fun createBuiltInRepository(source: MangaParserSource): ParserMangaRepository? =
            runCatchingCancellable {
                ParserMangaRepository(
                    parser = loaderContext.newParserInstance(source),
                    cache = contentCache,
                    mirrorSwitcher = mirrorSwitcher,
                    appSettings = appSettings,
                )
            }.onFailure { it.printStackTraceDebug() }.getOrNull()

        private fun createPluginRepository(source: PluginMangaSource): MangaRepository? {
            // Plugins carry their own parser classes but share MangaLoaderContext with the host. Older
            // Android runtimes can dispatch a virtual call to the wrong slot after that abstract host
            // API gains methods (issue #12 called getConfig() as evaluateJs() with a null continuation).
            // Resolve domain once now so an incompatible copy is detected before it reaches any screen.
            val plugin = runCatchingCancellable {
                PluginMangaRepository(
                    loadedParser = DynamicParserManager.createParser(
                        source = source,
                        loaderContext = loaderContext,
                        tsukiContext = if (source.isTsuki) tsukiLoaderContext.get() else null,
                        appContext = context,
                    ),
                    settings = SourceSettings(context, source),
                    cache = contentCache,
                    appSettings = appSettings,
                    mirrorSwitcher = mirrorSwitcher,
                )
            }.onFailure { it.printStackTraceDebug() }.getOrNull()
            val compatiblePlugin = plugin?.let { repository ->
                runCatchingCancellable {
                    repository.domain.takeIf(String::isNotBlank)
                }.onFailure { it.printStackTraceDebug() }.getOrNull()?.let { repository }
            }
            if (compatiblePlugin != null) {
                return compatiblePlugin
            }
            val builtInSource = findBuiltInParserSource(source.sourceName) ?: return null
            return createBuiltInRepository(builtInSource)
        }
    }
}
