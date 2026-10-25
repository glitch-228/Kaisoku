package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrackerProgressWritePolicyTest {
    private val old = TrackerRawProgress("12.5", "2")
    @Test fun raisesPreserveUnselectedVolumeAndNeverLowerNewerRemote() {
        checkTrackerProgressValues(TrackerRawProgress("13"), old, old, false, false)
        checkTrackerProgressValues(TrackerRawProgress("13"), old, old.copy(volume = "9"), false, false)
        assertNotNull(runCatching { checkTrackerProgressValues(TrackerRawProgress("13"), old,
            old.copy(number = "14"), false, false) }.exceptionOrNull())
    }
    @Test fun reviewedLowerAndUnknownWritesRequireTheSameSelectedBaseline() {
        checkTrackerProgressValues(TrackerRawProgress("5"), old, old, true, false)
        assertNotNull(runCatching { checkTrackerProgressValues(TrackerRawProgress("5"), old,
            old.copy(number = "12.6"), true, false) }.exceptionOrNull())
        checkTrackerProgressValues(TrackerRawProgress("5"), null, null, false, true)
        assertNotNull(runCatching { checkTrackerProgressValues(TrackerRawProgress("5"), null, old,
            false, true) }.exceptionOrNull())
    }
    @Test fun unknownLabelsUnsupportedUnitsAndHostileExponentsCannotBecomeAutomaticZero() {
        for (current in listOf(null, TrackerRawProgress("Extra A"), TrackerRawProgress("1e2147483647"),
            TrackerRawProgress("9", unit = "page"))) {
            assertNotNull(runCatching { checkTrackerProgressValues(TrackerRawProgress("10"), current,
                current, false, false) }.exceptionOrNull())
        }
    }
    @Test fun opaqueChapterIdsNeedReviewedUnknownConsent() {
        assertNotNull(runCatching { checkTrackerProgressValues(TrackerRawProgress(null, entryId = "opaque-next"),
            null, null, false, false) }.exceptionOrNull())
        checkTrackerProgressValues(TrackerRawProgress(null, entryId = "opaque-next"), null, null, false, true)
    }
    @Test fun requestContextIsScopedAndCannotCreateMissingEntries() = runTest {
        checkTrackerProgressWrite(null)
        try {
            withTrackerProgressWritePolicy(TrackerRawProgress("13"), old, false, false) {
                checkTrackerProgressWrite(null)
            }
            fail("A progress-only write cannot create an entry")
        } catch (_: IllegalArgumentException) { }
        checkTrackerProgressWrite(null)
    }
}
