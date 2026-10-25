package org.koitharu.kotatsu.remote.library

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.SourceContentClass
import org.koitharu.kotatsu.core.model.SourceContentPolicy

class LibraryListContentPolicyTest {
    @Test fun emptySnapshotKeepsOrdinaryQueriesAndLargeSnapshotsHaveNoBindOrExpressionDepthLimit() {
        val policy = SourceContentPolicy(false, setOf(SourceContentClass.SAFE), true)
        assertEquals("1", LibraryListContentPolicy.capture(policy, emptyMap()).allowedSql)
        val rows = (1L..1200L).associate { (it to "file:///books/$it.cbz") to RemoteLibraryContent.UNKNOWN }
        val sql = LibraryListContentPolicy.capture(policy, rows)
        assertTrue(sql.allowedSql.contains("1200"))
        assertFalse(sql.allowedSql.contains('?'))
        assertTrue(sql.allowedSql.length < 300_000)
        val huge = rows.mapKeys { (key, _) -> key.first to (key.second + "x".repeat(1024)) }
        val bounded = LibraryListContentPolicy.capture(policy, huge)
        assertTrue(bounded.allowedSql.contains("manga.manga_id IN ("))
        assertTrue(bounded.allowedSql.length < 100_000)
        assertTrue(runCatching { LibraryListContentPolicy.capture(policy,
            (1L..10_001L).associate { (it to "file:///$it") to RemoteLibraryContent.UNKNOWN }) }.isFailure)
    }

    @Test fun registrySignalsRealAcquisitionChangesButNotRepeatedPublication() {
        RemoteLibrarySourceRegistry.replaceAll(emptyList())
        val initial = RemoteLibrarySourceRegistry.changes.value
        val metadata = LibraryAcquiredMetadata("", emptySet(), emptySet(), null, RemoteLibraryContent.UNKNOWN)
        RemoteLibrarySourceRegistry.updateAcquiredMetadata(981L, "file:///private.cbz", metadata)
        assertEquals(initial + 1, RemoteLibrarySourceRegistry.changes.value)
        RemoteLibrarySourceRegistry.updateAcquiredMetadata(981L, "file:///private.cbz", metadata)
        assertEquals(initial + 1, RemoteLibrarySourceRegistry.changes.value)
        RemoteLibrarySourceRegistry.updateAcquiredMetadata(981L, "file:///private.cbz", null)
        assertEquals(initial + 2, RemoteLibrarySourceRegistry.changes.value)
    }
}
