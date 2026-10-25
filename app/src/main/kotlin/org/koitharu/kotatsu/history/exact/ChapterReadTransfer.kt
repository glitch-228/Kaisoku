package org.koitharu.kotatsu.history.exact

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backups.data.model.MangaBackup

@Serializable
data class ChapterReadEnvelope(val version: Int = 1, val titles: List<ChapterReadBackup>) {
    fun validate() {
        require(version == 1) { "Unsupported exact completion format $version" }
        require(titles.size <= 50_000 && titles.map { it.policy.mangaId }.distinct().size == titles.size)
        require(titles.sumOf { it.records.size.toLong() + it.aliases.size } <= 200_000)
        titles.forEach(ChapterReadBackup::validate)
    }
}

@Serializable
data class ChapterReadBackup(
    val policy: ChapterReadPolicyEntity,
    val records: List<ChapterReadRecordEntity>,
    val aliases: List<ChapterReadAliasEntity>,
    val counts: List<ChapterReadCountEntity>,
    val manga: MangaBackup? = null,
) {
    fun validate() {
        require(policy.source.isNotBlank() && policy.source.length <= 512 && policy.editionUrl.length <= 16_384)
        require(policy.generation.isNotBlank() && policy.generation.length <= 128)
        policy.revision
        require(policy.generationRevision <= policy.revision)
        decodeReadBranch(policy.progressBranch)
        require(manga == null || manga.id == policy.mangaId && manga.source == policy.source)
        require(records.size <= 100_000 && aliases.size <= 100_000 && counts.size <= 1_000)
        require(records.map { it.generation to it.referenceId }.distinct().size == records.size)
        for (record in records) {
            require(record.mangaId == policy.mangaId && record.source.isNotBlank() && record.source.length <= 512)
            require(record.generation.isNotBlank() && record.generation.length <= 128 && record.originalUrl.length <= 16_384)
            val state = record.toState()
            require(state.referenceId == state.chapter.key) { "Invalid chapter reference" }
        }
        for (alias in aliases) {
            require(alias.mangaId == policy.mangaId && alias.referenceId.length == 64 && alias.source.isNotBlank())
            require(alias.source.length <= 512 && alias.url.length <= 16_384)
            decodeReadBranch(alias.branchKey)
        }
        require(counts.map { it.branchKey }.distinct().size == counts.size)
        for (count in counts) {
            require(count.mangaId == policy.mangaId && count.totalCount in 0..100_000 && count.readCount in 0..count.totalCount)
            count.revision
            val chapters = ChapterReadTransfer.references(count)
            require(chapters.size == count.totalCount && chapters.all { it.branchKey == count.branchKey })
            require(chapters.map { it.key }.distinct().size == chapters.size)
            require(chapters.all { it.source.isNotBlank() && it.source.length <= 512 && it.url.length <= 16_384 })
            require(count.listRevision == ChapterReadTransfer.listRevision(chapters)) { "Invalid chapter list evidence" }
        }
    }

    fun remap(mangaId: Long) = copy(
        policy = policy.copy(mangaId = mangaId),
        records = records.map { it.copy(mangaId = mangaId) },
        aliases = aliases.map { it.copy(mangaId = mangaId) },
        counts = counts.map { it.copy(mangaId = mangaId) },
        manga = null,
    )
}

/** The same reducer serves native archives and extended replicas; cursor/history has no write path here. */
object ChapterReadTransfer {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeReferences(chapters: List<ReadChapterReference>): String = json.encodeToString(chapters)

    fun references(count: ChapterReadCountEntity): List<ReadChapterReference> {
        require(count.chapterReferences.length <= MAX_BYTES)
        // The manifest is JSON inside a string; validating only its containing envelope
        // does not bound nested objects or reject duplicate keys in this second document.
        org.koitharu.kotatsu.core.util.BoundedJson.validateObject(
            ("{\"references\":" + count.chapterReferences + "}").toByteArray(Charsets.UTF_8), MAX_BYTES + 16,
        )
        return json.decodeFromString<List<ReadChapterReference>>(count.chapterReferences).also { require(it.size <= 100_000) }
    }

    // Order changes/reverse do not create a different chapter membership manifest.
    fun listRevision(chapters: List<ReadChapterReference>): String = readIdentityHash(
        chapters.map { readIdentityHash(listOf(it.source, it.branchKey, it.chapterId.toString(), it.url)) }.sorted(),
    )

    fun merge(local: ChapterReadBackup?, incoming: ChapterReadBackup): ChapterReadBackup {
        incoming.validate()
        if (local == null) return recompute(incoming)
        local.validate()
        require(local.policy.mangaId == incoming.policy.mangaId)
        require(local.policy.source == incoming.policy.source && local.policy.editionUrl == incoming.policy.editionUrl) {
            "Exact completion belongs to another edition; review its source and chapter mapping first"
        }
        val policy = maxOf(local.policy, incoming.policy, policyComparator)
        val records = (local.records + incoming.records).groupBy { it.generation to it.referenceId }
            .values.map { values -> values.reduce { left, right ->
                if (ChapterReadResolver.newer(left.toState(), right.toState()) == left.toState()) left else right
            } }.sortedWith(compareBy({ it.generation }, { it.referenceId }))
        val aliases = (local.aliases + incoming.aliases).distinct()
            .sortedWith(compareBy({ it.referenceId }, { it.source }, { it.branchKey }, { it.url }))
        val counts = (local.counts + incoming.counts).groupBy { it.branchKey }.values.map { values ->
            values.maxWith(compareBy<ChapterReadCountEntity>({ it.revision }, { it.listRevision }, { it.chapterReferences }))
        }.sortedBy { it.branchKey }
        return recompute(ChapterReadBackup(policy, records, aliases, counts, local.manga ?: incoming.manga))
    }

    private fun recompute(value: ChapterReadBackup): ChapterReadBackup = value.copy(counts = value.counts.map { count ->
        val refs = references(count)
        val read = ChapterReadResolver.resolve(value.policy.generation, refs, value.records.map { it.toState() },
            value.aliases.map { it.toAlias() }).readChapterIds.size
        count.copy(readCount = if (value.policy.deleted) 0 else read.coerceAtMost(refs.size), totalCount = refs.size)
    })

    private val policyComparator = compareBy<ChapterReadPolicyEntity>(
        { it.generationRevision }, { it.generation }, { it.revision }, { it.deleted }, { !it.enabled }, { it.progressBranch },
        { it.importFingerprint.orEmpty() },
    )
}
