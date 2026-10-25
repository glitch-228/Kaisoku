package org.koitharu.kotatsu.explore.data

import org.koitharu.kotatsu.core.parser.hasChapterText

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.MangaSourcesDao
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.MangaSourceRegistry
import org.koitharu.kotatsu.core.model.NsfwOverridesLoader
import org.koitharu.kotatsu.core.model.NsfwSourceOverrides
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.intrinsicIsNsfw
import org.koitharu.kotatsu.core.model.SourceContentPolicy
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceManager
import org.koitharu.kotatsu.core.parser.lnreader.toMangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonPrivateExtensionStore
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.community.data.CommunityRepository
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import org.koitharu.kotatsu.remote.library.RemoteLibraryManager
import org.koitharu.kotatsu.remote.library.RemoteLibrarySource

@Singleton
class MangaSourcesRepository @Inject constructor(
    @LocalizedAppContext private val context: Context,
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val mihonExtensionManager: MihonExtensionManager,
    private val nsfwOverridesLoader: NsfwOverridesLoader,
    private val lnReaderSourceManager: LnReaderSourceManager,
    private val community: CommunityRepository,
    private val remoteLibraries: RemoteLibraryManager,
) {

    data class ParserSourceSnapshot(
        val source: MangaSource,
        val title: String,
        val locale: String?,
        val contentType: ContentType,
        val isEnabled: Boolean,
        val addedIn: Int,
        val isBroken: Boolean,
        val isNsfw: Boolean,
        val isMihon: Boolean,
        val isPlugin: Boolean,
        val isNovel: Boolean = false,
    )

    private val isNewSourcesAssimilated = AtomicBoolean(false)
    private var assimilatedPluginVersion = -1
    private val dao: MangaSourcesDao
        get() = db.getSourcesDao()

    val allMangaSources: Set<MangaParserSource> = Collections.unmodifiableSet(
        EnumSet.noneOf<MangaParserSource>(MangaParserSource::class.java).also {
            MangaParserSource.entries.filterNotTo(it, MangaParserSource::isBroken)
        }
    )

    suspend fun getEnabledSources(): List<MangaSource> {
        val mihonSources = getMihonSources()
        val pluginSources = getPluginSources()
        val lnReaderSources = getLnReaderSources()
        assimilateAvailableSources(mihonSources)
        val order = settings.sourcesSortOrder
        val enabled = dao.findAll(!settings.isAllSourcesEnabled, order).toSources(
            contentPolicy = settings.sourceContentPolicy,
            sortOrder = order,
            hideBrokenSources = settings.isBrokenSourcesHidden,
            mihonSources = mihonSources.associateBy { it.name },
            pluginSources = pluginSources.associateBy { it.name },
            lnReaderSources = lnReaderSources.associateBy { it.name },
        )
        val external = getExternalSources().filterContent(settings.sourceContentPolicy)
        return ArrayList<MangaSourceInfo>(enabled.size + external.size).also { list ->
            external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
            list.addAll(enabled)
        }
    }

    suspend fun getRecommendationSources(includeDisabled: Boolean): List<MangaSource> =
        (if (includeDisabled) (getEnabledSources() + getDisabledSources()).distinctBy { it.name }
        else getEnabledSources()).filter {
            it.unwrap() !is RemoteLibrarySource && (it.unwrap() as? MihonMangaSource)?.resolved()?.isCatalogue != false
        }

    fun observeRecommendationVersion(): Flow<String> = combine(
        dao.observeAll(), registryUpdates, observeMihonSources(), db.getLnReaderSourcesDao().observeAll(),
    ) { entries, _, mihon, novels ->
        entries.sortedBy { it.source }.joinToString { "${it.source}:${it.isEnabled}:${it.nsfwOverride}" } + ":" +
            getPluginSources().sortedBy { it.name }.joinToString { source ->
                java.io.File(context.filesDir, "plugins/${source.jarName}").let { "${source.name}:${it.length()}:${it.lastModified()}" }
            } + ":" +
            mihon.sortedBy { it.name }.joinToString { "${it.name}:${it.recommendationVersion}" } +
            novels.sortedBy { it.pluginId }.joinToString { "${it.pluginId}:${it.version}:${it.jsCode.hashCode()}" }
    }.map { signature ->
        java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.distinctUntilChanged()

    suspend fun getPinnedSources(): Set<MangaSource> {
        val mihonSources = getMihonSources()
        val pluginSources = getPluginSources()
        val lnReaderByName = getLnReaderSources().associateBy { it.name }
        assimilateAvailableSources(mihonSources)
        val contentPolicy = settings.sourceContentPolicy
        val hideBroken = settings.isBrokenSourcesHidden
        val mihonByName = mihonSources.associateBy { it.name }
        val pluginByName = pluginSources.associateBy { it.name }
        return buildSet {
            addAll(getExternalSources().filterContent(contentPolicy))
            addAll(dao.findAllPinned().mapNotNullToSet {
                it.source.toInstalledSourceOrNull(mihonByName, pluginByName, lnReaderByName)?.takeUnless { x ->
                    (!contentPolicy.allows(x)) || (hideBroken && x.isBrokenSource())
                }
            })
        }
    }

    suspend fun getTopSources(limit: Int): List<MangaSource> {
        val mihonSources = getMihonSources()
        val pluginSources = getPluginSources()
        val lnReaderSources = getLnReaderSources()
        assimilateAvailableSources(mihonSources)
        return dao.findLastUsed(limit, enabledOnly = !settings.isAllSourcesEnabled).toSources(
            contentPolicy = settings.sourceContentPolicy,
            sortOrder = null,
            hideBrokenSources = settings.isBrokenSourcesHidden,
            mihonSources = mihonSources.associateBy { it.name },
            pluginSources = pluginSources.associateBy { it.name },
            lnReaderSources = lnReaderSources.associateBy { it.name },
        )
    }

    suspend fun getDisabledSources(): Set<MangaSource> {
        val mihonSources = getMihonSources()
        assimilateAvailableSources(mihonSources)
        if (settings.isAllSourcesEnabled) {
            return emptySet()
        }
        val contentPolicy = settings.sourceContentPolicy
        val hideBroken = settings.isBrokenSourcesHidden
        val pluginSources = getPluginSources()
        val lnReaderSources = getLnReaderSources()
        val result = LinkedHashSet<MangaSource>(allMangaSources.size + mihonSources.size + pluginSources.size)
        allMangaSources.filterNotTo(result) { source ->
            (!contentPolicy.allows(source)) || (hideBroken && source.isBroken)
        }
        mihonSources.filterNotTo(result) { !contentPolicy.allows(it) }
        pluginSources.filterNotTo(result) { source ->
            (!contentPolicy.allows(source)) || (hideBroken && source.isBroken)
        }
        lnReaderSources.filterNotTo(result) { !contentPolicy.allows(it) }
        remoteLibraries.sources().filterNotTo(result) { !contentPolicy.allows(it) }
        val enabled = dao.findAllEnabledNames()
        result.removeAll { it.name in enabled }
        return result
    }

    suspend fun queryParserSources(
        isDisabledOnly: Boolean,
        isNewOnly: Boolean,
        excludeBroken: Boolean,
        types: Set<ContentType>,
        query: String?,
        locale: String?,
        includeMihon: Boolean,
        excludeMihon: Boolean,
        includePlugins: Boolean,
        excludePlugins: Boolean,
        includeNovel: Boolean = false,
        excludeNovel: Boolean = false,
        sortOrder: SourcesSortOrder?,
        snapshot: List<ParserSourceSnapshot>? = null,
    ): List<MangaSource> {
        nsfwOverridesLoader.ensureLoaded()
        val entries = snapshot ?: getParserSourcesSnapshot()
        val coroutineContext = currentCoroutineContext()
        val hideBrokenSources = settings.isBrokenSourcesHidden
        val excludeEnabledSources = isDisabledOnly && !settings.isAllSourcesEnabled
        val effectiveQuery = query?.takeIf { it.isNotBlank() }
        val hasSourceKindIncludes = includeMihon || includePlugins || includeNovel
        val sources = ArrayList<MangaSource>(entries.size)
        for ((index, entry) in entries.withIndex()) {
            if (index % 32 == 0) {
                coroutineContext.ensureActive()
            }
            if (!settings.sourceContentPolicy.allows(entry.source)) {
                continue
            }
            if (hideBrokenSources && entry.isBroken) {
                continue
            }
            if (excludeEnabledSources && entry.isEnabled) {
                continue
            }
            if (isNewOnly && entry.addedIn != BuildConfig.VERSION_CODE) {
                continue
            }
            if (locale != null && entry.locale != locale) {
                continue
            }
            if (excludeMihon && entry.isMihon) {
                continue
            }
            if (excludePlugins && entry.isPlugin) {
                continue
            }
            if (excludeNovel && entry.isNovel) {
                continue
            }
            if (hasSourceKindIncludes &&
                !((includeMihon && entry.isMihon) || (includePlugins && entry.isPlugin) || (includeNovel && entry.isNovel))
            ) {
                continue
            }
            if (excludeBroken && !hideBrokenSources && entry.isBroken) {
                continue
            }
            if (types.isNotEmpty() && entry.contentType !in types) {
                continue
            }
            if (effectiveQuery != null &&
                !entry.title.contains(effectiveQuery, ignoreCase = true) &&
                !entry.source.name.contains(effectiveQuery, ignoreCase = true)
            ) {
                continue
            }
            sources += entry.source
        }
        if (sortOrder != SourcesSortOrder.ALPHABETIC) {
            // Snapshot order is pre-sorted alphabetically for catalog use.
            return sources
        }
        return sources
    }

    suspend fun getParserSourcesSnapshot(): List<ParserSourceSnapshot> {
        val mihonSources = getMihonSources()
        val pluginSources = getPluginSources()
        val lnReaderSources = getLnReaderSources()
        assimilateAvailableSources(mihonSources)
        val mihonByName = mihonSources.associateBy { it.name }
        val pluginByName = pluginSources.associateBy { it.name }
        val lnReaderByName = lnReaderSources.associateBy { it.name }
        return dao.findAll().mapNotNull { entity ->
            val source = entity.source.toInstalledSourceOrNull(mihonByName, pluginByName, lnReaderByName)
                ?: return@mapNotNull null
            if (source is MangaParserSource && source !in allMangaSources) {
                return@mapNotNull null
            }
            val locale = when (source) {
                is MangaParserSource -> source.locale
                is PluginMangaSource -> source.locale
                is MihonMangaSource -> source.resolved().locale
                is LnReaderMangaSource -> source.lang
                else -> null
            }
            ParserSourceSnapshot(
                source = source,
                title = source.getTitle(context),
                locale = locale,
                contentType = when (source) {
                    is MangaParserSource -> source.contentType
                    is PluginMangaSource -> source.contentType
                    is MihonMangaSource -> if (source.hasChapterText()) ContentType.NOVEL else ContentType.MANGA
                    is LnReaderMangaSource -> ContentType.NOVEL
                    else -> ContentType.OTHER
                },
                isEnabled = entity.isEnabled,
                addedIn = entity.addedIn,
                isBroken = source.isBrokenSource(),
                isNsfw = source.isNsfw(),
            isMihon = source is MihonMangaSource,
            isPlugin = source is PluginMangaSource,
            isNovel = source.hasChapterText(),
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun observeIsEnabled(source: MangaSource): Flow<Boolean> {
        return dao.observeIsEnabled(source.name).onStart { ensureSourceTracked(source) }
    }

    fun observeIsNsfw(source: MangaSource): Flow<Boolean> {
        val name = source.unwrap().name
        return dao.observeNsfwOverride(name).map { it?.let { value -> value != 0 } ?: source.intrinsicIsNsfw() }
            .onStart { ensureSourceTracked(source) }
    }

    fun observeEnabledSourcesCount(): Flow<Int> {
        return combine(
            observeSourceContentPolicy(),
            observeHideBrokenSources(),
            observeAllEnabled(),
            observeMihonSources(),
            registryUpdates,
        ) { contentPolicy, hideBroken, isAllSourcesEnabled, mihonSources, _ ->
            assimilateAvailableSources(mihonSources)
            val mihonByName = mihonSources.associateBy { it.name }
            val pluginByName = getPluginSources().associateBy { it.name }
            val lnReaderByName = getLnReaderSources().associateBy { it.name }
            dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL).map { sources ->
                sources.count {
                    it.source.toInstalledSourceOrNull(mihonByName, pluginByName, lnReaderByName)?.let { source ->
                        contentPolicy.allows(source) &&
                            (!hideBroken || !source.isBrokenSource())
                    } == true
                }
            }
        }.flattenLatest().distinctUntilChanged().onStart { assimilateAvailableSources() }
    }

    fun observeAvailableSourcesCount(): Flow<Int> {
        return combine(
            observeSourceContentPolicy(),
            observeHideBrokenSources(),
            observeAllEnabled(),
            observeMihonSources(),
            registryUpdates,
        ) { contentPolicy, hideBroken, isAllSourcesEnabled, mihonSources, _ ->
            if (isAllSourcesEnabled) {
                return@combine flowOf(0)
            }
            assimilateAvailableSources(mihonSources)
            val pluginSources = getPluginSources()
            val lnReaderSources = getLnReaderSources()
            val available = LinkedHashMap<String, MangaSource>(
                allMangaSources.size + mihonSources.size + pluginSources.size + lnReaderSources.size,
            )
            allMangaSources.forEach { source ->
                if (contentPolicy.allows(source) && (!hideBroken || !source.isBroken)) {
                    available[source.name] = source
                }
            }
            mihonSources.forEach { source ->
                if (contentPolicy.allows(source)) {
                    available[source.name] = source
                }
            }
            pluginSources.forEach { source ->
                if (contentPolicy.allows(source) && (!hideBroken || !source.isBroken)) {
                    available[source.name] = source
                }
            }
            lnReaderSources.forEach { source ->
                if (contentPolicy.allows(source)) {
                    available[source.name] = source
                }
            }
            remoteLibraries.sources().forEach { source ->
                if (contentPolicy.allows(source)) available[source.name] = source
            }
            dao.observeAll(enabledOnly = true, order = SourcesSortOrder.MANUAL).map { enabledSources ->
                val enabled = enabledSources.mapTo(HashSet(enabledSources.size)) { it.source }
                available.count { (name, _) -> name !in enabled }
            }
        }.flattenLatest().distinctUntilChanged().onStart { assimilateAvailableSources() }
    }

    fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = registryUpdates.flatMapLatest {
        combine(
            observeSourceContentPolicy(),
            observeHideBrokenSources(),
            observeAllEnabled(),
            observeSortOrder(),
            observeMihonSources(),
        ) { contentPolicy, hideBroken, allEnabled, order, mihonSources ->
            assimilateAvailableSources(mihonSources)
            val pluginByName = getPluginSources().associateBy { source -> source.name }
            val lnReaderByName = getLnReaderSources().associateBy { source -> source.name }
            dao.observeAll(!allEnabled, order).map {
                contentPolicy to it.toSources(
                    contentPolicy = contentPolicy,
                    sortOrder = order,
                    hideBrokenSources = hideBroken,
                    mihonSources = mihonSources.associateBy { source -> source.name },
                    pluginSources = pluginByName,
                    lnReaderSources = lnReaderByName,
                )
            }
        }.flattenLatest()
    }
        .onStart { assimilateAvailableSources() }
        .combine(observeExternalSources()) { (contentPolicy, enabled), external ->
            val filteredExternal = external.filterContent(contentPolicy)
            val list = ArrayList<MangaSourceInfo>(enabled.size + filteredExternal.size)
            filteredExternal.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
            list.addAll(enabled)
            list
        }
        .mapLatest { sources ->
            if (settings.sourcesSortOrder == SourcesSortOrder.POPULARITY) community.rankSources(sources) else sources
        }

    fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = registryUpdates.flatMapLatest {
        dao.observeAll().map { entities ->
            val pluginByName = getPluginSources().associateBy { source -> source.name }
            val lnReaderByName = getLnReaderSources().associateBy { source -> source.name }
            val result = ArrayList<Pair<MangaSource, Boolean>>(entities.size)
            for (entity in entities) {
                val source = entity.source.toParserOrPluginSourceOrNull(pluginByName)
                    ?: lnReaderByName[entity.source]
                    ?: entity.source.toRemoteLibrarySourceOrNull()
                    ?: continue
                if (source is PluginMangaSource || source is LnReaderMangaSource || source is RemoteLibrarySource || source in allMangaSources) {
                    result.add(source to entity.isEnabled)
                }
            }
            result
        }
    }.onStart { assimilateAvailableSources() }

    suspend fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
        setSourcesEnabledImpl(sources, isEnabled)
        return ReversibleHandle {
            setSourcesEnabledImpl(sources, !isEnabled)
        }
    }

    suspend fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
        val mihonSources = getMihonSources()
        val pluginSources = getPluginSources()
        val otherSources = getLnReaderSources() + remoteLibraries.sources()
        db.withTransaction {
            assimilateAvailableSources(mihonSources)
            for (s in allMangaSources) {
                dao.setEnabled(s.name, s in sources)
            }
            for (s in mihonSources) {
                dao.setEnabled(s.name, s in sources)
            }
            for (s in pluginSources) {
                dao.setEnabled(s.name, s in sources)
            }
            for (s in otherSources) {
                dao.setEnabled(s.name, s in sources)
            }
        }
    }

    suspend fun disableAllSources() {
        db.withTransaction {
            assimilateAvailableSources()
            dao.disableAllSources()
        }
    }

    suspend fun setPositions(sources: List<MangaSource>) {
        db.withTransaction {
            for ((index, item) in sources.withIndex()) {
                dao.setSortKey(item.name, index)
            }
        }
    }

    fun observeHasNewSources(): Flow<Boolean> = observeSourceContentPolicy().map { contentPolicy ->
        val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(
            contentPolicy = contentPolicy,
            sortOrder = null,
            hideBrokenSources = settings.isBrokenSourcesHidden,
            mihonSources = emptyMap(),
            pluginSources = getPluginSources().associateBy { it.name },
        )
        sources.isNotEmpty() && sources.size != allMangaSources.size
    }.onStart { assimilateNewParserSources() }

    fun observeHasNewSourcesForBadge(): Flow<Boolean> = combine(
        settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
        observeSourceContentPolicy(),
        observeHideBrokenSources(),
    ) { version, contentPolicy, hideBroken ->
        if (version < BuildConfig.VERSION_CODE) {
            val sources = dao.findAllFromVersion(version).toSources(
                contentPolicy = contentPolicy,
                sortOrder = null,
                hideBrokenSources = hideBroken,
                mihonSources = emptyMap(),
                pluginSources = getPluginSources().associateBy { it.name },
            )
            sources.isNotEmpty()
        } else {
            false
        }
    }.onStart { assimilateNewParserSources() }

    fun clearNewSourcesBadge() {
        settings.sourcesVersion = BuildConfig.VERSION_CODE
    }

    fun observeInstalledMihonSources(): Flow<List<MihonMangaSource>> = observeMihonSources()

    suspend fun refreshInstalledMihonSources(): Boolean {
        mihonExtensionManager.invalidate()
        return assimilateInstalledMihonSources(getMihonSources())
    }

    fun observeInstalledPluginSources(): Flow<List<PluginMangaSource>> =
        registryUpdates.map { getPluginSources() }

    suspend fun refreshInstalledPluginSources(): Boolean =
        assimilateInstalledPluginSources(getPluginSources())

    fun observeInstalledLnReaderSources(): Flow<List<LnReaderMangaSource>> =
        db.getLnReaderSourcesDao().observeAll().map { entities ->
            lnReaderSourceManager.reload()
            entities.map { it.toMangaSource() }
        }

    suspend fun refreshInstalledLnReaderSources(): Boolean {
        lnReaderSourceManager.reload()
        return assimilateInstalledLnReaderSources(getLnReaderSources())
    }

    private suspend fun getLnReaderSources(): List<LnReaderMangaSource> =
        lnReaderSourceManager.getInstalledSources()

    private suspend fun assimilateAvailableSources(): Boolean {
        return assimilateAvailableSources(getMihonSources())
    }

    private suspend fun assimilateAvailableSources(mihonSources: List<MihonMangaSource>): Boolean {
        nsfwOverridesLoader.ensureLoaded()
        val parsersUpdated = assimilateNewParserSources()
        val mihonUpdated = assimilateInstalledMihonSources(mihonSources)
        val pluginUpdated = assimilateInstalledPluginSources(getPluginSources())
        lnReaderSourceManager.reload()
        val lnReaderUpdated = assimilateInstalledLnReaderSources(getLnReaderSources())
        val librariesUpdated = assimilateRemoteLibraries(remoteLibraries.sources())
        return parsersUpdated || mihonUpdated || pluginUpdated || lnReaderUpdated || librariesUpdated
    }

    private suspend fun assimilateRemoteLibraries(sources: List<RemoteLibrarySource>): Boolean {
        if (sources.isEmpty()) return false
        val known = dao.findAll().mapTo(HashSet()) { it.source }
        val missing = sources.filterNot { it.name in known }
        if (missing.isEmpty()) return false
        var sortKey = dao.getMaxSortKey()
        dao.insertIfAbsent(missing.map { source ->
            MangaSourceEntity(source.name, settings.isAllSourcesEnabled, ++sortKey, BuildConfig.VERSION_CODE,
                0, false, CloudFlareHelper.PROTECTION_NOT_DETECTED)
        })
        return true
    }

    private suspend fun assimilateInstalledLnReaderSources(lnReaderSources: List<LnReaderMangaSource>): Boolean {
        if (lnReaderSources.isEmpty()) {
            return false
        }
        val known = dao.findAll().mapTo(HashSet()) { it.source }
        val missing = lnReaderSources.filterNot { it.name in known }
        if (missing.isEmpty()) {
            return false
        }
        var maxSortKey = dao.getMaxSortKey()
        val isAllEnabled = settings.isAllSourcesEnabled
        val entities = missing.map { source ->
            MangaSourceEntity(
                source = source.name,
                isEnabled = isAllEnabled,
                sortKey = ++maxSortKey,
                addedIn = BuildConfig.VERSION_CODE,
                lastUsedAt = 0,
                isPinned = false,
                cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
            )
        }
        dao.insertIfAbsent(entities)
        return true
    }

    private suspend fun assimilateNewParserSources(): Boolean {
        if (isNewSourcesAssimilated.getAndSet(true)) {
            return false
        }
        val new = getNewParserSources()
        if (new.isEmpty()) {
            return false
        }
        var maxSortKey = dao.getMaxSortKey()
        val isAllEnabled = settings.isAllSourcesEnabled
        val entities = new.map { x ->
            MangaSourceEntity(
                source = x.name,
                isEnabled = isAllEnabled,
                sortKey = ++maxSortKey,
                addedIn = BuildConfig.VERSION_CODE,
                lastUsedAt = 0,
                isPinned = false,
                cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
            )
        }
        dao.insertIfAbsent(entities)
        return true
    }

    private suspend fun assimilateInstalledMihonSources(mihonSources: List<MihonMangaSource>): Boolean {
        if (mihonSources.isEmpty()) {
            return false
        }
        val known = dao.findAll().mapTo(HashSet()) { it.source }
        val missing = mihonSources.filterNot { it.name in known }
        if (missing.isEmpty()) {
            return false
        }
        var maxSortKey = dao.getMaxSortKey()
        val isAllEnabled = settings.isAllSourcesEnabled
        val entities = missing.map { source ->
            MangaSourceEntity(
                source = source.name,
                isEnabled = isAllEnabled,
                sortKey = ++maxSortKey,
                addedIn = BuildConfig.VERSION_CODE,
                lastUsedAt = 0,
                isPinned = false,
                cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
            )
        }
        dao.insertIfAbsent(entities)
        return true
    }

    private suspend fun assimilateInstalledPluginSources(pluginSources: List<PluginMangaSource>): Boolean {
        val currentVersion = MangaSourceRegistry.version
        if (assimilatedPluginVersion == currentVersion) {
            return false
        }
        assimilatedPluginVersion = currentVersion
        if (pluginSources.isEmpty()) {
            return false
        }
        val known = dao.findAll().mapTo(HashSet()) { it.source }
        val missing = pluginSources.filterNot { it.name in known }
        if (missing.isEmpty()) {
            return false
        }
        var maxSortKey = dao.getMaxSortKey()
        val isAllEnabled = settings.isAllSourcesEnabled
        val entities = missing.map { source ->
            MangaSourceEntity(
                source = source.name,
                isEnabled = isAllEnabled,
                sortKey = ++maxSortKey,
                addedIn = BuildConfig.VERSION_CODE,
                lastUsedAt = 0,
                isPinned = false,
                cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
            )
        }
        dao.insertIfAbsent(entities)
        return true
    }

    suspend fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
        setSourcesPinnedImpl(sources, isPinned)
        return ReversibleHandle {
            setSourcesEnabledImpl(sources, !isPinned)
        }
    }

    suspend fun trackUsage(source: MangaSource) {
        if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
            ensureSourceTracked(source)
            dao.setLastUsed(source.name, System.currentTimeMillis())
        }
    }

    /**
     * Manually mark [sources] as NSFW/SFW, overriding their intrinsic rating.
     * Pass `null` to clear the override and inherit the intrinsic rating again.
     */
    suspend fun setNsfwOverride(sources: Collection<MangaSource>, isNsfw: Boolean?): ReversibleHandle {
        val previous = sources.associateWith { NsfwSourceOverrides.peek(it.unwrap().name) }
        setNsfwOverrideImpl(sources, isNsfw)
        return ReversibleHandle {
            for ((source, value) in previous) {
                dao.setNsfwOverride(source.unwrap().name, value?.let { if (it) 1 else 0 })
            }
            nsfwOverridesLoader.reload()
        }
    }

    private suspend fun setNsfwOverrideImpl(sources: Collection<MangaSource>, isNsfw: Boolean?) {
        val value = isNsfw?.let { if (it) 1 else 0 }
        if (sources.size == 1) { // fast path
            dao.setNsfwOverride(sources.first().unwrap().name, value)
        } else {
            db.withTransaction {
                for (source in sources) {
                    dao.setNsfwOverride(source.unwrap().name, value)
                }
            }
        }
        nsfwOverridesLoader.reload()
    }

    private suspend fun setSourcesEnabledImpl(sources: Collection<MangaSource>, isEnabled: Boolean) {
        if (sources.size == 1) { // fast path
            dao.setEnabled(sources.first().name, isEnabled)
            return
        }
        db.withTransaction {
            for (source in sources) {
                dao.setEnabled(source.name, isEnabled)
            }
        }
    }

    private suspend fun getNewParserSources(): MutableSet<out MangaSource> {
        val entities = dao.findAll()
        val result = EnumSet.copyOf(allMangaSources)
        for (e in entities) {
            result.remove(e.source.toParserSourceOrNull() ?: continue)
        }
        return result
    }

    private suspend fun setSourcesPinnedImpl(sources: Collection<MangaSource>, isPinned: Boolean) {
        if (sources.size == 1) { // fast path
            dao.setPinned(sources.first().name, isPinned)
            return
        }
        db.withTransaction {
            for (source in sources) {
                dao.setPinned(source.name, isPinned)
            }
        }
    }

    private fun observeExternalSources(): Flow<List<ExternalMangaSource>> {
        return callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    trySendBlocking(intent)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_VERIFIED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            awaitClose { context.unregisterReceiver(receiver) }
        }.onStart {
            emit(null)
        }.map {
            getExternalSources()
        }.distinctUntilChanged()
            .conflate()
    }

    private fun observeMihonSources(): Flow<List<MihonMangaSource>> {
        return callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    trySendBlocking(intent)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_VERIFIED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(MihonPrivateExtensionStore.ACTION_PRIVATE_EXTENSIONS_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            awaitClose { context.unregisterReceiver(receiver) }
        }.onStart {
            emit(null)
        }.filter { intent ->
            when {
                intent == null -> true
                intent.action == MihonPrivateExtensionStore.ACTION_PRIVATE_EXTENSIONS_CHANGED -> true
                intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) -> false
                else -> intent.data?.schemeSpecificPart?.let { name ->
                    runInterruptible(Dispatchers.IO) { mihonExtensionManager.isRelevantPackageChange(name) }
                } == true
            }
        }.map {
            if (it != null) {
                mihonExtensionManager.invalidate()
            }
            getMihonSources()
        }.distinctUntilChanged()
            .conflate()
    }

    private val registryUpdates: Flow<Unit>
        get() = combine(MangaSourceRegistry.updates.onStart { emit(Unit) }, remoteLibraries.changes) { _, _ -> Unit }

    fun getExternalSources(): List<ExternalMangaSource> = context.packageManager.queryIntentContentProviders(
        Intent("app.kotatsu.parser.PROVIDE_MANGA"), PackageManager.GET_META_DATA,
    ).map { resolveInfo ->
        ExternalMangaSource(
            packageName = resolveInfo.providerInfo.packageName,
            authority = resolveInfo.providerInfo.authority,
            isNsfwSource = resolveInfo.providerInfo.metaData?.getBoolean(METADATA_NSFW, false) == true,
        )
    }

    private suspend fun getMihonSources(): List<MihonMangaSource> = runInterruptible(Dispatchers.IO) {
        mihonExtensionManager.getInstalledSources()
    }

    private fun getPluginSources(): List<PluginMangaSource> =
        MangaSourceRegistry.entries.filterIsInstance<PluginMangaSource>()

    private suspend fun ensureSourceTracked(source: MangaSource) {
        when (source) {
            is MangaParserSource -> assimilateNewParserSources()
            is PluginMangaSource -> assimilateInstalledPluginSources(listOf(source))
            is LnReaderMangaSource -> assimilateInstalledLnReaderSources(listOf(source))
            is RemoteLibrarySource -> assimilateRemoteLibraries(remoteLibraries.sources().filter { it.name == source.name })
            is MihonMangaSource -> {
                val installed = mihonExtensionManager.resolve(source)?.wrapper ?: return
                assimilateInstalledMihonSources(listOf(installed))
            }
        }
    }

    private fun List<MangaSourceEntity>.toSources(
        contentPolicy: SourceContentPolicy,
        sortOrder: SourcesSortOrder?,
        hideBrokenSources: Boolean,
        mihonSources: Map<String, MihonMangaSource>,
        pluginSources: Map<String, PluginMangaSource>,
        lnReaderSources: Map<String, LnReaderMangaSource> = emptyMap(),
    ): MutableList<MangaSourceInfo> {
        val isAllEnabled = settings.isAllSourcesEnabled
        val result = ArrayList<MangaSourceInfo>(size)
        for (entity in this) {
            val source = entity.source.toInstalledSourceOrNull(mihonSources, pluginSources, lnReaderSources) ?: continue
            if (!contentPolicy.allows(source)) {
                continue
            }
            if (hideBrokenSources && source.isBrokenSource()) {
                continue
            }
            result.add(
                MangaSourceInfo(
                    mangaSource = source,
                    isEnabled = entity.isEnabled || isAllEnabled,
                    isPinned = entity.isPinned,
                ),
            )
        }
        if (sortOrder == SourcesSortOrder.ALPHABETIC) {
            result.sortWith(compareBy<MangaSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
        }
        return result
    }

    private fun observeSourceContentPolicy() = settings.observe(
        AppSettings.KEY_DISABLE_NSFW, AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED, AppSettings.KEY_SOURCE_CONTENT_CLASSES,
    ).onStart { emit(null) }.map { settings.sourceContentPolicy }.distinctUntilChanged()

    private fun observeHideBrokenSources() = settings.observeAsFlow(AppSettings.KEY_SOURCES_HIDE_BROKEN) {
        isBrokenSourcesHidden
    }

    private fun observeSortOrder() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) {
        sourcesSortOrder
    }

    private fun observeAllEnabled() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ENABLED_ALL) {
        isAllSourcesEnabled
    }

    private fun String.toParserSourceOrNull(): MangaParserSource? = MangaParserSource.entries.find { it.name == this }

    private fun String.toParserOrPluginSourceOrNull(
        pluginSources: Map<String, PluginMangaSource>,
    ): MangaSource? = toParserSourceOrNull() ?: pluginSources[this]

    private fun String.toInstalledSourceOrNull(
        mihonSources: Map<String, MihonMangaSource>,
        pluginSources: Map<String, PluginMangaSource>,
        lnReaderSources: Map<String, LnReaderMangaSource> = emptyMap(),
    ): MangaSource? {
        return toParserSourceOrNull() ?: pluginSources[this] ?: lnReaderSources[this] ?: mihonSources[this]
            ?: toRemoteLibrarySourceOrNull()
    }

    private fun String.toRemoteLibrarySourceOrNull(): RemoteLibrarySource? {
        val source = RemoteLibrarySource.fromName(this) ?: return null
        val connection = remoteLibraries.connection(source.connectionId)?.takeIf { it.enabled } ?: return null
        return RemoteLibrarySource(connection.id, connection.name, connection.content)
    }

    private fun MangaSource.isBrokenSource(): Boolean = when (this) {
        is MangaParserSource -> isBroken
        is PluginMangaSource -> isBroken
        else -> false
    }

    private fun <T : MangaSource> List<T>.filterContent(contentPolicy: SourceContentPolicy): List<T> =
        filter(contentPolicy::allows)

    companion object {

        const val METADATA_NSFW = "app.kotatsu.parser.nsfw"
    }
}
