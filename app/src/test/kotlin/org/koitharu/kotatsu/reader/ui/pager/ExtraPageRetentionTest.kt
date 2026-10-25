package org.koitharu.kotatsu.reader.ui.pager

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraPageRetentionTest {
    @Test fun defaultsAndLowMemoryNeverIncreaseRetention() {
        for (default in listOf(2, 4)) {
            assertEquals(default, retainedPageCount(false, false, 512, default))
            assertEquals(default, retainedPageCount(true, true, 512, default))
            assertEquals(default, retainedPageCount(true, false, 128, default))
        }
    }

    @Test fun extraHoldersHaveAHardUpperBound() {
        for (default in listOf(2, 4)) {
            assertEquals(default + 1, retainedPageCount(true, false, 256, default))
            assertEquals(default + 2, retainedPageCount(true, false, 384, default))
            assertEquals(default + 2, retainedPageCount(true, false, Int.MAX_VALUE, default))
        }
    }
}
