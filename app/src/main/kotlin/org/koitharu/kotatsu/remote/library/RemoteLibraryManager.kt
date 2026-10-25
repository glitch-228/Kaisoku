package org.koitharu.kotatsu.remote.library

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.koitharu.kotatsu.core.model.NsfwSourceOverrides
import org.koitharu.kotatsu.core.model.SourceContentClass
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.local.data.importer.SingleMangaImporter
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.scrobbling.v2.KeystoreTrackerCredentialVault
import org.koitharu.kotatsu.parsers.util.await
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LibraryAcquisitionResult(val localManga: LocalManga, val record: LibraryAcquisitionRecord)

@Singleton
class RemoteLibraryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val importer: SingleMangaImporter,
    private val persistence: RemoteLibraryPersistence,
    @LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {
    private val store get() = persistence.store
    private val vault by lazy { KeystoreTrackerCredentialVault(context) }
    private val cache by lazy { LibraryCache(File(context.cacheDir, "remote_libraries")) }
    private val sessions = HashMap<String, RemoteLibrarySession>()
    private val connectionGenerations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    val changes: StateFlow<Long> get() = persistence.changes

    suspend fun connections(): List<RemoteLibraryConnection> = persistence.connections()

    suspend fun sources(): List<RemoteLibrarySource> = connections().filter { it.enabled }.map {
        RemoteLibrarySource(it.id, it.name, it.content)
    }

    fun connection(id: String): RemoteLibraryConnection? = store.connection(id)

    /** Invoked explicitly by setup/edit UI. A new origin/account never reuses an old source identity. */
    suspend fun save(connection: RemoteLibraryConnection, password: String? = null) = withContext(Dispatchers.IO) {
        persistence.mutation.withLock {
        LibraryUrlPolicy.forConnection(connection)
        val old = store.connection(connection.id)
        require(old == null || RemoteLibraryStore.sameScope(old, connection)) {
            "Add a separate connection when changing server URL or account"
        }
        if (password != null) {
            val credentials = RemoteLibraryCredentials(connection.account, password)
            vault.write(vaultKey(connection.id), buildJsonObject {
                put("username", credentials.username); put("password", credentials.password)
            }.toString())
        } else if (connection.account.isNotEmpty() && old == null) {
            throw LibraryAuthenticationException()
        }
        store.save(connection)
        connectionGenerations.computeIfAbsent(connection.id) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        synchronized(sessions) { sessions.remove(connection.id) }
        persistence.changed()
        }
    }

    fun session(connectionId: String): RemoteLibrarySession {
        val connection = store.connection(connectionId) ?: throw LibraryUnsupportedException("This library connection is unavailable. Restore or reconnect it in Remote libraries.")
        return synchronized(sessions) {
            sessions[connectionId]?.takeIf { it.connection == connection } ?: run {
                val revision = connectionGenerations.computeIfAbsent(connectionId) { java.util.concurrent.atomic.AtomicLong() }
                val generation = revision.get()
                val current = { store.connection(connectionId) == connection && connection.enabled && generation == revision.get() }
                val check = {
                    if (!current()) throw LibraryUnsupportedException("This library connection changed or was disabled; reopen it before continuing")
                }
                RemoteLibrarySession(connection,
                RemoteLibraryTransport(LibraryUrlPolicy.forConnection(connection), { credentials(connection) }, currentConnection = check), cache,
                { content -> allowsContent(connection, content) },
                { allowsLibraryContent(appSettings.sourceContentPolicy, connection.content,
                    NsfwSourceOverrides.peek(connection.sourceName), true) }, current).also { sessions[connectionId] = it }
            }
        }
    }

    fun repository(source: RemoteLibrarySource): RemoteLibraryMangaRepository = RemoteLibraryMangaRepository(source, session(source.connectionId))

    /** Shared save/OCR/translation/upscaler consumers resolve the same opaque, account-scoped route. */
    suspend fun imageResponse(value: String): okhttp3.Response {
        val reference = LibraryImageReference.parse(value) ?: throw RemoteLibraryException("Invalid library image reference")
        val active = session(reference.connectionId)
        if (reference.scope != active.connection.cacheScope) throw LibraryAuthenticationException()
        val publication = active.publication(reference.publicationId)
        val url = reference.pageIndex?.let { active.page(publication, it).url } ?: publication.cover?.href
            ?: throw RemoteLibraryException("The publication has no supported image")
        return active.transport.imageClient.newCall(active.transport.pageRequest(url)).await()
    }

    suspend fun acquired(connectionId: String, publicationId: String): List<LibraryAcquisitionRecord> = withContext(Dispatchers.IO) {
        store.acquired(connectionId, publicationId).filter { record ->
            // Do not resolve restored absolute paths to unrelated files; verify the content digest
            // during explicit open/acquire instead of silently relinking a same-named local file.
            runCatching { record.localUrl.toUri().scheme == "file" && File(record.localUrl.toUri().path.orEmpty()).isFile }.getOrDefault(false)
        }
    }

    suspend fun openAcquired(record: LibraryAcquisitionRecord): org.koitharu.kotatsu.parsers.model.Manga = withContext(Dispatchers.IO) {
        val matching = store.acquired(record.connectionId, record.publicationId).any { it == record }
        if (!matching) throw RemoteLibraryException("This acquisition reference is no longer valid")
        val connection = store.connection(record.connectionId) ?: throw RemoteLibraryException("This library connection is unavailable")
        val verification = LibraryAcquisitionVerifier.verify(record, connection)
        val parsed = org.koitharu.kotatsu.local.data.input.LocalMangaParser(LibraryAcquisitionVerifier.localFile(record.localUrl)).getManga(false)
        if (parsed.manga.id != record.localMangaId) throw RemoteLibraryException("The acquired local identity changed; open it from local storage")
        persistence.mutation.withLock { store.markVerified(record, verification); persistence.changed() }
        val decorated = parsed.copy(manga = applyAcquiredMetadata(parsed.manga))
        localStorageChanges.emit(decorated)
        decorated.manga
    }

    /** Downloading/importing is explicit. It never updates a tracker, favorite category, or reader cursor. */
    suspend fun acquire(connectionId: String, publicationId: String, link: LibraryLink,
                        anonymousExternal: Boolean = false, progress: (Long, Long?) -> Unit = { _, _ -> }): LibraryAcquisitionResult =
        withContext(Dispatchers.IO) {
            val session = session(connectionId)
            val publication = session.publication(publicationId)
            session.permitted(publication)
            if (link !in publication.acquisitionLinks) throw RemoteLibraryException("This acquisition is not advertised by the selected publication")
            val format = link.supportedFormat ?: throw LibraryUnsupportedException("Unsupported book acquisition")
            session.transport.policy.resolve(publication.documentUrl, link.href, anonymousExternal)
            val acquisition = LibraryAcquisitionTask.run(File(context.cacheDir, "library_acquisition"), publication.title,
                publication.identifier, format, { file -> session.transport.download(link, file, anonymousExternal, progress) }) { file ->
                val result = importer.import(file.toUri())
                result.imported.singleOrNull() ?: throw (result.failures.firstOrNull()?.error
                    ?: RemoteLibraryException("The acquired book could not be imported"))
            }
            val imported = acquisition.value
            val record = LibraryAcquisitionRecord(connectionId, publicationId, imported.manga.id,
                imported.manga.url, acquisition.sha256, format, LibraryAcquiredMetadata(publication.title,
                    publication.authors.take(100).toSet(), publication.subjects.take(100).toSet(), publication.description,
                    publication.content.takeUnless { it == RemoteLibraryContent.UNKNOWN } ?: session.connection.content))
            val verification = LibraryAcquisitionVerifier.verify(record, session.connection)
            try { persistence.mutation.withLock {
                try { store.recordVerified(record, verification) } finally { persistence.changed() }
            } } catch (error: IOException) {
                throw RemoteLibraryException("The book was imported into local storage, but its library reference could not be saved", error)
            }
            val decorated = imported.copy(manga = applyAcquiredMetadata(imported.manga))
            // The importer publishes first. Repeat only this title after its durable source receipt
            // commits, so the existing local index/observers receive the source metadata as well.
            localStorageChanges.emit(decorated)
            LibraryAcquisitionResult(decorated, record)
        }

    suspend fun exportTransfer(): String = persistence.exportTransfer()
    suspend fun previewTransfer(value: String): LibraryTransferPreview = persistence.previewTransfer(value)
    suspend fun mergeTransfer(value: String): LibraryTransferPreview = withContext(Dispatchers.IO) {
        persistence.mergeTransfer(value).also {
            connectionGenerations.values.forEach { it.incrementAndGet() }
            synchronized(sessions) { sessions.clear() }
        }
    }

    private suspend fun credentials(connection: RemoteLibraryConnection): RemoteLibraryCredentials? {
        if (connection.account.isEmpty()) return null
        val value = vault.read(vaultKey(connection.id)) ?: throw LibraryAuthenticationException()
        val root = Json.parseToJsonElement(value) as? JsonObject ?: throw LibraryAuthenticationException()
        val username = (root["username"] as? JsonPrimitive)?.contentOrNull ?: throw LibraryAuthenticationException()
        val password = (root["password"] as? JsonPrimitive)?.contentOrNull ?: throw LibraryAuthenticationException()
        if (username != connection.account) throw LibraryAuthenticationException()
        return RemoteLibraryCredentials(username, password)
    }

    private fun allowsContent(connection: RemoteLibraryConnection, content: RemoteLibraryContent): Boolean {
        return allowsLibraryContent(appSettings.sourceContentPolicy, content,
            NsfwSourceOverrides.peek(connection.sourceName), false)
    }

    private fun vaultKey(id: String): String = "LIBRARY_" + id.replace("-", "").uppercase(java.util.Locale.ROOT)

    /** Apply before the existing per-title user-override layer. Never change local IDs, URL, chapters or progress. */
    fun applyAcquiredMetadata(manga: org.koitharu.kotatsu.parsers.model.Manga): org.koitharu.kotatsu.parsers.model.Manga {
        return persistence.applyAcquiredMetadata(manga)
    }

    fun acquiredMetadata(manga: org.koitharu.kotatsu.parsers.model.Manga): LibraryAcquiredMetadata? =
        persistence.acquiredMetadata(manga)
}

fun LibraryAcquiredMetadata.applyTo(manga: org.koitharu.kotatsu.parsers.model.Manga): org.koitharu.kotatsu.parsers.model.Manga =
    manga.copy(
            // Existing embedded/local metadata wins; saved source fields only fill missing values.
            authors = manga.authors.ifEmpty { authors },
            tags = manga.tags.ifEmpty { subjects.mapTo(LinkedHashSet()) { org.koitharu.kotatsu.parsers.model.MangaTag(it, it, manga.source) } },
            description = manga.description?.takeIf { it.isNotBlank() } ?: description,
            contentRating = when {
                manga.contentRating == org.koitharu.kotatsu.parsers.model.ContentRating.ADULT || content == RemoteLibraryContent.ADULT ->
                    org.koitharu.kotatsu.parsers.model.ContentRating.ADULT
                manga.contentRating != null -> manga.contentRating
                content == RemoteLibraryContent.SAFE -> org.koitharu.kotatsu.parsers.model.ContentRating.SAFE
                else -> null // UNKNOWN/MIXED must remain available to the caller's content policy.
            },
        )

internal fun allowsLibraryContent(policy: org.koitharu.kotatsu.core.model.SourceContentPolicy,
                                  content: RemoteLibraryContent, sourceOverride: Boolean?, connectionOnly: Boolean): Boolean {
    // A source's SFW override can classify otherwise unknown/mixed content, but must not erase
    // an explicit adult publication rating. Marking the source adult remains conservative.
    val effective = when {
        sourceOverride == true -> RemoteLibraryContent.ADULT
        sourceOverride == false && (connectionOnly || content != RemoteLibraryContent.ADULT) -> RemoteLibraryContent.SAFE
        else -> content
    }
    val kind = when (effective) {
        RemoteLibraryContent.SAFE -> SourceContentClass.SAFE
        RemoteLibraryContent.ADULT -> SourceContentClass.ADULT
        RemoteLibraryContent.UNKNOWN, RemoteLibraryContent.MIXED -> SourceContentClass.MIXED
    }
    return policy.allows(kind, effective != RemoteLibraryContent.SAFE)
}
