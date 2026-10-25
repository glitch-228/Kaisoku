package org.koitharu.kotatsu.sync.extended.appserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class ServerExtendedBackgroundPolicyTest {
    @Test fun corruptConfigurationStopsSchedulingAndPreservesExactFile() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("extended-background-policy-").toFile()
        try {
            val state = java.io.File(directory, "state.json")
            val bytes = "{\"version\":1,\"version\":2}".toByteArray()
            state.writeBytes(bytes)
            val canceled = mutableListOf<String>()
            var enqueued = false
            val result = ServerExtendedBackgroundPolicy.run(onFailure = {
                for (name in listOf("periodic", "startup")) {
                    ServerExtendedBackgroundPolicy.run<Unit> {
                        canceled += name
                        if (name == "periodic") throw IOException("Fixture cancellation failure")
                    }
                }
            }) {
                ServerExtendedStore(directory).configuration()
                enqueued = true
            }
            assertNull(result)
            assertFalse(enqueued)
            assertEquals(listOf("periodic", "startup"), canceled)
            assertArrayEquals(bytes, state.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun workerFailureUsesFailureFallbackWithoutRetryOrRecoveryMutation() = runBlocking<Unit> {
        var attempts = 0
        val result = ServerExtendedBackgroundPolicy.run {
            attempts++
            throw IOException("Fixture private state failure")
        } ?: "failure"
        assertEquals("failure", result)
        assertEquals(1, attempts)
    }

    @Test fun successfulBackgroundActionIsReturnedWithoutFailureCleanup() = runBlocking<Unit> {
        var cleanup = false
        val result = ServerExtendedBackgroundPolicy.run(onFailure = { cleanup = true }) { "scheduled" }
        assertEquals("scheduled", result)
        assertFalse(cleanup)
    }

    @Test fun cancellationFromActionIsRethrownWithoutOrdinaryFailureCleanup() = runBlocking<Unit> {
        var cleanup = false
        val cancellation = CancellationException("Fixture cancellation")
        try {
            ServerExtendedBackgroundPolicy.run(onFailure = { cleanup = true }) { throw cancellation }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertFalse(cleanup)
    }

    @Test fun cancellationDuringFailureCleanupIsRethrown() = runBlocking<Unit> {
        val cancellation = CancellationException("Fixture cleanup cancellation")
        try {
            ServerExtendedBackgroundPolicy.run(onFailure = { throw cancellation }) {
                throw IOException("Fixture background failure")
            }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }
}
