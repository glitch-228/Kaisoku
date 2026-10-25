package org.koitharu.kotatsu.history.exact

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ChapterReadSnapshot(
    val policy: ChapterReadPolicyEntity?,
    val records: List<ChapterReadRecordEntity>,
    val aliases: List<ChapterReadAliasEntity>,
    val counts: List<ChapterReadCountEntity> = emptyList(),
    val resetGeneration: String? = null,
) {
    val isActive: Boolean get() = policy?.let { it.enabled && !it.deleted } == true

    fun readIds(chapters: List<MangaChapter>): Set<Long>? {
        val current = policy?.takeIf { it.enabled && !it.deleted } ?: return null
        return ChapterReadResolver.resolve(
            generation = current.generation,
            chapters = chapters.map { it.readReference(current.source) },
            records = records.map { it.toState() },
            aliases = aliases.map { it.toAlias() },
        ).readChapterIds
    }
}

/** Downloaded counterparts keep their original chapter IDs; their local file URL is not a new edition. */
internal fun MangaChapter.readReference(ownerSource: String? = null): ReadChapterReference {
    val downloaded = source.name == "LOCAL" && ownerSource != null && ownerSource != "LOCAL"
    return ReadChapterReference(if (downloaded) checkNotNull(ownerSource) else source.name, branch, id,
        if (downloaded) "" else url)
}

/** Explicit completion never updates HistoryEntity, trackers, statistics or recommendation baselines. */
@Singleton
class ChapterReadRepository @Inject constructor(private val database: MangaDatabase) {
    private val dao get() = database.getChapterReadDao()

    fun observe(mangaId: Long): Flow<ChapterReadSnapshot> = database.invalidationTracker.createFlow(
        "chapter_read_policy", "chapter_read_record", "chapter_read_alias", "chapter_read_count", emitInitialState = true,
    ).map { snapshot(mangaId) }.distinctUntilChanged()

    suspend fun snapshot(mangaId: Long): ChapterReadSnapshot = database.withTransaction {
        ChapterReadSnapshot(dao.policy(mangaId), dao.records(mangaId), dao.aliases(mangaId), dao.counts(mangaId))
    }

    /** Caller displays and confirms conversion first. Never infer a prefix from the resume cursor. */
    suspend fun convert(manga: Manga, initiallyRead: Set<Long> = emptySet(), branch: String? = manga.chapters?.firstOrNull()?.branch) = database.withTransaction {
        val chapters = requireNotNull(manga.chapters)
        require(chapters.isNotEmpty())
        require(initiallyRead.all { id -> chapters.any { it.id == id } })
        val previous = dao.policy(manga.id)
        val stored = database.getMangaDao().find(manga.id)?.manga
        val ownerSource = stored?.source ?: manga.source.name
        require(manga.source.name == ownerSource || manga.source.name == "LOCAL") {
            "Review the title source mapping before enabling exact completion"
        }
        val revision = nextRevision(previous?.revision)
        val generation = UUID.randomUUID().toString()
        val selectedBranch = encodeReadBranch(branch)
        val policy = ChapterReadPolicyEntity(manga.id, ownerSource, previous?.editionUrl ?: stored?.url ?: manga.url,
            generation, true, false, selectedBranch, revision.wall, revision.logical, revision.actor, null)
        dao.put(policy)
        dao.pruneObsoleteRecords(manga.id, generation)
        dao.put(chapters.filter { it.id in initiallyRead }.map { record(manga.id, generation, it, true, revision, ownerSource) })
        refreshCounts(manga)
    }

    suspend fun setEnabled(manga: Manga, enabled: Boolean) = database.withTransaction {
        val previous = dao.policy(manga.id)
        if (previous == null || previous.deleted) {
            if (enabled) convert(manga)
            return@withTransaction
        }
        val revision = nextRevision(previous.revision)
        dao.put(previous.copy(enabled = enabled, revisionWall = revision.wall,
            revisionLogical = revision.logical, revisionActor = revision.actor))
        if (enabled) refreshCounts(manga)
    }

    /** Explicit foreign snapshot acceptance. Both read and unread flags survive gaps/cache replacement. */
    internal suspend fun importReviewed(
        manga: Manga,
        chapters: List<MangaChapter>,
        readIds: Set<Long>,
        fingerprint: String,
    ) = database.withTransaction {
        val chapterIds = chapters.mapTo(HashSet()) { it.id }
        require(chapters.isNotEmpty() && chapterIds.size == chapters.size)
        require(chapters.all { it.source.name == manga.source.name } && chapterIds.containsAll(readIds))
        val previous = dao.policy(manga.id)
        require(previous == null || previous.source == manga.source.name) { "Review the source edition mapping first" }
        val revision = nextRevision(previous?.revision)
        val generation = UUID.randomUUID().toString()
        val branch = previous?.progressBranch ?: encodeReadBranch(chapters.first().branch)
        dao.put(ChapterReadPolicyEntity(manga.id, manga.source.name, previous?.editionUrl ?: manga.url,
            generation, true, false, branch, revision.wall, revision.logical, revision.actor, fingerprint))
        dao.put(chapters.map { record(manga.id, generation, it, it.id in readIds, revision, manga.source.name) })
        dao.pruneObsoleteRecords(manga.id, generation)
        dao.putAliases(chapters.map { it.readReference() }.filter { it.url.isNotBlank() }.map {
            ChapterReadAliasEntity(manga.id, it.key, it.source, it.branchKey, it.url)
        })
        // The caller supplies the current cache separately; foreign references need not all resolve there.
        refreshCounts(manga)
    }

    suspend fun mark(manga: Manga, chapterIds: Set<Long>, read: Boolean) = database.withTransaction {
        val policy = dao.policy(manga.id)?.takeIf { it.enabled && !it.deleted } ?: return@withTransaction
        require(policy.source == (database.getMangaDao().find(manga.id)?.manga?.source ?: manga.source.name))
        val selected = requireNotNull(manga.chapters).filter { it.id in chapterIds }
        require(selected.map { it.id }.toSet() == chapterIds)
        if (selected.isEmpty()) return@withTransaction
        val revision = nextRevision(policy.revision)
        dao.put(selected.map { record(manga.id, policy.generation, it, read, revision, policy.source) })
        dao.putAliases(selected.map { it.readReference(policy.source) }.filter { it.url.isNotBlank() }.map {
            ChapterReadAliasEntity(manga.id, it.key, it.source, it.branchKey, it.url)
        })
        refreshCounts(manga)
    }

    /** Called only with the existing reader's actual chapterCompleted flag, inside its ordered write. */
    suspend fun confirmed(manga: Manga, chapterId: Long) = database.withTransaction {
        var policy = dao.policy(manga.id)?.takeIf { it.enabled } ?: return@withTransaction
        // A source/edition remap needs its own reviewed mapping; never fail the ordinary cursor write.
        if (policy.source != (database.getMangaDao().find(manga.id)?.manga?.source ?: manga.source.name)) return@withTransaction
        val chapter = manga.chapters?.singleOrNull { it.id == chapterId } ?: return@withTransaction
        if (policy.deleted) {
            val revision = nextRevision(policy.revision)
            policy = policy.copy(generation = UUID.randomUUID().toString(), deleted = false, importFingerprint = null,
                revisionWall = revision.wall, revisionLogical = revision.logical, revisionActor = revision.actor,
                generationWall = revision.wall, generationLogical = revision.logical, generationActor = revision.actor)
            dao.put(policy)
        }
        val ref = chapter.readReference(policy.source)
        val existing = dao.records(manga.id).firstOrNull { it.generation == policy.generation && it.referenceId == ref.key }
        if (existing?.isRead != true) {
            val revision = nextRevision(existing?.toState()?.revision ?: policy.revision)
            dao.put(listOf(record(manga.id, policy.generation, chapter, true, revision, policy.source)))
        }
        if (policy.progressBranch != ref.branchKey) {
            val revision = nextRevision(policy.revision)
            dao.put(policy.copy(progressBranch = ref.branchKey, revisionWall = revision.wall,
                revisionLogical = revision.logical, revisionActor = revision.actor))
        }
        refreshCounts(manga)
    }

    /** Successful full chapter refreshes update count evidence, never read flags. */
    suspend fun refreshCounts(manga: Manga) = database.withTransaction {
        val policy = dao.policy(manga.id)?.takeIf { it.enabled && !it.deleted } ?: return@withTransaction
        if (policy.source != (database.getMangaDao().find(manga.id)?.manga?.source ?: manga.source.name)) return@withTransaction
        val chapters = manga.chapters?.takeIf { it.isNotEmpty() } ?: return@withTransaction
        val records = dao.records(manga.id).map { it.toState() }
        val aliases = dao.aliases(manga.id).map { it.toAlias() }
        val previous = dao.counts(manga.id).associateBy { it.branchKey }
        if (policy.source != "LOCAL" && (manga.source.name == "LOCAL" || chapters.any { it.source.name == "LOCAL" })) {
            // Downloaded subsets are not full remote list evidence. Keep its confirmed total and identities.
            val changed = previous.values.map { count ->
                val refs = ChapterReadTransfer.references(count)
                val read = ChapterReadResolver.resolve(policy.generation, refs, records, aliases).readChapterIds.size
                count.copy(readCount = read.coerceAtMost(refs.size))
            }.filter { previous[it.branchKey] != it }
            if (changed.isNotEmpty()) dao.putCounts(changed)
            return@withTransaction
        }
        val priorReferences = previous.values.flatMap { ChapterReadTransfer.references(it) }.associateBy { it.key }
        val observedLists = previous.values.maxOfOrNull { it.revision }
        var listRevision: ReadRevision? = null
        val counts = chapters.groupBy { encodeReadBranch(it.branch) }.map { (branch, current) ->
            val unique = current.distinctBy { it.id }
            val refs = unique.map { chapter -> chapter.readReference(policy.source).let { ref ->
                if (ref.url.isBlank()) priorReferences[ref.key] ?: ref else ref
            } }
            val fingerprint = ChapterReadTransfer.listRevision(refs)
            val before = previous[branch]
            val revision = if (before?.listRevision == fingerprint) before.revision else {
                listRevision ?: nextRevision(observedLists).also { listRevision = it }
            }
            val resolved = ChapterReadResolver.resolve(policy.generation, refs, records, aliases)
            ChapterReadCountEntity(manga.id, branch, resolved.readChapterIds.size.coerceAtMost(unique.size), unique.size,
                fingerprint, ChapterReadTransfer.encodeReferences(refs), revision.wall, revision.logical, revision.actor)
        }
        val changed = counts.filter { previous[it.branchKey] != it }
        if (changed.isNotEmpty()) dao.putCounts(changed)
    }

    /** Missing section is handled by callers; explicit reset/deletion always establishes a new generation. */
    suspend fun reset(ids: Collection<Long>): Map<Long, ChapterReadSnapshot> = database.withTransaction {
        val before = ids.associateWith { snapshot(it) }.toMutableMap()
        for ((id, saved) in before.toMap()) {
            val old = saved.policy ?: continue
            val revision = nextRevision(old.revision)
            val resetGeneration = UUID.randomUUID().toString()
            dao.put(old.copy(generation = resetGeneration, deleted = true, importFingerprint = null,
                revisionWall = revision.wall, revisionLogical = revision.logical, revisionActor = revision.actor,
                generationWall = revision.wall, generationLogical = revision.logical, generationActor = revision.actor))
            dao.pruneObsoleteRecords(id, resetGeneration)
            before[id] = saved.copy(resetGeneration = resetGeneration)
        }
        before
    }

    /** Undo copies into a new causal generation rather than resurrecting an old reset. */
    suspend fun restoreResetUndo(saved: Map<Long, ChapterReadSnapshot>) = database.withTransaction {
        for ((mangaId, snapshot) in saved) {
            val old = snapshot.policy ?: continue
            val current = dao.policy(mangaId) ?: continue
            if (current.generation != snapshot.resetGeneration || !current.deleted) continue
            val revision = nextRevision(maxOf(old.revision, current.revision))
            val generation = UUID.randomUUID().toString()
            dao.put(old.copy(generation = generation, revisionWall = revision.wall,
                revisionLogical = revision.logical, revisionActor = revision.actor,
                generationWall = revision.wall, generationLogical = revision.logical, generationActor = revision.actor))
            dao.put(snapshot.records.filter { it.generation == old.generation }.map {
                it.copy(generation = generation, revisionWall = revision.wall, revisionLogical = revision.logical,
                    revisionActor = revision.actor)
            })
            dao.pruneObsoleteRecords(mangaId, generation)
            dao.putAliases(snapshot.aliases)
            dao.putCounts(snapshot.counts)
        }
    }

    suspend fun activeCounts(ids: Collection<Long>): Map<Long, ChapterReadCountEntity?> {
        if (ids.isEmpty()) return emptyMap()
        return database.withTransaction {
            val result = HashMap<Long, ChapterReadCountEntity?>()
            for (id in ids) if (dao.policy(id)?.let {
                it.enabled && !it.deleted && database.getMangaDao().find(id)?.manga?.source == it.source
            } == true) result[id] = null
            dao.activeCounts(ids).forEach { result[it.mangaId] = it }
            result
        }
    }

    suspend fun refreshCachedCounts(mangaId: Long) {
        val manga = database.getMangaDao().find(mangaId) ?: return
        val chapters = database.getChaptersDao().findAll(mangaId)
        if (chapters.isNotEmpty()) {
            refreshCounts(manga.toManga(chapters))
        }
    }

    suspend fun export(): ChapterReadEnvelope = database.withTransaction {
        ChapterReadEnvelope(titles = dao.allPolicies().map { policy ->
            ChapterReadBackup(policy, dao.records(policy.mangaId), dao.aliases(policy.mangaId), dao.counts(policy.mangaId),
                database.getMangaDao().find(policy.mangaId)?.let { org.koitharu.kotatsu.backups.data.model.MangaBackup(it) })
        }).also { it.validate() }
    }

    /** Decode and validate the whole envelope first. Missing/ambiguous editions never overwrite a title. */
    suspend fun merge(envelope: ChapterReadEnvelope) {
        envelope.validate()
        database.withTransaction {
            for (incoming in envelope.titles) {
                val identity = incoming.policy
                val byId = database.getMangaDao().find(identity.mangaId)
                val byIdentity = database.getMangaDao().findForExactIdentity(identity.source, identity.editionUrl)
                val targetId = when {
                    byId != null && byId.manga.source == identity.source &&
                        (dao.policy(identity.mangaId)?.editionUrl == identity.editionUrl ||
                            byId.manga.url == identity.editionUrl || byId.manga.publicUrl == identity.editionUrl ||
                            incoming.manga?.let { it.id == byId.manga.id && it.source == byId.manga.source &&
                                it.url == byId.manga.url && it.publicUrl == byId.manga.publicUrl } == true) -> identity.mangaId
                    byIdentity.size == 1 -> byIdentity.single().manga.id
                    byIdentity.size > 1 -> error("Several local editions match; review the exact completion mapping first")
                    byId == null && incoming.manga != null -> {
                        val manga = incoming.manga
                        require(manga.url == identity.editionUrl || manga.publicUrl == identity.editionUrl)
                        val tags = manga.tags.map { it.toEntity() }
                        require(tags.map { it.id }.distinct().size == tags.size) { "Duplicate transferred tag identities" }
                        val known = database.getTagsDao().findByIds(tags.map { it.id }).associateBy { it.id }
                        require(tags.all { tag -> known[tag.id]?.let {
                            it.source == tag.source && it.key == tag.key
                        } != false }) { "Transferred chapter metadata tag belongs to another source" }
                        database.getTagsDao().upsert(tags.filterNot { it.id in known })
                        database.getMangaDao().upsert(manga.toEntity(), tags.map { known[it.id] ?: it })
                        identity.mangaId
                    }
                    byId == null && identity.deleted -> identity.mangaId
                    else -> error("Cannot resolve the exact completion edition; restore its title metadata first")
                }
                val mapped = incoming.remap(targetId)
                val existing = dao.policy(targetId)?.let {
                    ChapterReadBackup(it, dao.records(targetId), dao.aliases(targetId), dao.counts(targetId))
                }
                val merged = ChapterReadTransfer.merge(existing, mapped)
                dao.put(merged.policy)
                dao.put(merged.records)
                dao.pruneObsoleteRecords(targetId, merged.policy.generation)
                dao.putAliases(merged.aliases)
                dao.putCounts(merged.counts)
                val observed = (merged.records.map { it.toState().revision } + merged.counts.map { it.revision } +
                    merged.policy.revision).maxOrNull()
                nextRevision(observed)
            }
        }
    }

    private fun record(mangaId: Long, generation: String, chapter: MangaChapter, read: Boolean,
        revision: ReadRevision, ownerSource: String): ChapterReadRecordEntity {
        val ref = chapter.readReference(ownerSource)
        return ChapterReadRecordEntity(mangaId, generation, ref.key, ref.source, ref.branchKey,
            ref.chapterId, ref.url, read, revision.wall, revision.logical, revision.actor)
    }

    internal suspend fun nextRevision(observed: ReadRevision? = null): ReadRevision {
        val saved = dao.clock() ?: ChapterReadClockEntity(actor = UUID.randomUUID().toString(), wall = 0, logical = 0)
        val local = ReadRevision(saved.wall, saved.logical, saved.actor)
        val next = maxOf(local, observed ?: local).next(System.currentTimeMillis(), saved.actor)
        dao.put(saved.copy(wall = next.wall, logical = next.logical))
        return next
    }
}
