package org.koitharu.kotatsu.backups.mihon

import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.core.db.MangaDatabase

@Serializable
data class ForeignImportEnvelope(
    val version: Int = 1,
    val pending: List<PendingTrackerImportEntity> = emptyList(),
    val receipts: List<ForeignImportReceiptEntity> = emptyList(),
    val manga: List<MangaBackup> = emptyList(),
) {
    fun validate() {
        require(version == 1) { "Unsupported foreign import state version" }
        require(pending.size <= 100_000 && receipts.size <= 100_000 && manga.size <= 50_000) { "Import state is too large" }
        require(pending.map { it.importId }.toSet().size == pending.size &&
            receipts.map { it.importDigest to it.mangaId }.toSet().size == receipts.size &&
            manga.map { it.id }.toSet().size == manga.size) { "Duplicate imported state identities" }
        val digest = Regex("[a-f0-9]{64}")
        var budget = (pending.size.toLong() + receipts.size + manga.size) * 256
        fun identity(mangaId: Long, source: String, edition: String, hash: String, accepted: Long) {
            require(mangaId != 0L && source.isNotBlank() && source.length <= 16_384 && edition.isNotBlank() &&
                edition.length <= 16_384 && digest.matches(hash) && accepted >= 0) { "Invalid imported title identity" }
            budget += (source.length.toLong() + edition.length + hash.length) * 4
            require(budget <= ForeignImportTransfer.MAX_BYTES) { "Import state exceeds its safe size limit" }
        }
        for (row in pending) {
            identity(row.mangaId, row.source, row.editionUrl, row.importDigest, row.acceptedAt)
            require(digest.matches(row.importId) && row.provider.isNotBlank() && row.provider.length <= 256 &&
                listOf(row.mediaId, row.libraryId, row.rawProgress, row.rawStatus, row.rawScore).all { it.length <= 4_096 }) {
                "Invalid pending tracker reference"
            }
            budget += listOf(row.importId, row.provider, row.mediaId, row.libraryId, row.rawProgress, row.rawStatus,
                row.rawScore, row.rawPayload).sumOf { it.length.toLong() * 4 }
            require(row.rawPayload.length <= 131_072) { "Oversized raw tracker evidence" }
        }
        receipts.forEach { identity(it.mangaId, it.source, it.editionUrl, it.importDigest, it.acceptedAt) }
        val referenced = pending.mapTo(HashSet()) { it.mangaId } + receipts.map { it.mangaId }
        val sources = HashMap<Long, String>()
        for ((id, source) in pending.map { it.mangaId to it.source } + receipts.map { it.mangaId to it.source }) {
            require(sources.put(id, source)?.let { it == source } != false) { "Conflicting imported source ownership" }
        }
        require(manga.all { it.id in referenced && it.source.isNotBlank() && it.url.isNotBlank() }) { "Unreferenced title metadata" }
        for (row in manga) {
            budget += Json.encodeToString(row).length.toLong() * 4
            require(budget <= ForeignImportTransfer.MAX_BYTES) { "Import state exceeds its safe size limit" }
        }
        require(budget <= ForeignImportTransfer.MAX_BYTES) { "Import state exceeds its safe size limit" }
    }
}

/** Optional native/extended-sync section; absent means no change and it never activates a tracker. */
class ForeignImportTransfer(private val database: MangaDatabase) {
    suspend fun export(): ForeignImportEnvelope = database.withTransaction {
        val dao = database.getMihonImportDao()
        val pending = dao.pending()
        val receipts = dao.receipts()
        val ids = (pending.map { it.mangaId } + receipts.map { it.mangaId }).toSet()
        ForeignImportEnvelope(pending = pending, receipts = receipts,
            manga = ids.mapNotNull { database.getMangaDao().find(it)?.let(::MangaBackup) }).also { it.validate() }
    }

    suspend fun merge(envelope: ForeignImportEnvelope) {
        envelope.validate()
        database.withTransaction {
            val metadata = envelope.manga.associateBy { it.id }
            val remaps = HashMap<Triple<Long, String, String>, Long>()
            val savedTags = HashMap<String, Map<Long, org.koitharu.kotatsu.core.db.entity.TagEntity>>()
            suspend fun resolve(id: Long, source: String, edition: String): Long {
                val key = Triple(id, source, edition)
                remaps[key]?.let { return it }
                val current = database.getMangaDao().find(id)?.manga
                val known = database.getMangaDao().findForExactIdentity(source, edition)
                val target = when {
                    current != null && current.source == source && (current.url == edition || current.publicUrl == edition ||
                        database.getChapterReadDao().policy(id)?.editionUrl == edition || metadata[id]?.let {
                            it.source == current.source && it.url == current.url && it.publicUrl == current.publicUrl
                        } == true) -> id
                    known.size == 1 -> known.single().manga.id
                    known.size > 1 || current != null -> error("Review the imported edition mapping before restoring tracker evidence")
                    metadata[id] != null -> {
                        val title = metadata.getValue(id)
                        require(title.source == source && (title.url == edition || title.publicUrl == edition))
                        val tags = title.tags.map { tag ->
                            val candidate = tag.toEntity()
                            val knownTags = savedTags.getOrPut(candidate.source) {
                                database.getTagsDao().findTags(candidate.source).associateBy { it.id }
                            }
                            knownTags[candidate.id]?.also {
                                require(it.key == candidate.key && it.source == candidate.source) { "Conflicting saved tag identity" }
                            } ?: candidate
                        }
                        database.getTagsDao().upsert(tags)
                        database.getMangaDao().upsert(title.toEntity(), tags)
                        id
                    }
                    else -> id // Durable unresolved evidence can outlive a deliberately removed title.
                }
                remaps[key] = target
                return target
            }
            val dao = database.getMihonImportDao()
            val existingPending = dao.pending().associateBy { it.importId }
            val existingReceipts = dao.receipts().associateBy { it.importDigest to it.mangaId }
            for (incoming in envelope.pending) {
                val row = incoming.copy(mangaId = resolve(incoming.mangaId, incoming.source, incoming.editionUrl))
                val previous = existingPending[row.importId]
                require(previous == null || previous.copy(acceptedAt = row.acceptedAt) == row) { "Conflicting pending tracker evidence" }
                dao.restorePending(row.copy(acceptedAt = minOf(row.acceptedAt, previous?.acceptedAt ?: row.acceptedAt)))
            }
            for (incoming in envelope.receipts) {
                val row = incoming.copy(mangaId = resolve(incoming.mangaId, incoming.source, incoming.editionUrl))
                val previous = existingReceipts[row.importDigest to row.mangaId]
                require(previous == null || previous.copy(acceptedAt = row.acceptedAt) == row) { "Conflicting import receipt" }
                dao.restoreReceipt(row.copy(acceptedAt = minOf(row.acceptedAt, previous?.acceptedAt ?: row.acceptedAt)))
            }
        }
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
