package org.koitharu.kotatsu.scrobbling.v2

import org.koitharu.kotatsu.history.exact.encodeReadBranch
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.math.BigDecimal

/** A provider's count is a suggestion for review, never evidence that a prefix was read. */
object IncomingProgressPolicy {
    fun matchingChapter(progress: TrackerRawProgress?, chapters: List<MangaChapter>, branchKey: String): Long? {
        val number = progress?.takeIf { it.unit == "chapter" }?.numericNumber()?.takeIf { it > BigDecimal.ZERO }
            ?: return null
        val volume = progress.volume?.toBigDecimalOrNull()
        if (progress.volume != null && (volume == null || volume < BigDecimal.ZERO)) return null
        return chapters.filter { encodeReadBranch(it.branch) == branchKey && it.number.isFinite() && it.number > 0f &&
            it.number.toString().toBigDecimal().compareTo(number) == 0 &&
            (volume == null || BigDecimal.valueOf(it.volume.toLong()).compareTo(volume) == 0)
        }.singleOrNull()?.id
    }

    /** Explicit UI action only. Ambiguous numbering never manufactures prefix completion. */
    fun throughReportedChapter(progress: TrackerRawProgress?, chapters: List<MangaChapter>, branchKey: String): Set<Long>? {
        val matching = matchingChapter(progress, chapters, branchKey) ?: return null
        val target = chapters.single { it.id == matching }
        val scoped = chapters.filter { encodeReadBranch(it.branch) == branchKey &&
            (progress?.volume == null || it.volume == target.volume) }
        if (scoped.any { !it.number.isFinite() || it.number <= 0f } ||
            scoped.groupBy { it.number.toString().toBigDecimal().stripTrailingZeros() }.any { it.value.size != 1 }) return null
        return scoped.filter { it.number <= target.number }.mapTo(LinkedHashSet()) { it.id }
    }
}
