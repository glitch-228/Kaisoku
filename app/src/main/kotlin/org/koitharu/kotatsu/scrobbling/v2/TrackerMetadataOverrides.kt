package org.koitharu.kotatsu.scrobbling.v2

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.archive.ArchivePrivacy
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.exceptions.CopyableErrorDetails
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.redactSecrets
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.scrobbling.common.data.withTrackerRequestGuard
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.sync.extended.MetadataOverrideState
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TrackerMetadataSelection(val title: Boolean, val description: Boolean, val cover: Boolean) {
    val any: Boolean get() = title || description || cover
}

class TrackerMetadataPreview internal constructor(
    val id: String,
    val connection: TrackerConnection,
    val expiresAt: Long,
    internal val local: TrackerMetadataLocal,
    val title: String?,
    val description: String?,
    val cover: String?,
) {
    val manga: Manga get() = local.manga
    val previous: MangaOverride? get() = local.previous
    val provider: String get() = connection.owner.provider
}

internal data class TrackerMetadataLocal(
    val manga: Manga,
    val link: IncomingProgressLink,
    val previous: MangaOverride?,
    val causality: List<MetadataOverrideState> = emptyList(),
)

internal interface TrackerMetadataAccess {
    suspend fun connection(provider: String): TrackerConnection
    fun checkConnection(connection: TrackerConnection)
    suspend fun snapshot(connection: TrackerConnection, mangaId: Long): TrackerMetadataLocal
    suspend fun metadata(connection: TrackerConnection, mediaId: String, check: suspend () -> Unit): TrackerTitle
    suspend fun commit(connection: TrackerConnection, expected: TrackerMetadataLocal, value: MangaOverride)
    suspend fun coverIsReferenced(mangaId: Long, uri: String): Boolean
}

internal class TrackerMetadataCover(val uri: String, val delete: () -> Unit)
internal fun interface TrackerMetadataCoverStore {
    suspend fun stage(url: String, check: suspend () -> Unit): TrackerMetadataCover
}

/** Memory-only, expiring, single-use reviews; constructing a value cannot bypass the registry. */
internal class TrackerMetadataWorkflow(
    private val access: TrackerMetadataAccess,
    private val covers: TrackerMetadataCoverStore,
    private val now: () -> Long,
) {
    private val mutex = Mutex()
    private val previews = linkedMapOf<String, TrackerMetadataPreview>()

    suspend fun prepare(mangaId: Long, provider: String): TrackerMetadataPreview = mutex.withLock {
        val connection = access.connection(provider)
        require(connection.owner.provider == provider) { "Tracker returned another provider" }
        val local = access.snapshot(connection, mangaId)
        validate(connection, local)
        val metadata = access.metadata(connection, local.link.reference.mediaId) { validate(connection, local) }
        require(metadata.mediaId == local.link.reference.mediaId) { "Tracker returned another title" }
        validate(connection, local)
        currentCoroutineContext().ensureActive()
        val moment = now()
        val title = metadata.title.takeIf { it.isNotBlank() && it.length <= 1_000 }
        val description = metadata.description?.takeIf { it.isNotBlank() && it.length <= 512 * 1024 }
        val cover = metadata.coverUrl?.takeIf { value -> value.length <= 16_384 &&
            value.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true }
        TrackerMetadataPreview(UUID.randomUUID().toString(), connection, Math.addExact(moment, 15 * 60_000L),
            local, title, description, cover).also { prepared -> synchronized(previews) {
                previews.entries.removeAll { it.value.expiresAt <= moment }
                check(previews.size < 16) { "Close an older tracker metadata preview first" }
                previews[prepared.id] = prepared
            } }
    }

    fun discard(preview: TrackerMetadataPreview) {
        synchronized(previews) { if (previews[preview.id] === preview) previews.remove(preview.id) }
    }

    suspend fun apply(preview: TrackerMetadataPreview, selected: TrackerMetadataSelection) = mutex.withLock {
        require(selected.any) { "Select metadata fields to apply" }
        checkActive(preview)
        require(!selected.title || preview.title != null) { "The tracker has no title to apply" }
        require(!selected.description || preview.description != null) { "The tracker has no synopsis to apply" }
        require(!selected.cover || preview.cover != null) { "The tracker has no supported cover to apply" }
        validate(preview.connection, preview.local)
        var cover: TrackerMetadataCover? = null
        var committed = false
        try {
            if (selected.cover) {
                cover = covers.stage(requireNotNull(preview.cover)) {
                    checkActive(preview)
                    validate(preview.connection, preview.local)
                }
            }
            checkActive(preview)
            validate(preview.connection, preview.local)
            currentCoroutineContext().ensureActive()
            val before = preview.previous ?: MangaOverride(null, null, null)
            access.commit(preview.connection, preview.local, before.copy(
                title = if (selected.title) preview.title else before.title,
                description = if (selected.description) preview.description else before.description,
                coverUrl = cover?.uri ?: before.coverUrl,
            ))
            committed = true
            discard(preview)
        } finally {
            val stagedCover = cover
            if (!committed && stagedCover != null) withContext(NonCancellable) {
                // A cancellation may arrive after Room committed but before withContext returned.
                // On an uncertain database failure retain the new file rather than break a saved cover.
                val referenced = runCatching { access.coverIsReferenced(preview.manga.id, stagedCover.uri) }.getOrDefault(true)
                if (!referenced) stagedCover.delete()
            }
        }
    }

    private fun checkActive(preview: TrackerMetadataPreview) {
        check(synchronized(previews) { previews[preview.id] === preview } && now() < preview.expiresAt) {
            "This metadata preview expired; refresh it before applying changes"
        }
    }

    private suspend fun validate(connection: TrackerConnection, expected: TrackerMetadataLocal) {
        access.checkConnection(connection)
        val current = access.snapshot(connection, expected.manga.id)
        check(current == expected) { "The account, link, source edition or metadata changed; refresh this preview" }
        require(current.link.reference.owner == connection.owner) { "Tracker returned another account" }
        access.checkConnection(connection)
    }
}

/** Saves selected overrides only; provider/source records and reader position are never updated here. */
@Singleton
class TrackerMetadataOverrides @Inject constructor(
    @ApplicationContext private val context: Context,
    private val data: MangaDataRepository,
    private val db: MangaDatabase,
    private val tools: TrackerToolsRepository,
    @BaseHttpClient baseClient: OkHttpClient,
) {
    private val coverClient = privateTrackerClient(baseClient)
    private val access = object : TrackerMetadataAccess {
        override suspend fun connection(provider: String) = tools.incomingConnection(provider)
        override fun checkConnection(connection: TrackerConnection) = tools.checkConnection(connection)
        override suspend fun snapshot(connection: TrackerConnection, mangaId: Long): TrackerMetadataLocal =
            db.withTransaction { localSnapshot(connection, mangaId) }
        override suspend fun metadata(connection: TrackerConnection, mediaId: String, check: suspend () -> Unit) =
            withTrackerRequestGuard(check) {
                check()
                tools.provider(connection.owner.provider).metadata(connection, mediaId).also { check() }
            }
        override suspend fun commit(connection: TrackerConnection, expected: TrackerMetadataLocal, value: MangaOverride) {
            db.withTransaction {
                check(localSnapshot(connection, expected.manga.id) == expected) {
                    "The account, link, source edition or metadata changed; refresh this preview"
                }
                data.setOverride(expected.manga, value)
                currentCoroutineContext().ensureActive()
                tools.checkConnection(connection)
                check(tools.incomingLink(connection, expected.manga.id) == expected.link) {
                    "Tracker link changed; refresh this preview"
                }
                val current = requireNotNull(db.getMangaDao().find(expected.manga.id)).manga
                check(current.source == expected.manga.source.name && current.url == expected.manga.url &&
                    current.publicUrl == expected.manga.publicUrl) { "Source edition changed; refresh this preview" }
            }
        }
        override suspend fun coverIsReferenced(mangaId: Long, uri: String) = data.getOverride(mangaId)?.coverUrl == uri
    }
    private val workflow = TrackerMetadataWorkflow(access, TrackerMetadataCoverStore(::stageCover), SystemClock::elapsedRealtime)

    suspend fun preview(manga: Manga, service: ScrobblerService): TrackerMetadataPreview = preview(manga, service.name)

    suspend fun preview(manga: Manga, provider: String): TrackerMetadataPreview {
        var pending: TrackerMetadataPreview? = null
        var delivered = false
        try {
            val prepared = withContext(Dispatchers.IO) {
                requireOrdinaryMetadata(manga)
                workflow.prepare(manga.id, provider).also {
                    pending = it
                    require(it.manga.source.name == manga.source.name && it.manga.url == manga.url &&
                        it.manga.publicUrl == manga.publicUrl) { "Source edition changed; refresh this preview" }
                }
            }
            delivered = true
            return prepared
        } finally {
            if (!delivered) pending?.let { workflow.discard(it) }
        }
    }

    fun discard(preview: TrackerMetadataPreview) = workflow.discard(preview)

    suspend fun apply(preview: TrackerMetadataPreview, selected: TrackerMetadataSelection) = withContext(Dispatchers.IO) {
        workflow.apply(preview, selected)
    }

    private suspend fun localSnapshot(connection: TrackerConnection, mangaId: Long): TrackerMetadataLocal {
        tools.checkConnection(connection)
        val manga = db.getMangaDao().find(mangaId)?.toManga() ?: error("The selected source edition is unavailable")
        requireOrdinaryMetadata(manga)
        val link = tools.incomingLink(connection, mangaId)
            ?: error("Confirm this tracker link and source edition before applying metadata")
        require(link.reference.owner == connection.owner)
        val states = db.getMetadataOverrideDao().edition(manga.source.name, manga.url)
            .filter { it.mangaId == mangaId }.sortedBy { it.field }
        return TrackerMetadataLocal(manga, link, data.getOverride(mangaId), states)
    }

    private suspend fun stageCover(url: String, validate: suspend () -> Unit): TrackerMetadataCover {
        var pending: TrackerMetadataCover? = null
        var returned = false
        try {
            val cover = withContext(Dispatchers.IO) {
                validate()
                val request = Request.Builder().url(url).header("Cache-Control", "no-store").get().build()
                require(request.url.isHttps && request.url.username.isEmpty() && request.url.password.isEmpty())
                val bytes = coverClient.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw IOException("Tracker cover HTTP ${response.code}")
                    require(response.body.contentType()?.type == "image") { "Tracker cover is not an image" }
                    if (response.body.contentLength() > MAX_COVER_BYTES) throw IOException("Tracker cover is too large")
                    val source = response.body.source()
                    if (source.request(MAX_COVER_BYTES + 1L)) throw IOException("Tracker cover is too large")
                    source.readByteArray()
                }
                validate()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Tracker cover dimensions are unsupported" }
                ensureActive()
                val directory = requireNotNull(context.getExternalFilesDir("covers")) { "Cover storage is unavailable" }
                if (!directory.isDirectory) check(directory.mkdirs() || directory.isDirectory)
                val staged = File.createTempFile("tracker-cover-", ".tmp", directory)
                val extension = when (bounds.outMimeType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "img"
                }
                val result = File(directory, "tracker-${UUID.randomUUID()}.$extension")
                var handedOff = false
                try {
                    staged.outputStream().use { output -> output.write(bytes); output.fd.sync() }
                    validate()
                    ensureActive()
                    check(staged.renameTo(result)) { "Cannot publish tracker cover" }
                    val cover = TrackerMetadataCover(result.toUri().toString()) { result.delete(); Unit }
                    pending = cover
                    handedOff = true
                    cover
                } finally {
                    staged.delete()
                    if (!handedOff) result.delete()
                }
            }
            returned = true
            return cover
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw TrackerMetadataCoverFailure(error)
        } finally {
            if (!returned) withContext(NonCancellable + Dispatchers.IO) { pending?.delete?.invoke() }
        }
    }

    private suspend fun requireOrdinaryMetadata(manga: Manga) = withContext(Dispatchers.IO) {
        require(ArchivePrivacy.canPersistMetadata(manga)) {
            "Persistent tracker metadata overrides are unavailable for protected archives"
        }
    }

    private companion object { const val MAX_COVER_BYTES = 4L * 1024 * 1024 }
}

/** Signed/authenticated URL parameters are unnecessary in a field preview or copied UI text. */
internal fun trackerMetadataCoverDisplay(url: String?): String = url?.toHttpUrlOrNull()?.let {
    it.newBuilder().username("").password("").query(null).fragment(null).build().toString()
}.orEmpty()

/** Covers may use signed URLs; copied network diagnostics must not retain their query or userinfo. */
internal class TrackerMetadataCoverFailure(cause: Exception) : IOException("Cannot load the tracker cover", cause),
    CopyableErrorDetails {
    override val copyableErrorDetails: String get() = sanitize(this)
    override fun copyableEnclosingErrorDetails(error: Throwable): String = sanitize(error)

    private fun sanitize(error: Throwable): String = URL.replace(error.stackTraceToString().take(64 * 1024)) {
        trackerMetadataCoverDisplay(it.value).ifEmpty { "[redacted cover URL]" }
    }.redactSecrets()

    private companion object { val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE) }
}
