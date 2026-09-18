package org.koitharu.kotatsu.backups.mihon

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.parser.mihon.mihonStableId

class MihonImportMappingTest {
    @Test fun `import uses the existing live source identity algorithm`() {
        val source = "mihon:eu.kanade.tachiyomi.extension.en.test/123"
        // Golden IDs produced by the bridge before the importer was introduced.
        assertEquals(66342753453796080L, mihonStableId(source, "/manga/test"))
        assertEquals(4167525564333986220L, mihonStableId(source, "/chapter/1"))
        assertEquals(4167525564333986221L, mihonStableId(source, "/chapter/2"))
    }

    @Test fun `reading and planning statuses use each service wire format`() {
        assertEquals("reading", mihonTrackingStatus(1, 1))
        assertEquals("plan_to_read", mihonTrackingStatus(1, 6))
        assertEquals("CURRENT", mihonTrackingStatus(2, 1))
        assertEquals("PLANNING", mihonTrackingStatus(2, 5))
        assertEquals("current", mihonTrackingStatus(3, 1))
        assertEquals("planned", mihonTrackingStatus(3, 5))
        assertEquals("watching", mihonTrackingStatus(4, 1))
        assertEquals("rewatching", mihonTrackingStatus(4, 6))
        assertNull(mihonTrackingStatus(999, 1))
    }
}
