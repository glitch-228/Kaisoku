package org.koitharu.kotatsu.suggestions.local

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChapterReleaseEvidenceTest {
    private val before = chapters(100)
    private val base = ChapterReleaseEvidence.reading(null, before, null, 10_000)

    @Test fun TwentyFiveNewChaptersOfOneHundredTwentyFiveDoNotReachTwentyFivePercent() {
        assertFalse(eligible(update(125)))
    }
    @Test fun ThirtyFourNewChaptersOfOneHundredThirtyFourReachTwentyFivePercent() {
        assertTrue(eligible(update(134)))
    }
    @Test fun ExactThresholdIsIncluded() {
        val old = ChapterReleaseEvidence.reading(null, chapters(75), null, 10_000)
        assertTrue(eligible(ChapterReleaseEvidence.update(old, chapters(100, 75), null, 11_000)))
    }
    @Test fun BelowAndAboveThresholdAreDistinct() {
        assertFalse(eligible(update(133)))
        assertTrue(eligible(update(135)))
    }
    @Test fun ReleasesAccumulateAcrossSuccessfulUpdates() {
        val first = update(120)
        val next = ChapterReleaseEvidence.update(first, chapters(134).map { if (it.number > 120) it.copy(uploadDate = 12_000) else it }, null, 13_000)
        assertEquals(34, next.releases)
        assertTrue(eligible(next))
    }
    @Test fun RepeatedChecksDoNotCountReleasesTwice() {
        val first = update(134)
        val second = ChapterReleaseEvidence.update(first, chapters(134), null, 15_000)
        assertEquals(first, second)
        assertEquals(34, second.releases)
    }
    @Test fun ReadingSessionConsumesAllPreviouslyAvailableReleaseCredit() {
        val reading = ChapterReleaseEvidence.reading(update(134), chapters(134), null, 13_000)
        assertEquals(0, reading.releases)
        assertFalse(eligible(reading))
    }
    @Test fun LoweringTheThresholdAloneCannotResurrectACompletedTitle() {
        assertFalse(ChapterReleaseEvidence.eligible(update(134), true, true, 1, true))
        assertFalse(ChapterReleaseEvidence.eligible(base, true, false, 1, true))
    }
    @Test fun NeverRecommendStartedDoesNotHideUnstartedFavorites() {
        assertFalse(ChapterReleaseEvidence.eligible(update(134), true, false, 25, false))
        assertTrue(ChapterReleaseEvidence.eligible(null, false, false, 25, false))
    }
    @Test fun MissingBaselineCannotGuessReleasesFromReadingPercentage() {
        assertFalse(ChapterReleaseEvidence.eligible(null, true, false, 1, true))
        assertEquals(0, ChapterReleaseEvidence.update(null, chapters(134), null, 12_000).releases)
    }
    @Test fun ReversingTheChapterOrderPreservesTheBaseline() {
        assertEquals(base, ChapterReleaseEvidence.update(base, before.reversed(), null, 12_000))
    }
    @Test fun UrlChurnEstablishesAFreshBaseline() {
        val changed = chapters(134).map { it.copy(url = "/changed/${it.id}") }
        assertEquals(0, ChapterReleaseEvidence.update(base, changed, null, 12_000).releases)
    }
    @Test fun DeletionsDoNotEarnReleaseCredit() {
        assertEquals(0, ChapterReleaseEvidence.update(update(134), chapters(99), null, 12_000).releases)
    }
    @Test fun BackfillsAreNotNewReleases() {
        val backfill = before + chapters(34).map { it.copy(id = it.id + 1000, number = it.number + .5f) }
        assertEquals(0, ChapterReleaseEvidence.update(base, backfill, null, 12_000).releases)
        val oldDate = chapters(134).map { it.copy(uploadDate = 1000) }
        assertEquals(0, ChapterReleaseEvidence.update(base, oldDate, null, 12_000).releases)
    }
    @Test fun ChangingBranchesDoesNotCarryReleaseCredit() {
        val branch = chapters(134).map { it.copy(branch = "translation B") }
        assertEquals(0, ChapterReleaseEvidence.update(update(134), branch, "translation B", 12_000).releases)
    }
    @Test fun MixedBranchesUseOnlyTheCurrentBranchTotal() {
        val others = chapters(200).map { it.copy(branch = "other") }
        val result = ChapterReleaseEvidence.update(base, chapters(134) + others, null, 12_000)
        assertEquals(134, result.total)
        assertEquals(34, result.releases)
    }
    @Test fun MissingDatesAreAmbiguousAndEstablishANewBaseline() {
        assertEquals(0, ChapterReleaseEvidence.update(base, chapters(134).map { it.copy(uploadDate = 0) }, null, 12_000).releases)
    }
    @Test fun ReplacementOfOldChaptersWithTheSameCountIsNotAnAppend() {
        val replaced = chapters(134).map { if (it.id == 50L) it.copy(id = 5000) else it }
        assertEquals(0, ChapterReleaseEvidence.update(base, replaced, null, 12_000).releases)
    }
    @Test fun releaseIdentityProofAccumulatesAndResetsWithoutCountingTwice() {
        val first = update(120)
        val next = ChapterReleaseEvidence.update(first, chapters(134).map {
            if (it.number > 120) it.copy(uploadDate = 12_000) else it
        }, null, 13_000)
        assertTrue(next.confirmedReadingBoundary)
        assertEquals((101L..134L).toSet(), next.releaseChapterIds)
        assertEquals(1, next.releaseIdentityVersion)
        assertEquals(next, ChapterReleaseEvidence.update(next, chapters(134), null, 15_000))
        assertTrue(ChapterReleaseEvidence.reading(next, chapters(134), null, 16_000).releaseChapterIds.isEmpty())
        assertTrue(ChapterReleaseEvidence.update(next, chapters(134).map { it.copy(url = it.url + "changed") }, null, 16_000)
            .releaseChapterIds.isEmpty())
        assertFalse(ChapterReleaseEvidence.update(next, chapters(134).map { it.copy(branch = "other") }, "other", 16_000)
            .confirmedReadingBoundary)
    }

    private fun eligible(baseline: ChapterBaseline) = ChapterReleaseEvidence.eligible(baseline, true, false, 25, true)
    private fun update(total: Int) = ChapterReleaseEvidence.update(base, chapters(total), null, 11_000)
    private fun chapters(total: Int, old: Int = 100) = (1..total).map { number -> MangaChapter(number.toLong(), "Chapter $number",
        number.toFloat(), 0, "/chapter/$number", null, if (number <= old) 1000 else 11_000, null, TestMangaSource) }
}
