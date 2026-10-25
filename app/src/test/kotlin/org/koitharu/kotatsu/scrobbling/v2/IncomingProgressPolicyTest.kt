package org.koitharu.kotatsu.scrobbling.v2

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.history.exact.encodeReadBranch
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class IncomingProgressPolicyTest {
    private fun chapter(id: Long, number: Float, branch: String? = null, volume: Int = 0) =
        MangaChapter(id, "Chapter $id", number, volume, "/chapter/$id", null, 0, branch, MangaParserSource.REMANGA)

    @Test fun preservesFractionalNumbersAndDoesNotInterpretCountsAsPrefixes() {
        val chapters = listOf(chapter(1, 1f), chapter(2, 1.5f), chapter(3, 3f))
        assertEquals(2L, IncomingProgressPolicy.matchingChapter(TrackerRawProgress("1.50"), chapters, "N"))
        assertEquals(setOf(1L, 2L), IncomingProgressPolicy.throughReportedChapter(TrackerRawProgress("1.5"), chapters, "N"))
        assertNull(IncomingProgressPolicy.matchingChapter(TrackerRawProgress("2"), chapters, "N"))
        assertNull(IncomingProgressPolicy.matchingChapter(TrackerRawProgress("Chapter II"), chapters, "N"))
        assertNull(IncomingProgressPolicy.matchingChapter(TrackerRawProgress("1", unit = "episode"), chapters, "N"))
    }

    @Test fun duplicateNumbersBranchesVolumesAndUnnumberedChaptersNeedReview() {
        val chapters = listOf(chapter(1, 1f), chapter(2, 2f), chapter(3, 2f), chapter(4, 2f, "other"))
        assertNull(IncomingProgressPolicy.matchingChapter(TrackerRawProgress("2"), chapters, "N"))
        assertEquals(4L, IncomingProgressPolicy.matchingChapter(TrackerRawProgress("2"), chapters, encodeReadBranch("other")))
        assertNull(IncomingProgressPolicy.throughReportedChapter(TrackerRawProgress("2"), chapters, "N"))
        val volumes = listOf(chapter(1, 1f, volume = 1), chapter(2, 2f, volume = 1), chapter(3, 1f, volume = 2))
        assertNull(IncomingProgressPolicy.matchingChapter(TrackerRawProgress("1"), volumes, "N"))
        assertEquals(3L, IncomingProgressPolicy.matchingChapter(TrackerRawProgress("1", "2"), volumes, "N"))
        assertEquals(setOf(3L), IncomingProgressPolicy.throughReportedChapter(TrackerRawProgress("1", "2"), volumes, "N"))
        assertNull(IncomingProgressPolicy.throughReportedChapter(TrackerRawProgress("2"), listOf(chapter(1, 0f), chapter(2, 2f)), "N"))
    }

    @Test fun nullZeroNegativeHugeAndNonfiniteProgressCannotGuessAChapter() {
        val chapters = listOf(chapter(1, 1f), chapter(2, Float.NaN), chapter(3, Float.POSITIVE_INFINITY))
        for (value in listOf(null, "", "0", "-1", "NaN", "Infinity", "92233720368547758080")) {
            assertNull(IncomingProgressPolicy.matchingChapter(TrackerRawProgress(value), chapters, "N"))
        }
        assertEquals(1L, IncomingProgressPolicy.matchingChapter(TrackerRawProgress("1.0"), chapters.reversed(), "N"))
        assertNull(IncomingProgressPolicy.matchingChapter(TrackerRawProgress("1", "not a volume"), chapters, "N"))
    }
}
