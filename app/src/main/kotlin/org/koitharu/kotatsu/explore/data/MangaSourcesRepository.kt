package org.koitharu.kotatsu.explore.data

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

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager,
	private val nsfwOverridesLoader: NsfwOverridesLoader,
	private val lnReaderSourceManager: LnReaderSourceManager,
	private val community: CommunityRepository,
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
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = order,
			hideBrokenSources = settings.isBrokenSourcesHidden,
			mihonSources = mihonSources.associateBy { it.name },
			pluginSources = pluginSources.associateBy { it.name },
			lnReaderSources = lnReaderSources.associateBy { it.name },
		)
		val external = getExternalSources().filterNsfw(settings.isNsfwContentDisabled)
		return ArrayList<MangaSourceInfo>(enabled.size + external.size).also { list ->
			external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
			list.addAll(enabled)
		}
	}

	suspend fun getPinnedSources(): Set<MangaSource> {
		val mihonSources = getMihonSources()
		val pluginSources = getPluginSources()
		val lnReaderByName = getLnReaderSources().associateBy { it.name }
		assimilateAvailableSources(mihonSources)
		val skipNsfw = settings.isNsfwContentDisabled
		val hideBroken = settings.isBrokenSourcesHidden
		val mihonByName = mihonSources.associateBy { it.name }
		val pluginByName = pluginSources.associateBy { it.name }
		return buildSet {
			addAll(getExternalSources().filterNsfw(skipNsfw))
			addAll(dao.findAllPinned().mapNotNullToSet {
				it.source.toInstalledSourceOrNull(mihonByName, pluginByName, lnReaderByName)?.takeUnless { x ->
					(skipNsfw && x.isNsfw()) || (hideBroken && x.isBrokenSource())
				}
			})
		}
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		val mihonSources = getMihonSources()
		val pluginSources = getPluginSources()
		val lnReaderSources = getLnReaderSources()
		assimilateAvailableSources(mihonSources)
		return dao.findLastUsed(limit).toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
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
		val skipNsfw = settings.isNsfwContentDisabled
		val hideBroken = settings.isBrokenSourcesHidden
		val pluginSources = getPluginSources()
		val lnReaderSources = getLnReaderSources()
		val result = LinkedHashSet<MangaSource>(allMangaSources.size + mihonSources.size + pluginSources.size)
		allMangaSources.filterNotTo(result) { source ->
			(skipNsfw && source.isNsfw()) || (hideBroken && source.isBroken)
		}
		mihonSources.filterNotTo(result) { skipNsfw && it.isNsfw() }
		pluginSources.filterNotTo(result) { source ->
			(skipNsfw && source.isNsfw()) || (hideBroken && source.isBroken)
		}
		lnReaderSources.filterNotTo(result) { skipNsfw && it.isNsfw() }
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
			if (settings.isNsfwContentDisabled && entry.isNsfw) {
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
					is MihonMangaSource -> ContentType.MANGA
					is LnReaderMangaSource -> ContentType.NOVEL
					else -> ContentType.OTHER
				},
				isEnabled = entity.isEnabled,
				addedIn = entity.addedIn,
				isBroken = source.isBrokenSource(),
				isNsfw = source.isNsfw(),
			isMihon = source is MihonMangaSource,
			isPlugin = source is PluginMangaSource,
			isNovel = source is LnReaderMangaSource,
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
			observeIsNsfwDisabled(),
			observeHideBrokenSources(),
			observeAllEnabled(),
			observeMihonSources(),
			registryUpdates,
		) { skipNsfw, hideBroken, isAllSourcesEnabled, mihonSources, _ ->
			assimilateAvailableSources(mihonSources)
			val mihonByName = mihonSources.associateBy { it.name }
			val pluginByName = getPluginSources().associateBy { it.name }
			val lnReaderByName = getLnReaderSources().associateBy { it.name }
			dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL).map { sources ->
				sources.count {
					it.source.toInstalledSourceOrNull(mihonByName, pluginByName, lnReaderByName)?.let { source ->
						(!skipNsfw || !source.isNsfw()) &&
							(!hideBroken || !source.isBrokenSource())
					} == true
				}
			}
		}.flattenLatest().distinctUntilChanged().onStart { assimilateAvailableSources() }
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeHideBrokenSources(),
			observeAllEnabled(),
			observeMihonSources(),
			registryUpdates,
		) { skipNsfw, hideBroken, isAllSourcesEnabled, mihonSources, _ ->
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
				if ((!skipNsfw || !source.isNsfw()) && (!hideBroken || !source.isBroken)) {
					available[source.name] = source
				}
			}
			mihonSources.forEach { source ->
				if (!skipNsfw || !source.isNsfw()) {
					available[source.name] = source
				}
			}
			pluginSources.forEach { source ->
				if ((!skipNsfw || !source.isNsfw()) && (!hideBroken || !source.isBroken)) {
					available[source.name] = source
				}
			}
			lnReaderSources.forEach { source ->
				if (!skipNsfw || !source.isNsfw()) {
					available[source.name] = source
				}
			}
			dao.observeAll(enabledOnly = true, order = SourcesSortOrder.MANUAL).map { enabledSources ->
				val enabled = enabledSources.mapTo(HashSet(enabledSources.size)) { it.source }
				available.count { (name, _) -> name !in enabled }
			}
		}.flattenLatest().distinctUntilChanged().onStart { assimilateAvailableSources() }
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = registryUpdates.flatMapLatest {
		combine(
			observeIsNsfwDisabled(),
			observeHideBrokenSources(),
			observeAllEnabled(),
			observeSortOrder(),
			observeMihonSources(),
		) { skipNsfw, hideBroken, allEnabled, order, mihonSources ->
			assimilateAvailableSources(mihonSources)
			val pluginByName = getPluginSources().associateBy { source -> source.name }
			val lnReaderByName = getLnReaderSources().associateBy { source -> source.name }
			dao.observeAll(!allEnabled, order).map {
				skipNsfw to it.toSources(
					skipNsfwSources = skipNsfw,
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
		.combine(observeExternalSources()) { (skipNsfw, enabled), external ->
			val filteredExternal = external.filterNsfw(skipNsfw)
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
					?: continue
				if (source is PluginMangaSource || source is LnReaderMangaSource || source in allMangaSources) {
					result.add(source to entity.isEnabled)
				}
			}
			result
		}
	}.onStart { assimilateNewParserSources() }

	suspend fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
		setSourcesEnabledImpl(sources, isEnabled)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isEnabled)
		}
	}

	suspend fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
		val mihonSources = getMihonSources()
		val pluginSources = getPluginSources()
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

	fun observeHasNewSources(): Flow<Boolean> = observeIsNsfwDisabled().map { skipNsfw ->
		val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(
			skipNsfwSources = skipNsfw,
			sortOrder = null,
			hideBrokenSources = settings.isBrokenSourcesHidden,
			mihonSources = emptyMap(),
			pluginSources = getPluginSources().associateBy { it.name },
		)
		sources.isNotEmpty() && sources.size != allMangaSources.size
	}.onStart { assimilateNewParserSources() }

	fun observeHasNewSourcesForBadge(): Flow<Boolean> = combine(
		settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
		observeIsNsfwDisabled(),
		observeHideBrokenSources(),
	) { version, skipNsfw, hideBroken ->
		if (version < BuildConfig.VERSION_CODE) {
			val sources = dao.findAllFromVersion(version).toSources(
				skipNsfwSources = skipNsfw,
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
		return parsersUpdated || mihonUpdated || pluginUpdated || lnReaderUpdated
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
		}.map {
			if (it != null) {
				mihonExtensionManager.invalidate()
			}
			getMihonSources()
		}.distinctUntilChanged()
			.conflate()
	}

	private val registryUpdates: Flow<Unit>
		get() = MangaSourceRegistry.updates.onStart { emit(Unit) }

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
			is MihonMangaSource -> {
				val installed = mihonExtensionManager.resolve(source)?.wrapper ?: return
				assimilateInstalledMihonSources(listOf(installed))
			}
		}
	}

	private fun List<MangaSourceEntity>.toSources(
		skipNsfwSources: Boolean,
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
			if (skipNsfwSources && source.isNsfw()) {
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

	private fun observeIsNsfwDisabled() = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) {
		isNsfwContentDisabled
	}

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
	}

	private fun MangaSource.isBrokenSource(): Boolean = when (this) {
		is MangaParserSource -> isBroken
		is PluginMangaSource -> isBroken
		else -> false
	}

	private fun <T : MangaSource> List<T>.filterNsfw(skipNsfwSources: Boolean): List<T> =
		if (skipNsfwSources) filterNot { it.isNsfw() } else this

	companion object {

		const val METADATA_NSFW = "app.kotatsu.parser.nsfw"
	}
}
