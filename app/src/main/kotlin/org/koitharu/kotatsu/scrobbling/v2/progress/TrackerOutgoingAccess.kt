package org.koitharu.kotatsu.scrobbling.v2.progress

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import org.koitharu.kotatsu.history.exact.encodeReadBranch
import org.koitharu.kotatsu.history.exact.readIdentityHash
import org.koitharu.kotatsu.scrobbling.common.data.withTrackerRequestGuard
import org.koitharu.kotatsu.scrobbling.v2.*
import javax.inject.Inject
import javax.inject.Singleton

/** Committed cache reads only; the two guarded methods are the only provider operations. */
@Singleton
class TrackerOutgoingAccess @Inject constructor(
    private val tools: TrackerToolsRepository,
    private val db: MangaDatabase,
    private val reads: ChapterReadRepository,
) : OutgoingProgressAccess {
    internal fun ownsLegacyAccount(connection: OutgoingProgressConnection, localAccount: Long): Boolean =
        tools.legacyAccountId(connection.tracker()) == localAccount

    internal suspend fun hasExactCompletion(mangaId: Long): Boolean = reads.snapshot(mangaId).isActive

    override suspend fun accounts(): List<OutgoingProgressAccount> = tools.incomingConnections().map { connection ->
        val key = connection.owner.provider
        val writable = TrackerCapability.PROGRESS_WRITE in tools.provider(key).capabilities && when (key) {
            "COMICK" -> "library:write" in connection.scopes
            "ANIME_PLANET" -> tools.canWriteAnimePlanet(connection)
            else -> true
        }
        OutgoingProgressAccount(OutgoingProgressConnection.from(connection),
            connection.nickname ?: connection.owner.accountId, writable,
            maxChapterScale = if (key in setOf("COMICK", "MANGABAKA")) 12 else 0,
            maxVolumeScale = when (key) { "KITSU" -> null; "COMICK", "MANGABAKA" -> 12; else -> 0 },
            supportsEntryIds = key == "COMICK")
    }

    override suspend fun snapshot(connection: OutgoingProgressConnection, mangaId: Long): OutgoingProgressSnapshot? =
        db.withTransaction {
            val account = connection.tracker()
            tools.checkConnection(account)
            val link = tools.incomingLink(account, mangaId) ?: return@withTransaction null
            val manga = db.getMangaDao().find(mangaId)?.manga ?: return@withTransaction null
            val chapters = db.getChaptersDao().findAll(mangaId)
            check(chapters.size in 1..100_000) { "Refresh this title's chapters explicitly first" }
            val exact = reads.snapshot(mangaId)
            val history = db.getHistoryDao().findForRestore(mangaId)
            val branch = chapters.singleOrNull { it.chapterId == history?.chapterId }?.let { encodeReadBranch(it.branch) }
                ?: exact.policy?.progressBranch?.takeIf { key -> chapters.any { encodeReadBranch(it.branch) == key } }
                ?: chapters.map { encodeReadBranch(it.branch) }.distinct().singleOrNull()
                ?: error("Select and read the intended chapter branch before reviewing tracker progress")
            val policy = exact.policy
            val evidence = readIdentityHash(listOf(manga.source, manga.url, policy?.generation.orEmpty(),
                policy?.enabled.toString(), policy?.deleted.toString(), policy?.source.orEmpty(), policy?.editionUrl.orEmpty()))
            val position = readIdentityHash(listOf(history.toString()) + exact.records.sortedBy { it.referenceId }.map { it.toString() })
            OutgoingProgressSnapshot(mangaId, manga.title, manga.source, manga.url, manga.publicUrl,
                branch, link.reference.mediaId, link.reference.listEntryId, link.revision, evidence, position)
        }

    /** Called only after HistoryRepository committed the actual reader completion flag. */
    suspend fun completion(connection: OutgoingProgressConnection, mangaId: Long, chapterId: Long):
        Pair<OutgoingProgressSnapshot, OutgoingProgressCompletion>? = db.withTransaction {
        val snapshot = snapshot(connection, mangaId) ?: return@withTransaction null
        val exact = reads.snapshot(mangaId)
        val policy = exact.policy?.takeIf { it.enabled && !it.deleted } ?: return@withTransaction null
        val chapter = db.getChaptersDao().findAll(mangaId).singleOrNull { it.chapterId == chapterId }
            ?.takeIf { encodeReadBranch(it.branch) == snapshot.branchKey } ?: return@withTransaction null
        val record = exact.records.singleOrNull { it.generation == policy.generation && it.chapterId == chapterId &&
            it.source == policy.source && it.branchKey == snapshot.branchKey && it.isRead } ?: return@withTransaction null
        val number = chapter.number.takeIf { it.isFinite() && it > 0f }?.toString()?.let {
            TrackerRawProgress(it).numericNumber()?.stripTrailingZeros()?.toPlainString()
        } ?: return@withTransaction null
        val reference = "$chapterId:" + readIdentityHash(
            listOf(snapshot.source, snapshot.branchKey, chapterId.toString(), chapter.url, number))
        val event = readIdentityHash(listOf(policy.generation, record.referenceId, record.revisionWall.toString(),
            record.revisionLogical.toString(), record.revisionActor, reference))
        snapshot to OutgoingProgressCompletion(event, reference, number, snapshot.branchKey, snapshot.evidenceRevision)
    }

    override suspend fun validate(guard: OutgoingProgressGuard): Unit = db.withTransaction {
        tools.checkConnection(guard.connection.tracker())
        val current = snapshot(guard.connection, guard.snapshot.mangaId)
        check(current?.sameEvidence(guard.snapshot, ignorePosition = guard.completion != null) == true) {
            "Tracker account, link, source edition, branch or reading state changed; review again"
        }
        guard.completion?.let { expected ->
            val policy = reads.snapshot(guard.snapshot.mangaId).policy
            check(policy?.enabled == true && !policy.deleted) { "Exact completion was disabled or reset" }
            val chapterId = expected.chapterReference.substringBefore(':').toLongOrNull()
                ?: error("The confirmed reader chapter identity is invalid")
            check(completion(guard.connection, guard.snapshot.mangaId, chapterId)?.second == expected) {
                "The confirmed reader completion changed; no progress was sent"
            }
        }
        Unit
    }

    override suspend fun readback(guard: OutgoingProgressGuard, beforeRequest: suspend () -> Unit): TrackerRemoteEntry? =
        guarded(guard, beforeRequest) { tools.incomingEntry(guard.connection.tracker(), guard.snapshot.mediaId) }

    /** Uncertainty can be resolved after local cursor/reset/relink changes, without rearming the old action. */
    override suspend fun recoveryReadback(guard: OutgoingProgressGuard,
        beforeRequest: suspend () -> Unit): TrackerRemoteEntry? {
        val connection = guard.connection.tracker()
        tools.checkConnection(connection)
        return withTrackerRequestGuard({ tools.checkConnection(connection); beforeRequest() }) {
            tools.incomingEntry(connection, guard.snapshot.mediaId).also { tools.checkConnection(connection) }
        }
    }

    override suspend fun updateProgressOnly(guard: OutgoingProgressGuard, fields: OutgoingProgressFields,
        expectedRemote: OutgoingProgressRemote?, allowLower: Boolean, allowUnknown: Boolean,
        beforeSend: suspend () -> Unit): TrackerRemoteEntry = guarded(guard, beforeSend) {
        accounts().single { it.connection.sameSession(guard.connection) }.check(fields)
        withTrackerProgressWritePolicy(fields.raw(), expectedRemote?.raw(), allowLower, allowUnknown) {
            tools.provider(guard.connection.provider).patch(guard.connection.tracker(), guard.snapshot.mediaId,
                TrackerPatch(progress = TrackerField.Set(fields.raw())), allowCreate = false)
        }
    }

    private suspend fun <T> guarded(guard: OutgoingProgressGuard, callback: suspend () -> Unit, block: suspend () -> T): T {
        validate(guard)
        return withTrackerRequestGuard({ validate(guard); callback(); tools.checkConnection(guard.connection.tracker()) }) {
            block().also { validate(guard) }
        }
    }
}
