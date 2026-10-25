package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.security.MessageDigest
import kotlin.math.ceil

data class ChapterBaseline(
    val branch: String?,
    val total: Int,
    val digest: String,
    val terminalNumber: Float,
    val terminalDate: Long,
    val observedAt: Long,
    val releases: Int = 0,
    val lastReadingAt: Long = 0,
    val releaseChapterIds: Set<Long> = emptySet(),
    val confirmedReadingBoundary: Boolean = false,
    val exactGeneration: String? = null,
    val releaseIdentityVersion: Int = 0,
    val chapterListIdentity: String = "",
)

/** Only a verified append earns credit. Reordering, deletion, replacement and backfill rebaseline. */
object ChapterReleaseEvidence {
    const val MAX_RELEASE_IDENTITIES = 8192
    private fun ordered(chapters: List<MangaChapter>, branch: String?): List<MangaChapter> =
        chapters.filter { it.branch == branch }.distinctBy { it.id }.sortedWith(
            compareBy<MangaChapter> { it.number }.thenBy { it.volume }.thenBy { it.id },
        )

    private fun digest(chapters: List<MangaChapter>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        chapters.forEach { chapter ->
            hash.update("${chapter.id}|${chapter.url}|${chapter.number}|${chapter.volume}\n".toByteArray())
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun update(old: ChapterBaseline?, chapters: List<MangaChapter>, branch: String?, now: Long,
               ownerSource: String? = null): ChapterBaseline {
        val list = ordered(chapters, branch)
        val tail = list.lastOrNull()
        val fresh = ChapterBaseline(branch, list.size, digest(list), tail?.number?.takeIf { it.isFinite() } ?: 0f, tail?.uploadDate ?: 0, now,
            lastReadingAt = old?.lastReadingAt ?: 0,
            confirmedReadingBoundary = old?.confirmedReadingBoundary == true && old.branch == branch,
            exactGeneration = old?.exactGeneration?.takeIf { old.branch == branch },
            releaseIdentityVersion = if (old?.releaseIdentityVersion == 1) 1 else 0,
            chapterListIdentity = org.koitharu.kotatsu.history.exact.ChapterReadTransfer.listRevision(list.map {
                org.koitharu.kotatsu.history.exact.ReadChapterReference(ownerSource ?: it.source.name, it.branch, it.id, it.url)
            }))
        if (chapters.filter { it.branch == branch }.size != list.size || list.any { !it.number.isFinite() }) return fresh
        if (old == null || old.branch != branch || old.total == 0 || list.size < old.total) return fresh
        if (list.size == old.total) return if (fresh.digest == old.digest) old.copy(chapterListIdentity = fresh.chapterListIdentity) else fresh
        if (digest(list.take(old.total)) != old.digest) return fresh
        val appended = list.drop(old.total)
        // Missing dates cannot prove a new release rather than a backfill.
        val verified = appended.all {
            it.number.isFinite() && it.number > old.terminalNumber &&
                it.uploadDate > 0 && it.uploadDate >= old.observedAt && it.uploadDate >= old.terminalDate
        }
        if (!verified) return fresh
        val ids = old.releaseChapterIds + appended.map { it.id }
        return fresh.copy(releases = old.releases + appended.size,
            releaseChapterIds = ids.takeIf { it.size <= MAX_RELEASE_IDENTITIES }.orEmpty(),
            releaseIdentityVersion = if (old.releaseIdentityVersion == 1 && ids.size <= MAX_RELEASE_IDENTITIES) 1 else 0)
    }

    fun reading(old: ChapterBaseline?, chapters: List<MangaChapter>, branch: String?, now: Long,
                ownerSource: String? = null): ChapterBaseline =
        update(old, chapters, branch, now, ownerSource).copy(releases = 0, lastReadingAt = now, releaseChapterIds = emptySet(),
            confirmedReadingBoundary = true, exactGeneration = null, releaseIdentityVersion = 1)

    fun eligible(baseline: ChapterBaseline?, started: Boolean, caughtUp: Boolean, threshold: Int, allowStarted: Boolean): Boolean {
        if (!started) return true
        if (!allowStarted || caughtUp || baseline == null || baseline.lastReadingAt == 0L || baseline.total <= 0) return false
        return baseline.releases > 0 && baseline.releases >= ceil(baseline.total * threshold.coerceIn(1, 100) / 100.0).toInt()
    }
}
