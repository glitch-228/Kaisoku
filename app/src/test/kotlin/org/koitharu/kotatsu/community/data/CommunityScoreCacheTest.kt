package org.koitharu.kotatsu.community.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CommunityScoreCacheTest {
    @Test fun `empty scores are cached and parallel refreshes share a request`() = runTest {
        var now = 0L
        var calls = 0
        val cache = CommunityScoreCache { now }
        val jobs = List(5) { async { cache.refresh(false) { calls++; delay(10); emptyMap() } } }
        jobs.forEach { assertTrue(it.await().isEmpty()) }
        assertEquals(1, calls)
        now = 24 * 60 * 60 * 1000L
        cache.refresh(false) { calls++; emptyMap() }
        assertEquals(2, calls)
        cache.refresh(true) { calls++; emptyMap() }
        assertEquals(3, calls)
    }

    @Test fun `network failure retains scores but cancellation propagates`() = runTest {
        val cache = CommunityScoreCache()
        val expected = mapOf("source" to CommunitySourceScore("source", 1.0, 1.0, 1.0, 1))
        assertEquals(expected, cache.refresh(false) { expected })
        assertEquals(expected, cache.refresh(true) { throw IOException("offline") })
        try {
            cache.refresh(true) { throw CancellationException("left screen") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(expected["source"], cache.get("source"))
        }
    }
}
