package org.koitharu.kotatsu.search.unified

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.TrackerConnection
import org.koitharu.kotatsu.scrobbling.v2.TrackerToolsRepository
import org.koitharu.kotatsu.scrobbling.common.data.collectRawTrackerLibraryPages
import org.koitharu.kotatsu.search.domain.SearchTrackerAccount
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerSearchRepository @Inject constructor(
    @ApplicationContext context: Context,
    val tools: TrackerToolsRepository,
    private val db: MangaDatabase,
    private val data: MangaDataRepository,
    private val settings: AppSettings,
    private val sources: org.koitharu.kotatsu.explore.data.MangaSourcesRepository,
) {
    private val cache = TrackerSearchCache(File(context.noBackupFilesDir, "tracker-search-v1"))
    val sessionChanges: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.merge(
        *ScrobblerService.entries.map { service ->
            requireNotNull(tools.legacyRepository(service.name)).storage.sessionChanges.map { Unit }
        }.toTypedArray(),
    )
    private val catalogueRequests = CatalogueRequestCoordinator(cache)
    @Volatile private var sourceVersion = ""
    private val cacheScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    init { cacheScope.launch { sources.observeRecommendationVersion().catch { sourceVersion = "unavailable" }.collect { sourceVersion = it } } }
    private val libraryLocks = ScrobblerService.entries.associateWith { Mutex() }

    fun isCurrent(account: SearchTrackerAccount): Boolean {
        val repository = tools.legacyRepository(account.provider) ?: return false
        val session = runCatching { repository.storage.snapshot() }.getOrNull() ?: return false
        return repository.isAuthorized && session.accountId == account.localAccount && session.revision == account.revision
    }

    fun cachedAccount(service: ScrobblerService): SearchTrackerAccount? {
        val connection = tools.knownConnectionForSearch(service.name)
        return if (connection != null) account(connection) else cache.account(service.name)?.takeIf(::isCurrent)
    }

    suspend fun connect(service: ScrobblerService): SearchTrackerAccount = account(tools.incomingConnection(service.name))

    suspend fun verified(account: SearchTrackerAccount): TrackerConnection {
        check(isCurrent(account)) { "Tracker account changed; search again" }
        val connection = tools.incomingConnection(account.provider)
        check(account(connection) == account) { "Tracker account changed; search again" }
        return connection
    }

    private fun account(connection: TrackerConnection): SearchTrackerAccount = SearchTrackerAccount(
        connection.owner.provider, connection.owner.accountId, connection.revision, tools.legacyAccountId(connection),
    )

    fun cachedLibrary(account: SearchTrackerAccount): List<SavedTrackerTitle> =
        if (isCurrent(account)) cache.library(account)?.titles.orEmpty() else emptyList()

    suspend fun refreshLibrary(account: SearchTrackerAccount, force: Boolean = false): List<SavedTrackerTitle> =
        libraryLocks.getValue(ScrobblerService.valueOf(account.provider)).withLock {
            check(isCurrent(account)) { "Tracker account changed" }
            val cached = cache.library(account)
            if (!force && cached != null && cache.fresh(cached.savedAt, 300_000)) return@withLock cached.titles
            val connection = verified(account)
            val provider = tools.provider(account.provider)
            val titles = collectRawTrackerLibraryPages(connection.owner) { provider.libraryPage(connection, it) }
                .map(SavedTrackerTitle::from)
            check(isCurrent(account)) { "Tracker account changed" }
            cache.saveLibrary(account, titles)
            titles
        }

    private fun catalogueKey(account: SearchTrackerAccount, query: String, cursor: String?): String =
        listOf(account.provider, account.account, account.revision, query, cursor, settings.sourceContentPolicy,
            settings.isNsfwContentDisabled, sourceVersion).joinToString("\u0000")

    fun cachedCatalogue(account: SearchTrackerAccount, query: String, cursor: String?, stale: Boolean = false): CataloguePage? =
        if (settings.useCombinedTrackerSearch && isCurrent(account)) cache.catalogue(catalogueKey(account, query, cursor), stale)
        else null

    suspend fun catalogue(account: SearchTrackerAccount, query: String, cursor: String?): CataloguePage {
        val key = catalogueKey(account, query, cursor)
        return catalogueRequests.get(key, {
            settings.useCombinedTrackerSearch && isCurrent(account) && key == catalogueKey(account, query, cursor)
        }) {
            verified(account)
            requireNotNull(tools.legacyRepository(account.provider)).readCataloguePage(query, cursor)
        }
    }

    suspend fun cards(account: SearchTrackerAccount, titles: List<CatalogueTitle>,
        saved: List<SavedTrackerTitle> = cachedLibrary(account)): List<TrackerSearchCard> {
        if (!isCurrent(account)) return emptyList()
        val policy = settings.sourceContentPolicy
        val available = (sources.getEnabledSources() + sources.getDisabledSources()).map { it.name }.toSet()
        val rows = db.getTrackerLinkV2Dao().records(account.provider, account.account)
        val savedMap = saved.associateBy { it.work.id }
        val service = ScrobblerService.valueOf(account.provider)
        val rowsByManga = rows.associateBy { it.mangaId }
        val rowsByMedia = rows.filter { it.confirmed && !it.deleted }.groupBy { it.mediaId }
        val legacyRows = db.getScrobblingDao().observeAll(service.id, account.localAccount).first()
            .filter { it.mangaId !in rowsByManga }.groupBy { it.targetId.toString() }
        val relevant = titles.map { it.id }.toSet()
        val allIds = (rows.filter { it.mediaId in relevant && it.confirmed && !it.deleted }.map { it.mangaId } +
            legacyRows.filterKeys { it in relevant }.values.flatten().map { it.mangaId }).distinct()
        val mangaById = allIds.chunked(500).flatMap { data.findMangaByIds(it.toLongArray()) }.associateBy { it.id }
        return titles.mapNotNull { work ->
            val ids = rowsByMedia[work.id].orEmpty().map { it.mangaId }.toMutableSet()
            work.id.toLongOrNull()?.takeIf { it > 0 && it.toString() == work.id }?.let { numeric ->
                legacyRows[work.id].orEmpty().forEach { ids += it.mangaId }
            }
            val editions = ids.mapNotNull { id -> mangaById[id]?.takeIf { manga ->
                val witness = rowsByManga[id]
                manga.source.name in available && policy.allows(manga) &&
                    (witness == null || witness.source == manga.source.name && witness.editionUrl == manga.url &&
                        witness.mediaId == work.id && witness.confirmed && !witness.deleted)
            } }
            if (work.content.allowed(policy) || work.content == CatalogueContent.UNKNOWN && editions.isNotEmpty()) {
                TrackerSearchCard(account, work, savedMap[work.id], editions)
            } else null
        }.takeIf { isCurrent(account) && policy == settings.sourceContentPolicy }.orEmpty()
    }
}
