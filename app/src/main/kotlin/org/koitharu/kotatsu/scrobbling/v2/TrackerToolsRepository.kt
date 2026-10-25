package org.koitharu.kotatsu.scrobbling.v2

import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.data.accountSession
import org.koitharu.kotatsu.scrobbling.common.data.withSession
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerToolsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseHttpClient client: OkHttpClient,
    private val db: MangaDatabase,
    private val data: MangaDataRepository,
    private val favorites: FavouritesRepository,
    private val legacy: ScrobblerRepositoryMap,
    private val links: TrackerLinkRepository,
    private val sources: org.koitharu.kotatsu.explore.data.MangaSourcesRepository,
) : IncomingProgressAccess {
    private val prefs = context.getSharedPreferences("tracker_tools", Context.MODE_PRIVATE)
    private val wire = PrivateTrackerWire(client)
    internal val oauth = ComickOAuth()
    internal val comickConnection = ComickConnectionManager(wire, KeystoreTrackerCredentialVault(context), oauth)
    private val comick = ComickProvider(wire, comickConnection)
    private val animePlanet = AnimePlanetProvider(wire,
        { CookieManager.getInstance().getCookie(AnimePlanetCodec.ORIGIN) }, object : AnimePlanetTitleIndex {
            private fun ownerKey(account: String) = MessageDigest.getInstance("SHA-256")
                .digest(account.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            override fun slug(account: String, mediaId: String) = prefs.getString("ap_${ownerKey(account)}_$mediaId", null)
            override fun remember(account: String, mediaId: String, slug: String) {
                prefs.edit { putString("ap_${ownerKey(account)}_$mediaId", slug) }
            }
            override fun isWriteValidated(account: String) = prefs.getBoolean("ap_write_${ownerKey(account)}", false)
            override fun setWriteValidated(account: String, value: Boolean) {
                prefs.edit { putBoolean("ap_write_${ownerKey(account)}", value) }
            }
        })
    @Volatile private var pendingComick: ComickOAuthAttempt? = null
    private val rawOwners = java.util.concurrent.ConcurrentHashMap<String, LegacyRawOwnerBinding>()
    private val readOnlyProviders = java.util.concurrent.ConcurrentHashMap<String, TrackerProvider>()
    internal val localChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private fun changed() { localChanges.value = Math.addExact(localChanges.value, 1) }

    internal fun legacyRepository(key: String) = ScrobblerService.entries.firstOrNull { it.name == key }?.let { legacy[it] }

    /** Restored credentials require explicit verification before incoming or automatic actions are offered. */
    internal suspend fun incomingConnection(key: String): TrackerConnection {
        val repository = legacyRepository(key) ?: return provider(key).connection().also { changed() }
        val session = repository.accountSession()
        val owner = repository.storage.withSession(session) { repository.readRawOwner() }
        require(owner.provider == key)
        repository.storage.checkSession(session)
        rawOwners[key] = LegacyRawOwnerBinding(owner, requireNotNull(session.accountId), session.revision)
        return TrackerConnection(owner, session.revision, nickname = repository.cachedUser?.nickname)
            .also { checkConnection(it); changed() }
    }

    internal suspend fun incomingConnections(): List<TrackerConnection> = buildList {
        for (service in ScrobblerService.entries) {
            val repository = legacy[service]
            if (repository.isAuthorized && repository.cachedUser != null &&
                repository.storage.verifiedRevision == repository.storage.sessionVersion) {
                knownLegacyConnection(service.name)?.let(::add)
            }
        }
        knownConnection("COMICK")?.let(::add)
        knownConnection("ANIME_PLANET")?.let(::add)
    }

    override fun checkConnection(connection: TrackerConnection) {
        val repository = legacyRepository(connection.owner.provider)
        if (repository == null) provider(connection.owner.provider).checkConnection(connection)
        else {
            val current = repository.storage.snapshot()
            val binding = rawOwners[connection.owner.provider] ?: numericBinding(connection.owner.provider,
                current.accountId, current.revision)
            check(binding?.matches(connection, current.accountId, current.revision) == true &&
                repository.storage.verifiedRevision == current.revision) { "Tracker account changed; verify it again" }
            repository.storage.checkSession(current)
        }
    }

    override suspend fun incomingLink(connection: TrackerConnection, mangaId: Long): IncomingProgressLink? {
        checkConnection(connection)
        val current = links.find(connection.owner, mangaId)
        if (current != null) return current.takeIf { it.confirmed && !it.deleted &&
            db.getMangaDao().find(mangaId)?.let { manga -> links.sameEdition(it, manga) } == true }?.let {
            IncomingProgressLink(it.reference(), "v2:${it.revision}")
        }
        val service = ScrobblerService.entries.firstOrNull { it.name == connection.owner.provider } ?: return null
        val owner = legacyAccountId(connection)
        val link = db.getScrobblingDao().find(service.id, mangaId, owner) ?: return null
        if (link.id <= 0 || link.targetId <= 0) return null
        return IncomingProgressLink(TrackerReference(connection.owner, link.targetId.toString(), link.id.toString()),
            "legacy:${link.scrobbler}:${link.accountId}:${link.mangaId}:${link.targetId}:${link.id}")
    }

    override suspend fun incomingEntry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry? {
        checkConnection(connection)
        val repository = legacyRepository(connection.owner.provider)
        val entry = if (repository == null) provider(connection.owner.provider).entry(connection, mediaId)
        else {
            val session = repository.storage.snapshot()
            repository.storage.withSession(session) { repository.readRawEntry(LegacyTrackerBridge.mediaId(mediaId)) }
        }
        checkConnection(connection)
        require(entry == null || entry.reference.owner == connection.owner && entry.reference.mediaId == mediaId)
        return entry
    }

    var comickClientId: String
        get() = prefs.getString("comick_public_client", "").orEmpty()
        set(value) { prefs.edit { putString("comick_public_client", value.trim()) } }

    internal fun provider(key: String): TrackerProvider = when (key) {
        "COMICK" -> comick
        "ANIME_PLANET" -> animePlanet
        else -> readOnlyProviders.getOrPut(key) {
            LegacyReadOnlyTrackerProvider(key, requireNotNull(legacyRepository(key)) { "Unknown Beta tracker" },
                { incomingConnection(key) }, ::checkConnection)
        }
    }

    internal suspend fun knownConnection(key: String): TrackerConnection? = when (key) {
        "COMICK" -> comickConnection.knownConnection()
        "ANIME_PLANET" -> animePlanet.knownConnection()
        else -> knownLegacyConnection(key)
    }

    private fun numericBinding(key: String, account: Long?, revision: Long): LegacyRawOwnerBinding? =
        if (key == "MANGABAKA" || account == null || account <= 0) null else
            LegacyRawOwnerBinding(TrackerOwner(key, account.toString()), account, revision)

    private fun knownLegacyConnection(key: String): TrackerConnection? {
        val repository = legacyRepository(key) ?: return null
        if (!repository.isAuthorized || repository.cachedUser == null) return null
        val session = repository.storage.snapshot()
        if (repository.storage.verifiedRevision != session.revision) return null
        val binding = rawOwners[key]?.takeIf { it.localAccountId == session.accountId && it.revision == session.revision }
            ?: numericBinding(key, session.accountId, session.revision) ?: return null
        repository.storage.checkSession(session)
        return TrackerConnection(binding.owner, session.revision, nickname = repository.cachedUser?.nickname)
    }

    internal fun legacyAccountId(connection: TrackerConnection): Long {
        checkConnection(connection)
        return requireNotNull(legacyRepository(connection.owner.provider)?.storage?.snapshot()?.accountId) {
            "This tracker has no local numeric account binding"
        }
    }

    internal fun beginComick(write: Boolean): String {
        val attempt = oauth.begin(comickClientId, write)
        pendingComick = attempt
        return oauth.authorizationUrl(attempt).toString()
    }

    internal suspend fun finishComick(callback: String): TrackerConnection {
        val attempt = pendingComick ?: throw TrackerReconnectRequired()
        val result = oauth.consume(attempt, callback)
        pendingComick = null
        return when (result) {
            is ComickOAuthCallback.Code -> comickConnection.connect(attempt, result).also { changed() }
            is ComickOAuthCallback.Denied -> throw IllegalStateException("Comick authorization was declined")
        }
    }

    internal suspend fun verifyAnimePlanet() = animePlanet.verifyAccount().also { changed() }

    internal suspend fun validateAnimePlanetWrites(connection: TrackerConnection, mediaId: String) =
        animePlanet.validateWrites(connection, mediaId)
    internal fun canWriteAnimePlanet(connection: TrackerConnection): Boolean = animePlanet.canWrite(connection)

    internal suspend fun disconnect(key: String) {
        when (key) {
            "COMICK" -> comickConnection.disconnect(revoke = true)
            "ANIME_PLANET" -> animePlanet.disconnect()
            else -> requireNotNull(legacyRepository(key)).logout()
        }
        rawOwners.remove(key)
        changed()
    }

    internal suspend fun localFavorites(): List<Manga> = favorites.getAllManga()

    internal suspend fun localManga(id: Long): Manga? = data.findMangaById(id, withChapters = false)

    internal suspend fun incomingLinkedTitles(connection: TrackerConnection): List<Manga> {
        checkConnection(connection)
        val records = db.getTrackerLinkV2Dao().records(connection.owner.provider, connection.owner.accountId)
        check(records.size <= 20_000) { "Too many tracker links for this chooser" }
        val old = ScrobblerService.entries.firstOrNull { it.name == connection.owner.provider }?.let {
            db.getScrobblingDao().observeAll(it.id).first().filter { row ->
                row.accountId == legacyAccountId(connection)
            }.map { row -> row.mangaId }
        }.orEmpty()
        val ids = (records.map { it.mangaId } + old).distinct()
        check(ids.size <= 20_000) { "Too many tracker links for this chooser" }
        return ids.mapNotNull { id -> incomingLink(connection, id)?.let { localManga(id) } }
    }

    internal suspend fun link(connection: TrackerConnection, selection: TrackerBatchSelection,
        entry: TrackerRemoteEntry) = withContext(Dispatchers.IO) {
        checkConnection(connection)
        require(entry.reference.owner == connection.owner && entry.reference.mediaId == selection.mediaId)
        db.withTransaction {
            checkConnection(connection)
            val manga = requireNotNull(localManga(selection.localMangaId)) { "The selected local title is unavailable" }
            check((selection.expectedSource == null || selection.expectedSource == manga.source.name) &&
                (selection.expectedUrl == null || selection.expectedUrl == manga.url)) {
                "The selected source edition changed; review the match again"
            }
            val dao = db.getTrackerLinkV2Dao()
            val previous = dao.find(connection.owner.provider, connection.owner.accountId, manga.id)
            check(previous?.revision == selection.expectedLinkRevision) { "Tracker link changed; review it again" }
            links.save(requireNotNull(db.getMangaDao().find(manga.id)), entry, previous?.revision) {
                checkConnection(connection)
            }
            selection.categoryId?.let { category -> favorites.addToCategory(category, listOf(manga)) }
            checkConnection(connection)
        }
        changed()
    }

    internal suspend fun priorLink(connection: TrackerConnection, mangaId: Long): TrackerLinkV2Entity? =
        links.find(connection.owner, mangaId)

    /** The selected source result and exact prior link are reviewed before this single local commit. */
    internal suspend fun commitBulk(
        connection: TrackerConnection,
        selection: TrackerBatchSelection,
        entry: TrackerRemoteEntry,
        expectedLink: IncomingProgressLink?,
        local: org.koitharu.kotatsu.scrobbling.v2.bulk.BulkTrackerLocal,
        categoryCreatedAt: Long?,
    ) = withContext(Dispatchers.IO) {
        require(selection.confirmed && selection.localMangaId == local.id && selection.mediaId == entry.reference.mediaId &&
            entry.reference.owner == connection.owner)
        checkConnection(connection)
        val selectedSource = if (local.fromSearch) sources.getEnabledSources().singleOrNull { it.name == local.source }
            ?: error("The selected source is unavailable or disabled; choose a source edition again") else null
        val applyLocal: suspend () -> Unit = { db.withTransaction {
            checkConnection(connection)
            check(incomingLink(connection, local.id) == expectedLink) { "Tracker link changed; review the match again" }
            val before = db.getMangaDao().find(local.id)
            if (before == null) {
                require(local.fromSearch && selectedSource != null && local.url.isNotBlank()) {
                    "Choose a real readable source result before importing this title"
                }
                require(db.getMangaDao().findForExactIdentity(local.source, local.url).isEmpty()) {
                    "This source edition is already stored under another ID; select the existing edition"
                }
                val manga = Manga(local.id, local.title, local.aliases, local.url, local.publicUrl,
                    org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN,
                    org.koitharu.kotatsu.core.db.entity.ContentRating(local.contentRating), null, emptySet(), null,
                    emptySet(), source = selectedSource)
                data.storeManga(manga, replaceExisting = false)
            }
            val manga = requireNotNull(localManga(local.id))
            check(manga.source.name == local.source && manga.url == local.url &&
                (selection.expectedSource == null || selection.expectedSource == local.source) &&
                (selection.expectedUrl == null || selection.expectedUrl == local.url)) {
                "The selected source edition changed; review it again"
            }
            selection.categoryId?.let { categoryId ->
                require(categoryId in 1..Int.MAX_VALUE.toLong() && categoryCreatedAt != null)
                val category = db.getFavouriteCategoriesDao().findForRestore(categoryId.toInt())
                check(category != null && category.deletedAt == 0L && category.createdAt == categoryCreatedAt) {
                    "The selected category changed; choose it again"
                }
            }
            val previous = links.find(connection.owner, local.id)
            check(previous?.revision == selection.expectedLinkRevision) { "Tracker link changed; review it again" }
            links.save(requireNotNull(db.getMangaDao().find(local.id)), entry, previous?.revision) { checkConnection(connection) }
            selection.categoryId?.let { favorites.addToCategory(it, listOf(manga), preserveStoredMetadata = true) }
            checkConnection(connection)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        } }
        val legacyMutex = legacyRepository(connection.owner.provider)?.storage?.mutationMutex
        if (legacyMutex == null) applyLocal() else legacyMutex.withLock { applyLocal() }
        changed()
    }

    internal suspend fun restoredLinks(connection: TrackerConnection): List<TrackerLinkV2Entity> {
        checkConnection(connection)
        return links.pending(connection.owner)
    }

    internal suspend fun confirmRestored(connection: TrackerConnection, expected: TrackerLinkV2Entity) {
        checkConnection(connection)
        require(expected.reference().owner == connection.owner)
        val remote = requireNotNull(incomingEntry(connection, expected.mediaId)) {
            "This title is no longer in the verified tracker account; choose a match again"
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        links.confirm(expected, remote.reference) { checkConnection(connection) }
        changed()
    }
}
