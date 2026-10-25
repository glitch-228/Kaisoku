package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceSwitchPromptDiscoveryTest {
    @Test
    fun suppressionAndObsoleteTicketsAvoidCandidateRequests() = runTest {
        var requests = 0
        assertNull(discoverSourceSwitchPrompt(1, { 1 }, { false }) { requests++; listOf("candidate") })
        assertNull(discoverSourceSwitchPrompt(1, { 2 }, { true }) { requests++; listOf("candidate") })
        assertEquals(0, requests)
    }

    @Test
    fun muteThenResumeDuringSuspendedDiscoveryCannotReplayOldPrompt() = runTest {
        var revision = 0L
        val response = CompletableDeferred<List<String>>()
        val pending = async { discoverSourceSwitchPrompt(0, { revision }, { true }) { response.await() } }
        runCurrent()
        revision = 2 // Mute and resume can be coalesced before a StateFlow collector sees either.
        response.complete(listOf("candidate"))
        assertNull(pending.await())
    }

    @Test
    fun titleChangeRejectsOtherwiseSuccessfulDiscovery() = runTest {
        var currentTitle = 1L
        val response = CompletableDeferred<List<String>>()
        val pending = async { discoverSourceSwitchPrompt(0, { 0 }, { currentTitle == 1L }) { response.await() } }
        runCurrent()
        currentTitle = 2
        response.complete(listOf("candidate"))
        assertNull(pending.await())
    }

    @Test
    fun cancellationDoesNotDeliverAndUnchangedRequestsRemainAvailable() = runTest {
        val pending = async { discoverSourceSwitchPrompt(0, { 0 }, { true }) { CompletableDeferred<String>().await() } }
        runCurrent()
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals("candidate", discoverSourceSwitchPrompt(0, { 0 }, { true }) { "candidate" })
    }
}
