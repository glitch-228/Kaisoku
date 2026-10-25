package org.koitharu.kotatsu.scrobbling.v2

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.backups.mihon.mihonImportFingerprint
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import org.koitharu.kotatsu.history.exact.ChapterReadSnapshot
import org.koitharu.kotatsu.history.exact.encodeReadBranch
import org.koitharu.kotatsu.parsers.model.Manga
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingProgressLink(val reference: TrackerReference, val revision: String)

interface IncomingProgressAccess {
    fun checkConnection(connection: TrackerConnection)
    suspend fun incomingLink(connection: TrackerConnection, mangaId: Long): IncomingProgressLink?
    suspend fun incomingEntry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry?
}

class IncomingProgressPreview internal constructor(
    val id: String,
    val connection: TrackerConnection,
    val manga: Manga,
    val entry: TrackerRemoteEntry,
    val completion: ChapterReadSnapshot,
    val resume: HistoryEntity?,
    val expiresAt: Long,
    internal val link: IncomingProgressLink,
    internal val fingerprint: String,
) {
    val branches: List<String> = manga.chapters.orEmpty().map { encodeReadBranch(it.branch) }.distinct()
}

data class IncomingProgressSelection(
    val branchKey: String,
    val readChapterIds: Set<Long> = emptySet(),
    val enableExactCompletion: Boolean = false,
    val resumeChapterId: Long? = null,
)

/** Read-only remote preparation, then a separately confirmed, serial local Room transaction. */
@Singleton
class IncomingProgressRepository internal constructor(
    private val database: MangaDatabase,
    private val reads: ChapterReadRepository,
    private val access: IncomingProgressAccess,
    private val nowMillis: () -> Long,
) {
    @Inject constructor(database: MangaDatabase, reads: ChapterReadRepository, tools: TrackerToolsRepository) :
        this(database, reads, tools, System::currentTimeMillis)

    private val mutex = Mutex()
    private val previews = LinkedHashMap<String, IncomingProgressPreview>()

    suspend fun prepare(connection: TrackerConnection, mangaId: Long): IncomingProgressPreview =
        withContext(Dispatchers.IO) { mutex.withLock {
            access.checkConnection(connection)
            val local = database.withTransaction { localSnapshot(connection, mangaId) }
            val remote = access.incomingEntry(connection, local.link.reference.mediaId)
                ?: error("This title is no longer on the selected tracker list")
            require(remote.reference.owner == connection.owner && remote.reference.mediaId == local.link.reference.mediaId &&
                (local.link.reference.listEntryId == null || remote.reference.listEntryId == local.link.reference.listEntryId)) {
                "Tracker entry identity changed; review the link first"
            }
            access.checkConnection(connection)
            database.withTransaction {
                val current = localSnapshot(connection, mangaId)
                check(current.fingerprint == local.fingerprint && current.link == local.link) {
                    "The title or reading position changed; prepare a new preview"
                }
            }
            val now = nowMillis()
            previews.entries.removeAll { it.value.expiresAt <= now }
            check(previews.size < 16) { "Close an older incoming-progress preview first" }
            IncomingProgressPreview(UUID.randomUUID().toString(), connection, local.manga, remote, local.completion,
                local.resume, Math.addExact(now, 15 * 60_000L), local.link, local.fingerprint).also { previews[it.id] = it }
        } }

    suspend fun discard(id: String) = mutex.withLock { previews.remove(id); Unit }

    suspend fun apply(id: String, selection: IncomingProgressSelection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val preview = previews[id] ?: error("Prepare a new incoming-progress preview")
            check(nowMillis() < preview.expiresAt) { "This preview expired; review it again" }
            require(selection.branchKey in preview.branches)
            val chapters = requireNotNull(preview.manga.chapters)
            val selectedBranch = chapters.filter { encodeReadBranch(it.branch) == selection.branchKey }
            require(selection.readChapterIds.all { id -> selectedBranch.any { it.id == id } })
            require(selection.resumeChapterId == null || selectedBranch.any { it.id == selection.resumeChapterId })
            require(selection.readChapterIds.isNotEmpty() || selection.resumeChapterId != null) { "Select exact changes first" }
            if (!preview.completion.isActive) {
                require(selection.enableExactCompletion) { "Confirm enabling exact chapter completion first" }
            }
            database.withTransaction {
                access.checkConnection(preview.connection)
                val current = localSnapshot(preview.connection, preview.manga.id)
                check(current.fingerprint == preview.fingerprint && current.link == preview.link) {
                    "The account, link, chapters or reading position changed; prepare a new preview"
                }
                if (!current.completion.isActive) {
                        if (current.completion.policy?.deleted != false) {
                            reads.convert(current.manga, branch = selectedBranch.first().branch)
                        } else reads.setEnabled(current.manga, true)
                }
                if (selection.readChapterIds.isNotEmpty()) {
                    reads.mark(current.manga, selection.readChapterIds, read = true)
                }
                selection.resumeChapterId?.let { chapterId ->
                    // No reader-save hook, tracker echo, session timestamp or recommendation release credit.
                    // A brand new imported cursor has unknown session dates (zero), not an invented reading session.
                    val old = current.resume
                    val cursor = old?.copy(chapterId = chapterId, page = 0, scroll = 0f, percent = 0f, deletedAt = 0,
                        chaptersCount = selectedBranch.size) ?: HistoryEntity(current.manga.id, 0, 0, chapterId,
                        0, 0f, 0f, 0, selectedBranch.size)
                    database.getHistoryDao().restore(cursor)
                }
                currentCoroutineContext().ensureActive()
                access.checkConnection(preview.connection)
            }
            previews.remove(id)
        }
    }

    private data class LocalSnapshot(val manga: Manga, val link: IncomingProgressLink,
        val completion: ChapterReadSnapshot, val resume: HistoryEntity?, val fingerprint: String)

    private suspend fun localSnapshot(connection: TrackerConnection, mangaId: Long): LocalSnapshot {
        access.checkConnection(connection)
        val link = access.incomingLink(connection, mangaId) ?: error("Confirm this tracker link first")
        require(link.reference.owner == connection.owner)
        val stored = database.getMangaDao().find(mangaId) ?: error("The selected source edition is unavailable")
        val chapters = database.getChaptersDao().findAll(mangaId)
        require(chapters.size in 1..100_000 && chapters.map { it.chapterId }.distinct().size == chapters.size) {
            "Refresh this title's chapters explicitly before importing progress"
        }
        val manga = stored.toManga(chapters)
        return LocalSnapshot(manga, link, reads.snapshot(mangaId), database.getHistoryDao().findForRestore(mangaId),
            database.mihonImportFingerprint(setOf(mangaId)))
    }
}
