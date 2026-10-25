package org.koitharu.kotatsu.suggestions.local

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.history.exact.RecommendationReadFootprint

class RecommendationExactBudgetTest {
    private val empty = RecommendationReadFootprint(0, 0)
    private val max = RecommendationExactBudget.TITLE_TEXT_BYTES

    @Test fun titleTextAndObjectBoundariesAreInclusiveAndRejectBeforeAllocation() {
        assertTrue(RecommendationExactBudget().admit(50_000, max - 100, RecommendationReadFootprint(49_999, 80), RecommendationReadFootprint(1, 20)))
        assertFalse(RecommendationExactBudget().admit(50_000, max - 100, RecommendationReadFootprint(50_000, 80), RecommendationReadFootprint(1, 20)))
        assertFalse(RecommendationExactBudget().admit(50_000, max - 100, RecommendationReadFootprint(49_999, 81), RecommendationReadFootprint(1, 20)))
        assertFalse(RecommendationExactBudget().canInspect(50_001, 1))
        assertFalse(RecommendationExactBudget().canInspect(1, max + 1))
    }

    @Test fun batchReferenceBudgetIsSharedAndARejectionDoesNotConsumeIt() {
        val budget = RecommendationExactBudget()
        assertFalse(budget.admit(50_001, 100, empty, empty))
        repeat(4) { assertTrue(budget.admit(50_000, 100, empty, empty)) }
        assertFalse(budget.canInspect(1, 1))
        assertFalse(budget.admit(1, 1, empty, empty))
    }

    @Test fun batchTextBudgetStopsBeforeFurtherMetadataMaterialization() {
        val budget = RecommendationExactBudget()
        repeat(8) { assertTrue(budget.admit(1, max, empty, empty)) }
        assertFalse(budget.canInspect(1, 1))
    }

    @Test fun negativeAndOverflowingSqlFootprintsNeverWrapIntoAnAllowance() {
        for (bad in listOf(-1L, Long.MAX_VALUE)) {
            assertFalse(RecommendationExactBudget().admit(1, 1, RecommendationReadFootprint(bad, 0), empty))
            assertFalse(RecommendationExactBudget().admit(1, 1, RecommendationReadFootprint(0, bad), empty))
            assertFalse(RecommendationExactBudget().admit(1, bad, empty, empty))
        }
    }
}
