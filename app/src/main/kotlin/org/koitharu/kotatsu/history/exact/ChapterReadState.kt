package org.koitharu.kotatsu.history.exact

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Causal version for explicit completion. Cursor/history timestamps have separate ownership. */
data class ReadRevision(val wall: Long, val logical: Long, val actor: String) : Comparable<ReadRevision> {
    init {
        require(wall >= 0 && logical >= 0 && actor.isNotBlank() && actor.length <= 128)
    }

    override fun compareTo(other: ReadRevision): Int =
        compareValuesBy(this, other, ReadRevision::wall, ReadRevision::logical, ReadRevision::actor)

    fun next(now: Long, localActor: String): ReadRevision {
        val nextWall = maxOf(wall, now.coerceAtLeast(0))
        return if (nextWall > wall) ReadRevision(nextWall, 0, localActor)
        else if (logical < Long.MAX_VALUE) ReadRevision(wall, logical + 1, localActor)
        else ReadRevision(Math.addExact(wall, 1), 0, localActor)
    }
}

@Serializable
data class ReadChapterReference(
    val source: String,
    val branch: String?,
    val chapterId: Long,
    val url: String,
) {
    val branchKey: String get() = encodeReadBranch(branch)
    val key: String get() = readIdentityHash(listOf(source, branchKey, chapterId.toString()))
}

data class ExplicitChapterRead(
    val referenceId: String,
    val generation: String,
    val chapter: ReadChapterReference,
    val read: Boolean,
    val revision: ReadRevision,
)

data class ChapterReadAlias(val referenceId: String, val source: String, val branchKey: String, val url: String)

data class ResolvedChapterReads(val readChapterIds: Set<Long>, val resolvedCount: Int, val unresolved: Int)

/** Null and an explicitly empty branch must never alias one another. */
fun encodeReadBranch(branch: String?): String = branch?.let { "S$it" } ?: "N"

fun decodeReadBranch(key: String): String? = when {
    key == "N" -> null
    key.startsWith('S') -> key.drop(1)
    else -> throw IllegalArgumentException("Invalid chapter branch")
}

fun readIdentityHash(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

object ChapterReadResolver {
    /** Exact scoped IDs win; URLs match only when both the reference and current chapter are unique. */
    fun resolve(
        generation: String,
        chapters: List<ReadChapterReference>,
        records: List<ExplicitChapterRead>,
        aliases: List<ChapterReadAlias> = emptyList(),
    ): ResolvedChapterReads {
        val scoped = chapters.groupBy { Triple(it.source, it.branchKey, it.chapterId) }
        val byUrl = chapters.filter { it.url.isNotBlank() }.groupBy { Triple(it.source, it.branchKey, it.url) }
        val recordsById = records.filter { it.generation == generation }.groupBy { it.referenceId }
            .mapValues { (_, values) -> values.reduce(::newer) }
        val aliasesById = aliases.groupBy { it.referenceId }
        val urlOwners = HashMap<Triple<String, String, String>, MutableSet<String>>()
        for (record in recordsById.values) {
            val ref = record.chapter
            if (ref.url.isNotBlank()) urlOwners.getOrPut(Triple(ref.source, ref.branchKey, ref.url), ::LinkedHashSet).add(record.referenceId)
            for (alias in aliasesById[record.referenceId].orEmpty()) {
                if (alias.url.isNotBlank()) urlOwners.getOrPut(Triple(alias.source, alias.branchKey, alias.url), ::LinkedHashSet).add(record.referenceId)
            }
        }
        val resolved = LinkedHashMap<Triple<String, String, Long>, ExplicitChapterRead>()
        var unresolved = 0
        for (record in recordsById.values) {
            val ref = record.chapter
            val exact = scoped[Triple(ref.source, ref.branchKey, ref.chapterId)]?.singleOrNull()
            val target = exact ?: run {
                val urls = listOf(ChapterReadAlias(record.referenceId, ref.source, ref.branchKey, ref.url)) +
                    aliasesById[record.referenceId].orEmpty()
                urls.mapNotNull { alias ->
                    if (alias.source != ref.source || alias.branchKey != ref.branchKey || alias.url.isBlank()) null
                    else Triple(alias.source, alias.branchKey, alias.url).let { key ->
                        byUrl[key]?.singleOrNull()?.takeIf { urlOwners[key]?.size == 1 }
                    }
                }.distinctBy { Triple(it.source, it.branchKey, it.chapterId) }.singleOrNull()
            }
            if (target == null) {
                unresolved++
            } else {
                val key = Triple(target.source, target.branchKey, target.chapterId)
                resolved[key] = resolved[key]?.let { newer(it, record) } ?: record
            }
        }
        return ResolvedChapterReads(
            readChapterIds = resolved.filterValues { it.read }.keys.mapTo(LinkedHashSet()) { it.third },
            resolvedCount = resolved.size,
            unresolved = unresolved,
        )
    }

    fun newer(left: ExplicitChapterRead, right: ExplicitChapterRead): ExplicitChapterRead = when {
        left.revision > right.revision -> left
        right.revision > left.revision -> right
        left.read != right.read -> if (left.read) right else left
        else -> if (listOf(left.referenceId, left.chapter.source, left.chapter.branchKey,
            left.chapter.chapterId.toString(), left.chapter.url).joinToString("\u0000") <=
            listOf(right.referenceId, right.chapter.source, right.chapter.branchKey,
                right.chapter.chapterId.toString(), right.chapter.url).joinToString("\u0000")) left else right
    }
}
