package org.koitharu.kotatsu.scrobbling.anilist.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AniListProgressRetryTest {

	@Test
	fun newerProgressSurvivesAnOlderInFlightRetry() = runBlocking {
		val mutex = Mutex()
		var pending = 5
		val started = CompletableDeferred<Unit>()
		val finish = CompletableDeferred<Unit>()
		val sent = mutableListOf<Int>()
		val attempt = async {
			retryAniListProgress(mutex, { pending }, { pending = 0 }) { chapter ->
				sent += chapter
				started.complete(Unit)
				finish.await()
			}
		}
		started.await()
		mutex.withLock { pending = 7 }
		finish.complete(Unit)
		assertFalse(attempt.await())
		assertEquals(7, pending)
		assertTrue(retryAniListProgress(mutex, { pending }, { pending = 0 }) { sent += it })
		assertEquals(listOf(5, 7), sent)
		assertEquals(0, pending)
	}

	@Test
	fun failedRequestKeepsPendingProgress() = runBlocking {
		var pending = 5
		try {
			retryAniListProgress(Mutex(), { pending }, { pending = 0 }) { throw IOException("offline") }
			fail("Expected the request error")
		} catch (_: IOException) {
			assertEquals(5, pending)
		}
	}

	@Test
	fun cancellationKeepsPendingProgressAndPropagates() = runBlocking {
		var pending = 5
		try {
			retryAniListProgress(Mutex(), { pending }, { pending = 0 }) { throw CancellationException() }
			fail("Expected cancellation")
		} catch (_: CancellationException) {
			assertEquals(5, pending)
		}
	}

	@Test
	fun noPendingProgressDoesNotSendARequest() = runBlocking {
		assertTrue(retryAniListProgress(Mutex(), { 0 }, { fail("Nothing to acknowledge") }) {
			fail("Nothing to send")
		})
	}
}
