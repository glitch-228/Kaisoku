package org.koitharu.kotatsu.scrobbling.v2

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import javax.inject.Inject
import javax.inject.Singleton

/** No provider calls, reader cursor writes or library-membership changes belong in this repository. */
@Singleton
class TrackerLinkRepository @Inject constructor(private val db: MangaDatabase) {
    private val dao get() = db.getTrackerLinkV2Dao()
    private val clock = ChapterReadRepository(db)

    internal suspend fun find(owner: TrackerOwner, mangaId: Long): TrackerLinkV2Entity? = db.withTransaction {
        dao.find(owner.provider, owner.accountId, mangaId)
    }

    internal suspend fun save(
        manga: MangaWithTags,
        entry: TrackerRemoteEntry,
        expectedRevision: Long?,
        checkAccount: () -> Unit = {},
    ): TrackerLinkV2Entity = db.withTransaction {
        checkAccount()
        val owner = entry.reference.owner
        val old = dao.find(owner.provider, owner.accountId, manga.manga.id)?.let { stamped(it) }
        check(old?.revision == expectedRevision) { "Tracker link changed; review it again" }
        val revision = clock.nextRevision(old?.causalRevision)
        val next = TrackerLinkV2Entity.linked(manga.manga.id, entry, Math.addExact(old?.revision ?: 0, 1)).copy(
            source = manga.manga.source, editionUrl = manga.manga.url, confirmed = true,
            revisionWall = revision.wall, revisionLogical = revision.logical, revisionActor = revision.actor,
        )
        TrackerLinkTransfer.replica(next)
        check(dao.saveIfCurrent(next, expectedRevision)) { "Tracker link changed; review it again" }
        checkAccount()
        currentCoroutineContext().ensureActive()
        next
    }

    /** A local unlink is a causal tombstone; old replicas cannot silently revive it. */
    internal suspend fun unlink(owner: TrackerOwner, mangaId: Long, expectedRevision: Long) = db.withTransaction {
        val old = requireNotNull(find(owner, mangaId))
        check(old.revision == expectedRevision) { "Tracker link changed; review it again" }
        val revision = clock.nextRevision(old.causalRevision)
        dao.upsert(old.copy(deleted = true, confirmed = false, revision = Math.addExact(old.revision, 1),
            revisionWall = revision.wall, revisionLogical = revision.logical, revisionActor = revision.actor))
    }

    suspend fun export(): TrackerLinkEnvelope = db.withTransaction {
        val records = dao.dump()
        require(records.size <= TrackerLinkTransfer.MAX_LINKS) { "Too many tracker links" }
        TrackerLinkEnvelope(links = records.map { old ->
            val link = stamped(old)
            val manga = db.getMangaDao().find(link.mangaId)?.takeIf { sameEdition(link, it) }?.let(::MangaBackup)
            TrackerLinkTransfer.replica(link, manga)
        }).also { it.validate() }
    }

    /** All identities/conflicts are checked in one transaction; a failed envelope changes nothing. */
    suspend fun merge(value: TrackerLinkEnvelope) {
        value.validate()
        db.withTransaction {
            val targets = hashSetOf<Triple<String, String, Long>>()
            for (incoming in value.links) {
                val id = resolve(incoming)
                check(targets.add(Triple(incoming.provider, incoming.accountId, id))) {
                    "Several transferred editions resolve to one local tracker link"
                }
                val previous = dao.find(incoming.provider, incoming.accountId, id)?.let { stamped(it) }
                val local = previous?.let { TrackerLinkTransfer.replica(it) }
                val winner = TrackerLinkTransfer.newer(local, incoming)
                if (local == null || !winner.sameValue(local)) {
                    dao.upsert(winner.entity(id, Math.addExact(previous?.revision ?: 0, 1), confirmed = false))
                }
                clock.nextRevision(incoming.causalRevision)
            }
            require(dao.dump().size <= TrackerLinkTransfer.MAX_LINKS) { "Too many tracker links" }
        }
    }

    internal suspend fun pending(owner: TrackerOwner): List<TrackerLinkV2Entity> = db.withTransaction {
        dao.records(owner.provider, owner.accountId).filter { !it.deleted && !it.confirmed }
    }

    /** Only an explicit reviewed readback can activate a restored row, and it never changes its remote values. */
    internal suspend fun confirm(
        expected: TrackerLinkV2Entity,
        remote: TrackerReference,
        checkAccount: () -> Unit,
    ) = db.withTransaction {
        checkAccount()
        val current = dao.find(expected.provider, expected.accountId, expected.mangaId)
        check(current == expected && !expected.deleted && !expected.confirmed) {
            "This restored tracker link changed; review it again"
        }
        check(remote.owner == expected.reference().owner && remote.mediaId == expected.mediaId &&
            (expected.listEntryId == null || remote.listEntryId == expected.listEntryId)) {
            "The remote tracker entry changed; choose its match again"
        }
        check(db.getMangaDao().find(expected.mangaId)?.let { sameEdition(expected, it) } == true) {
            "The selected source edition changed; review the match again"
        }
        dao.upsert(expected.copy(confirmed = true, revision = Math.addExact(expected.revision, 1)))
        checkAccount()
        currentCoroutineContext().ensureActive()
    }

    private suspend fun stamped(value: TrackerLinkV2Entity): TrackerLinkV2Entity {
        if (value.causalRevision != null) return value.also { TrackerLinkTransfer.replica(it) }
        val manga = requireNotNull(db.getMangaDao().find(value.mangaId)) {
            "Restore the title metadata before transferring this tracker link"
        }
        val revision = clock.nextRevision()
        return value.copy(source = manga.manga.source, editionUrl = manga.manga.url,
            revisionWall = revision.wall, revisionLogical = revision.logical, revisionActor = revision.actor)
            .also { TrackerLinkTransfer.replica(it); dao.upsert(it) }
    }

    private suspend fun resolve(value: TrackerLinkReplica): Long {
        val byId = db.getMangaDao().find(value.mangaId)
        val candidates = db.getMangaDao().findForExactIdentity(value.source, value.editionUrl)
        require(candidates.size <= 1) { "Several local source editions match this tracker link" }
        if (candidates.isNotEmpty()) return candidates.single().manga.id
        require(byId == null) { "The tracker title ID belongs to another source edition" }
        val manga = value.manga
        if (manga != null) {
            val tags = manga.tags.map { it.toEntity() }
            val existing = db.getTagsDao().findByIds(tags.map { it.id }).associateBy { it.id }
            require(tags.all { tag -> existing[tag.id]?.let { it.source == tag.source && it.key == tag.key } != false }) {
                "Transferred tag identity belongs to another source"
            }
            db.getTagsDao().upsert(tags.filterNot { it.id in existing })
            db.getMangaDao().upsert(manga.toEntity(), tags.map { existing[it.id] ?: it })
            return manga.id
        }
        require(value.deleted) { "Restore this tracker's exact title metadata first" }
        return value.mangaId
    }

    internal fun sameEdition(link: TrackerLinkV2Entity, manga: MangaWithTags): Boolean =
        link.source == manga.manga.source && (link.editionUrl == manga.manga.url || link.editionUrl == manga.manga.publicUrl)
}
