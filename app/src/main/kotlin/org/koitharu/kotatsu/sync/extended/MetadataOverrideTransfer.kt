package org.koitharu.kotatsu.sync.extended

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.core.archive.ArchivePrivacy
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.util.BoundedJson
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import org.koitharu.kotatsu.history.exact.ReadRevision
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class MetadataOverrideReplica(
    val mangaId: Long,
    val source: String,
    val editionUrl: String,
    val field: String,
    val value: String?,
    val revisionWall: Long,
    val revisionLogical: Long,
    val revisionActor: String,
    val coverSha256: String? = null,
    val coverData: String? = null,
    val manga: MangaBackup? = null,
) {
    val revision: ReadRevision get() = ReadRevision(revisionWall, revisionLogical, revisionActor)
    internal val key: Triple<String, String, String> get() = Triple(source, editionUrl, this.field)

    internal fun validate() {
        require(mangaId != 0L && source.isNotBlank() && source.length <= 512 && editionUrl.isNotBlank() &&
            editionUrl.length <= 16_384 && field in METADATA_FIELDS) { "Invalid metadata edition or field" }
        revision
        require(value == null || value.length <= if (field == "description") 1_000_000 else 16_384) {
            "Metadata value is too large"
        }
        require(manga == null || manga.id == mangaId && manga.source == source &&
            (manga.url == editionUrl || manga.publicUrl == editionUrl)) { "Metadata belongs to another edition" }
        require(manga == null || manga.tags.size <= 500 && manga.title.length <= 16_384 &&
            manga.altTitles.orEmpty().length <= 131_072 && manga.authors.orEmpty().length <= 131_072) {
            "Metadata title is too large"
        }
        if (field == "cover") {
            if (coverData != null) {
                require(coverData.length <= (MetadataOverrideTransfer.MAX_COVER_BYTES / 3 + 1) * 4 &&
                    coverSha256?.matches(Regex("[a-f0-9]{64}")) == true && value == "sha256:$coverSha256") {
                    "Invalid embedded metadata cover"
                }
            } else {
                require(coverSha256 == null && (value == null || publicMetadataCover(value))) {
                    "Metadata covers must be embedded images or public HTTPS URLs"
                }
            }
        } else require(coverData == null && coverSha256 == null) { "Unexpected metadata cover bytes" }
        if (field == "content_rating") require(value == null || value in setOf("SAFE", "SUGGESTIVE", "ADULT")) {
            "Unsupported metadata content rating"
        }
    }
}

@Serializable
data class MetadataOverrideEnvelope(val version: Int = 1, val fields: List<MetadataOverrideReplica>) {
    fun validate() {
        require(version == 1 && fields.size <= 20_000) { "Unsupported or oversized metadata state" }
        require(fields.map { it.key }.distinct().size == fields.size) { "Duplicate metadata edition fields" }
        fields.forEach { it.validate() }
    }
}

object MetadataOverrideCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(value: MetadataOverrideEnvelope): ByteArray {
        value.validate()
        return json.encodeToString(value.copy(fields = value.fields.sortedWith(
            compareBy({ it.source }, { it.editionUrl }, { it.field })))).toByteArray(Charsets.UTF_8).also {
            BoundedJson.validateObject(it, MetadataOverrideTransfer.MAX_BYTES)
        }
    }
    fun decode(bytes: ByteArray): MetadataOverrideEnvelope = json.decodeFromString<MetadataOverrideEnvelope>(
        BoundedJson.validateObject(bytes, MetadataOverrideTransfer.MAX_BYTES),
    ).also { it.validate() }

    internal fun sameValue(local: MetadataOverrideState, incoming: MetadataOverrideReplica): Boolean =
        local.source == incoming.source && local.editionUrl == incoming.editionUrl && local.field == incoming.field &&
            local.revision == incoming.revision && (if (local.field == "cover" && local.assetSha256 != null)
                "sha256:${local.assetSha256}" else local.value) == incoming.value
}

/** The same field reducer is used by native, Drive and the negotiated server namespaces. */
@Singleton
class MetadataOverrideTransfer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MangaDatabase,
) {
    private val clock = ChapterReadRepository(db)
    private val journal = MetadataOverrideJournal(db)

    suspend fun export(checkCurrent: () -> Unit = {}, includeCovers: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        val rows = db.withTransaction {
            checkCurrent()
            for (preferences in db.getPreferencesDao().getOverrides()) {
                val manga = db.getMangaDao().find(preferences.mangaId) ?: continue
                if (!ArchivePrivacy.canPersistMetadata(manga.toManga(null))) continue
                journal.record(manga.manga, preferences)
            }
            checkCurrent()
            currentCoroutineContext().ensureActive()
            db.getMetadataOverrideDao().dump().also { require(it.size <= 20_000) }
                .filter { includeCovers || it.field != "cover" }
        }
        val fields = ArrayList<MetadataOverrideReplica>(rows.size)
        var embeddedBytes = 0L
        for (before in rows) {
            checkCurrent()
            currentCoroutineContext().ensureActive()
            val manga = db.getMangaDao().find(before.mangaId)?.takeIf {
                it.manga.source == before.source && (it.manga.url == before.editionUrl || it.manga.publicUrl == before.editionUrl)
            }
            if (manga != null && !ArchivePrivacy.canPersistMetadata(manga.toManga(null))) continue
            if (manga == null && before.source == "LOCAL" &&
                before.editionUrl.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in
                setOf("cbz", "zip", "epub")) continue
            var row = before
            var coverData: String? = null
            var hash: String? = null
            var value = row.value
            if (row.field == "cover" && value != null && !publicMetadataCover(value)) {
                val bytes = readCover(value)
                embeddedBytes += bytes.size
                require(embeddedBytes <= MAX_BYTES / 2) { "Embedded metadata covers exceed the transfer budget" }
                hash = coverHash(bytes)
                coverData = Base64.getEncoder().encodeToString(bytes)
                row = db.withTransaction {
                    checkCurrent()
                    val current = db.getMetadataOverrideDao().edition(row.source, row.editionUrl)
                        .singleOrNull { it.mangaId == row.mangaId && it.field == row.field }
                    check(current == row) { "Metadata changed while preparing its cover; retry the transfer" }
                    val revision = if (row.assetSha256 == null || row.assetSha256 == hash) row.revision
                        else clock.nextRevision(row.revision)
                    row.copy(assetSha256 = hash, revisionWall = revision.wall, revisionLogical = revision.logical,
                        revisionActor = revision.actor).also { db.getMetadataOverrideDao().put(listOf(it)); checkCurrent() }
                }
                value = "sha256:$hash"
            }
            fields += MetadataOverrideReplica(row.mangaId, row.source, row.editionUrl, row.field, value,
                row.revisionWall, row.revisionLogical, row.revisionActor, hash, coverData, manga?.let(::MangaBackup))
        }
        checkCurrent()
        MetadataOverrideCodec.encode(MetadataOverrideEnvelope(fields = fields))
    }

    suspend fun merge(bytes: ByteArray, checkCurrent: () -> Unit = {}) = withContext(Dispatchers.IO) {
        val value = MetadataOverrideCodec.decode(bytes)
        val staged = ArrayList<File>()
        var committed = false
        try {
            db.withTransaction {
                checkCurrent()
                for (incoming in value.fields) {
                    currentCoroutineContext().ensureActive()
                    checkCurrent()
                    val id = resolve(incoming)
                    val previous = db.getMetadataOverrideDao().edition(incoming.source, incoming.editionUrl)
                        .singleOrNull { it.mangaId == id && it.field == incoming.field }
                    if (previous != null && incoming.revision == previous.revision) {
                        require(MetadataOverrideCodec.sameValue(previous, incoming)) { "Conflicting metadata revision" }
                    } else if (previous == null || incoming.revision > previous.revision) {
                        val localValue = incoming.coverData?.let { data ->
                            val image = Base64.getDecoder().decode(data)
                            require(image.size in 1..MAX_COVER_BYTES && coverHash(image) == incoming.coverSha256) {
                                "Embedded metadata cover checksum mismatch"
                            }
                            validateCover(image)
                            stageCover(image).also(staged::add).let { Uri.fromFile(it).toString() }
                        } ?: incoming.value
                        val next = MetadataOverrideState(id, incoming.source, incoming.editionUrl, incoming.field,
                            localValue, incoming.coverSha256, incoming.revisionWall, incoming.revisionLogical, incoming.revisionActor)
                        db.getMetadataOverrideDao().put(listOf(next))
                        if (db.getMangaDao().find(id) != null) {
                            val preferences = db.getPreferencesDao().find(id) ?: emptyMetadataPreferences(id)
                            db.getPreferencesDao().upsert(preferences.withMetadataField(incoming.field, localValue))
                        }
                    }
                    clock.nextRevision(incoming.revision)
                }
                require(db.getMetadataOverrideDao().dump().size <= 20_000) { "Too many metadata fields" }
                checkCurrent()
                currentCoroutineContext().ensureActive()
            }
            committed = true
        } finally {
            if (!committed) staged.forEach { it.delete() }
        }
    }

    private suspend fun resolve(value: MetadataOverrideReplica): Long {
        val byId = db.getMangaDao().find(value.mangaId)
        val matches = db.getMangaDao().findForExactIdentity(value.source, value.editionUrl)
        require(matches.size <= 1) { "Several source editions match this metadata; review their mapping first" }
        if (matches.isNotEmpty()) {
            requireTransferable(matches.single())
            return matches.single().manga.id
        }
        require(byId == null) { "Metadata title ID belongs to another source edition" }
        val manga = value.manga
        if (manga == null) {
            require(value.value == null) { "Restore the exact title metadata first" }
            return value.mangaId
        }
        val tags = manga.tags.map { it.toEntity() }
        val known = db.getTagsDao().findByIds(tags.map { it.id }).associateBy { it.id }
        require(tags.all { tag -> known[tag.id]?.let { it.key == tag.key && it.source == tag.source } != false }) {
            "Metadata tag identity belongs to another source"
        }
        val title = MangaWithTags(manga.toEntity(), tags.map { known[it.id] ?: it })
        requireTransferable(title)
        db.getTagsDao().upsert(tags.filterNot { it.id in known })
        db.getMangaDao().upsert(title.manga, title.tags)
        return manga.id
    }

    private fun requireTransferable(manga: MangaWithTags) {
        val value = manga.toManga(null)
        require(ArchivePrivacy.canPersistMetadata(value)) {
            "Protected or missing local archives cannot receive persistent metadata overrides"
        }
    }

    private fun readCover(value: String): ByteArray {
        val uri = Uri.parse(value)
        require(uri.scheme in setOf("file", "content")) { "Unsupported local metadata cover" }
        if (uri.scheme == "file") {
            val directory = requireNotNull(context.getExternalFilesDir("covers")).canonicalFile
            val file = File(requireNotNull(uri.path)).canonicalFile
            require(file.toPath().startsWith(directory.toPath()) && file != directory) { "Metadata cover is outside app cover storage" }
        }
        return requireNotNull(context.contentResolver.openInputStream(uri)) { "Metadata cover is unavailable" }.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_COVER_BYTES) { "Metadata cover is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also(::validateCover)
        }
    }

    private fun stageCover(bytes: ByteArray): File {
        val directory = requireNotNull(context.getExternalFilesDir("covers")) { "Cover storage is unavailable" }
        if (!directory.isDirectory) check(directory.mkdirs())
        val file = File.createTempFile("replica-cover-", ".img", directory)
        try { file.outputStream().use { it.write(bytes); it.fd.sync() } }
        catch (error: Throwable) { file.delete(); throw error }
        return file
    }

    private fun validateCover(bytes: ByteArray) {
        require(bytes.size in 1..MAX_COVER_BYTES)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
            options.outWidth.toLong() * options.outHeight <= 16_000_000) { "Metadata cover dimensions are unsupported" }
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_COVER_BYTES = 4 * 1024 * 1024
        internal fun coverHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

private fun publicMetadataCover(value: String): Boolean = value.length <= 16_384 &&
    value.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true
