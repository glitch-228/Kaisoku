package org.koitharu.kotatsu.suggestions.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecommendationEligibilityTest {
    private val public = PublicRecommendationMetadata.anilistTitle(JSONObject(
        """{"id":42,"format":"MANGA","title":{"english":"Fixture"}}"""))!!
    private val key = "source:fixture:1"
    private val history = RecommendationReadingState(key, 100, .8f, 100)
    private val baseline = ChapterBaseline(null, 134, "", 134f, 100, 100, 34, 100)

    @Test fun unstartedFavoritesRemainEligible() {
        assertTrue(allowed(emptyList(), emptyMap(), false))
    }
    @Test fun publicCopiesOfStartedTitlesRequireConfirmedReleaseEvidence() {
        assertFalse(allowed(listOf(history), emptyMap()))
        assertFalse(allowed(listOf(history), mapOf(key to baseline.copy(total = 125, releases = 25))))
        assertTrue(allowed(listOf(history), mapOf(key to baseline)))
    }
    @Test fun caughtUpAndNeverRecommendStartedApplyToTrustedPublicCopies() {
        assertFalse(allowed(listOf(history.copy(progress = 1f, chapters = 134)), mapOf(key to baseline)))
        assertFalse(allowed(listOf(history), mapOf(key to baseline), false))
    }
    @Test fun loweringThresholdCannotReturnCompletedTitlesWithoutReleases() {
        assertFalse(RecommendationEligibility.readingAllowed(public, listOf(history.copy(progress = 1f)),
            mapOf(key to baseline.copy(releases = 0)), 1, true))
    }
    @Test fun missingOrAmbiguousHistoryBaselineDoesNotInventReleaseCredit() {
        assertFalse(allowed(listOf(history), mapOf(key to baseline.copy(lastReadingAt = 0))))
        assertFalse(allowed(emptyList(), mapOf(public.key to baseline.copy(releases = 0))))
    }
    @Test fun everyStartedTrustedSourceCopyMustRemainEligible() {
        assertFalse(allowed(listOf(history, history.copy(key = "source:other:2")), mapOf(key to baseline)))
    }
    @Test fun newerReadingSessionsInvalidateReleaseCreditFromOlderSessions() {
        assertFalse(allowed(listOf(history.copy(lastRead = 200)), mapOf(key to baseline)))
    }
    @Test fun exactCompletionWithoutAHistoryRowVetoesTrustedCopies() {
        val exact = RecommendationExactReading(134, 134, "generation", null)
        assertFalse(allowed(listOf(history.copy(lastRead = 0, progress = 0f, exact = exact)), emptyMap()))
    }
    @Test fun partialExactImportsCannotInventAReadingBoundary() {
        val exact = RecommendationExactReading(10, 134, "generation", null, unreadReleaseIds = (101L..134L).toSet())
        assertFalse(allowed(listOf(history.copy(lastRead = 0, exact = exact)), mapOf(key to baseline)))
    }
    @Test fun exactReturnCountsOnlyConfirmedReleasesStillUnreadAfterARealSession() {
        val proof = baseline.copy(releaseChapterIds = (101L..134L).toSet(), confirmedReadingBoundary = true,
            exactGeneration = "generation", releaseIdentityVersion = 1)
        val state = history.copy(progress = 1f, exact = RecommendationExactReading(100, 134, "generation", null,
            unreadReleaseIds = (101L..134L).toSet()))
        assertTrue(allowed(listOf(state), mapOf(key to proof)))
        assertFalse(allowed(listOf(state.copy(exact = state.exact!!.copy(unreadReleaseIds = (110L..134L).toSet()))), mapOf(key to proof)))
        assertFalse(allowed(listOf(state.copy(exact = state.exact!!.copy(generation = "reset"))), mapOf(key to proof)))
        assertFalse(allowed(listOf(state.copy(exact = state.exact!!.copy(branch = "other"))), mapOf(key to proof)))
        assertFalse(allowed(listOf(state.copy(exact = state.exact!!.copy(unreadReleaseIds = null))), mapOf(key to proof)))
    }
    @Test fun exactUnreadGapsOverrideLegacyCompletedPercentageAndNeverBorrowOldGapCredit() {
        val proof = baseline.copy(releaseChapterIds = (101L..134L).toSet(), confirmedReadingBoundary = true,
            exactGeneration = "generation", releaseIdentityVersion = 1)
        val state = history.copy(progress = 1f, exact = RecommendationExactReading(100, 134, "generation", null,
            unreadReleaseIds = (1L..34L).toSet()))
        assertFalse(allowed(listOf(state), mapOf(key to proof)))
    }
    private fun allowed(states: List<RecommendationReadingState>, baselines: Map<String, ChapterBaseline?>,
        allowStarted: Boolean = true) = RecommendationEligibility.readingAllowed(public, states, baselines, 25, allowStarted)
}
