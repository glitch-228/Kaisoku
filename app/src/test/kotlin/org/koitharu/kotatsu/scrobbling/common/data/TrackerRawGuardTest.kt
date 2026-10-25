package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrackerRawGuardTest {
    @Test fun nestedProviderSessionGuardsPreserveTheOuterReviewedEditionCheck() = runTest {
        val calls = arrayListOf<String>()
        withTrackerRequestGuard({ calls += "edition" }) {
            withTrackerRequestGuard({ calls += "session" }) { checkTrackerRequestGuard() }
        }
        assertEquals(listOf("edition", "session"), calls)
    }

    @Test fun changedOuterReviewPreventsTheNestedRequestFromProceeding() = runTest {
        var nested = 0
        try {
            withTrackerRequestGuard({ error("edition changed") }) {
                withTrackerRequestGuard({ nested++ }) { checkTrackerRequestGuard() }
            }
            fail("Changed review must stop the request")
        } catch (_: IllegalStateException) { }
        assertEquals(0, nested)
    }
}
