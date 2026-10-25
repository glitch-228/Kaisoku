package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RemoteLibraryTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun directReaderBodyFailuresKeepPrivateCopyableDiagnostics() = runBlocking {
        LibraryHttpFixture { _, socket ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nshort".toByteArray())
            socket.getOutputStream().flush()
        }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { null })
            val failure = runCatching {
                transport.imageClient.newCall(transport.pageRequest(server.base + "page?signature=private-capability"))
                    .execute().use { it.body.string() }
            }.exceptionOrNull()
            assertTrue(failure is RemoteLibraryException)
            assertFalse(requireNotNull(failure).getCopyableErrorDetails().contains("private-capability"))
            assertTrue(failure.getCopyableErrorDetails().contains("Caused by:"))
        }
    }

    @Test fun sameScopeRedirectPreservesScopedBasicAuthAndOnlyUsesGet() = runBlocking {
        LibraryHttpFixture { request, socket ->
            if (request.path.endsWith("first")) socket.respond("", 302, headers = mapOf("Location" to "/library/final"))
            else socket.respond("fixture")
        }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { RemoteLibraryCredentials("reader", "secret") })
            val result = transport.document(server.base + "first")
            assertEquals("fixture", result.bytes.toString(Charsets.UTF_8))
            assertEquals(server.base + "final", result.url)
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.all { it.method == "GET" && it.headers["authorization"]?.startsWith("Basic ") == true })
            assertTrue(server.requests.none { it.headers.containsKey("cookie") })
        }
    }

    @Test fun redirectsCannotEscapeTheCredentialDirectoryAndNonGetRequestsAreRejected() = runBlocking {
        LibraryHttpFixture { _, socket -> socket.respond("", 302, headers = mapOf("Location" to "/other/secret")) }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { RemoteLibraryCredentials("reader", "secret") })
            val failure = runCatching { transport.document(server.base + "redirect") }.exceptionOrNull()
            assertTrue(failure is LibraryScopeException)
            assertEquals(1, server.requests.size)
            val request = okhttp3.Request.Builder().url(server.base + "progress").delete().build()
            assertThrows(RemoteLibraryException::class.java) { transport.imageClient.newCall(request).execute().close() }
            assertEquals(1, server.requests.size)
        }
    }

    @Test fun foreignAcquisitionIsAnonymousOnlyAfterExplicitConsent() = runBlocking {
        LibraryHttpFixture { _, socket -> socket.respond("book bytes", contentType = "application/epub+zip") }.use { foreign ->
            LibraryHttpFixture { _, socket -> socket.respond("unused") }.use { origin ->
                val loads = AtomicInteger()
                val transport = RemoteLibraryTransport(origin.policy, { loads.incrementAndGet(); RemoteLibraryCredentials("reader", "secret") })
                val link = LibraryLink(foreign.base + "book", "application/epub+zip", setOf("download"))
                val stage = java.io.File(temporary.root, "book.epub")
                assertTrue(runCatching { transport.download(link, stage) }.exceptionOrNull() is LibraryScopeException)
                assertFalse(stage.exists())
                val checksum = transport.download(link, stage, true)
                assertEquals(64, checksum.length)
                assertEquals("book bytes", stage.readText())
                assertEquals(0, loads.get())
                assertEquals(1, foreign.requests.size)
                assertNull(foreign.requests.single().headers["authorization"])
                assertNull(foreign.requests.single().headers["cookie"])
                assertTrue(origin.requests.isEmpty())
            }
        }
    }

    @Test fun sizeAndChecksumFailuresRemoveOnlyTheNewStageAndPreserveExistingFiles() = runBlocking {
        LibraryHttpFixture { _, socket -> socket.respond("12345678", contentType = "application/zip") }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { null }, maximumAcquisitionBytes = 16)
            val link = LibraryLink(server.base + "book", "application/vnd.comicbook+zip", setOf("download"), size = 9)
            val stage = java.io.File(temporary.root, "book.cbz")
            assertTrue(runCatching { transport.download(link, stage) }.exceptionOrNull() is RemoteLibraryException)
            assertFalse(stage.exists())
            assertTrue(runCatching { transport.download(link.copy(size = 8, hash = "sha256:" + "0".repeat(64)), stage) }.exceptionOrNull() is RemoteLibraryException)
            assertFalse(stage.exists())
            assertTrue(runCatching { transport.download(link.copy(size = 17), stage) }.exceptionOrNull() is LibraryLimitException)
            assertFalse(stage.exists())
            stage.writeText("existing original")
            assertTrue(runCatching { transport.download(link, stage) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals("existing original", stage.readText())
        }
    }

    @Test fun cancellingAResponseBodyClosesTheCallAndRemovesTheStage() = runBlocking {
        val received = CountDownLatch(1)
        LibraryHttpFixture { _, socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nfirst".toByteArray())
                flush()
            }
            received.countDown()
            runCatching { socket.getInputStream().read() }
        }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { null })
            val stage = java.io.File(temporary.root, "cancelled.cbz")
            val job = launch(Dispatchers.IO) { transport.download(LibraryLink(server.base + "book", "application/zip", setOf("download")), stage) }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertFalse(stage.exists())
        }
    }

    @Test fun actualHttpOperationsAreBoundedAtTwo() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        LibraryHttpFixture { _, socket ->
            val current = active.incrementAndGet()
            maximum.accumulateAndGet(current, ::maxOf)
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            active.decrementAndGet()
            socket.respond("done")
        }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { null })
            val tasks = (1..4).map { index -> async(Dispatchers.IO) { transport.document(server.base + index) } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(2, maximum.get())
            release.countDown()
            withTimeout(5000) { tasks.forEach { it.await() } }
            assertEquals(2, maximum.get())
            assertEquals(4, server.requests.size)
        }
    }

    @Test fun authenticationAndOversizedDocumentsHaveUsefulBoundedErrors() = runBlocking {
        LibraryHttpFixture { request, socket ->
            if (request.path.endsWith("auth")) socket.respond("password must not enter diagnostics", 401)
            else socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\nConnection: close\r\n\r\n".toByteArray()); flush()
            }
        }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { null })
            val authentication = runCatching { transport.document(server.base + "auth") }.exceptionOrNull()
            assertTrue(authentication is LibraryAuthenticationException)
            assertFalse(authentication!!.message!!.contains("password must not"))
            assertTrue(runCatching { transport.document(server.base + "large") }.exceptionOrNull() is LibraryLimitException)
        }
    }
}
