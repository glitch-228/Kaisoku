package org.koitharu.kotatsu.backups.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebDavTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun endpointAndChildrenRejectTraversalEncodedSeparatorsCredentialsAndExternalOrigins() {
        val endpoint = WebDavEndpoint.parse("https://example.test/dav/books")
        assertEquals("https://example.test/dav/books/", endpoint.toString())
        assertEquals("one.bk.zip", endpoint.childName("/dav/books/one.bk.zip"))
        for (value in listOf("https://other.test/dav/books/one.bk.zip", "/dav/books2/one.bk.zip",
            "/dav/books/sub/one.bk.zip", "../one.bk.zip", "%2e%2e/one.bk.zip", "a%2fb.bk.zip",
            "a%252fb.bk.zip", "https://user:pass@example.test/dav/books/one.bk.zip", "one.bk.zip?secret=1")) {
            assertNull(value, endpoint.childName(value))
        }
        for (value in listOf("http://public.example/dav/", "https://user:pass@example.test/dav/",
            "https://example.test/a/../dav/", "https://example.test/a%2fdav/", "https://example.test/dav/?x=1")) {
            assertThrows(IllegalArgumentException::class.java) { WebDavEndpoint.parse(value, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { WebDavEndpoint.parse("http://127.0.0.1/dav/") }
        assertTrue(WebDavEndpoint.parse("http://192.168.1.2/dav/", true).url.host.startsWith("192.168"))
        assertTrue(WebDavEndpoint.parse("http://[::1]/dav/", true).isCollection("/dav/"))
    }

    @Test fun depthOneDiscoveryAndDownloadUseOnlySupportedDirectChildrenAndScopedAuth() = runTest {
        WebDavFixture().use { fixture ->
            fixture.put("one.bk.zip", "native-data".toByteArray())
            fixture.put(".incomplete.bk.zip.part", byteArrayOf(1))
            fixture.put("unrelated.txt", byteArrayOf(2))
            fixture.objects["nested/hidden.bk.zip"] = WebDavFixture.Object(byteArrayOf(3), "\"outside-direct-children\"")
            val transport = WebDavTransport(fixture.connection)
            val listing = transport.list()
            assertEquals(listOf("one.bk.zip"), listing.map { it.name })
            val file = temporary.newFile()
            assertEquals(WebDavPublisher.hashFile(file.apply { writeText("native-data") }),
                transport.download(listing.single(), file))
            assertEquals("native-data", file.readText())
            assertTrue(fixture.calls.all { it.authorization?.startsWith("Basic ") == true && it.path.startsWith("/dav/") })
        }
    }

    @Test fun redirectsAreNotFollowedAndCopyableDiagnosticsRedactEchoedSecrets() = runTest {
        WebDavFixture().use { fixture ->
            fixture.handler = { exchange ->
                exchange.responseHeaders.add("Location", "https://external.invalid/steal")
                WebDavFixture.reply(exchange, 302, "secret<&> secret&lt;&amp;&gt; alice " +
                    "Authorization: ${exchange.requestHeaders.getFirst("Authorization")}\n" +
                    "${exchange.requestHeaders.getFirst("Authorization")}")
                true
            }
            val failure = runCatching { WebDavTransport(fixture.connection).list() }.exceptionOrNull()
            assertTrue(failure is WebDavFailure)
            val details = (failure as WebDavFailure).copyableErrorDetails
            assertFalse(details.contains("secret<&>"))
            assertFalse(details.contains("secret&lt;&amp;&gt;"))
            assertFalse(details.contains("alice"))
            assertFalse(details.contains("Basic Y"))
            assertFalse(details.contains(okhttp3.Credentials.basic("alice", "secret<&>").substringAfter(' ')))
            assertEquals(1, fixture.calls.size)
        }
    }

    @Test fun longRetryAfterBlocksFollowingCallsWithoutExtraRequests() = runTest {
        WebDavFixture().use { fixture ->
            fixture.handler = { exchange ->
                exchange.responseHeaders.add("Retry-After", "120")
                WebDavFixture.reply(exchange, 429, "busy")
                true
            }
            val transport = WebDavTransport(fixture.connection)
            repeat(2) {
                val failure = runCatching { transport.list() }.exceptionOrNull()
                assertTrue(failure is WebDavFailure && failure.retryAfterMillis > 90_000L)
            }
            assertEquals(1, fixture.calls.size)
            assertEquals(120_000L, WebDavTransport.retryAfter("120", 0))
            assertEquals(0L, WebDavTransport.retryAfter("Thu, 01 Jan 1970 00:00:00 GMT", 1))
        }
    }

    @Test fun oversizedChunkedDownloadAndInvalidXmlFailWithoutExternalEntityRequests() = runTest {
        WebDavFixture().use { fixture ->
            val resource = fixture.put("one.bk.zip", byteArrayOf(1, 2, 3, 4))
            fixture.handler = { exchange ->
                if (exchange.requestMethod == "GET") {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { it.write(ByteArray(128)) }
                    true
                } else false
            }
            val failure = runCatching { WebDavTransport(fixture.connection).download(resource, temporary.newFile()) }.exceptionOrNull()
            assertTrue(failure is IOException)
            fixture.handler = { exchange ->
                WebDavFixture.reply(exchange, 207, "<!DOCTYPE x [<!ENTITY leak SYSTEM 'http://127.0.0.1:${fixture.connection.endpoint.url.port}/leak'>]><d:multistatus xmlns:d='DAV:'>&leak;</d:multistatus>")
                true
            }
            assertTrue(runCatching { WebDavTransport(fixture.connection).list() }.exceptionOrNull() is IOException)
            assertTrue(fixture.calls.none { it.path == "/leak" })
        }
    }

    @Test fun cancellationClosesInFlightRequestAndDoesNotRetry() = runTest {
        WebDavFixture().use { fixture ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.handler = { exchange ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                WebDavFixture.reply(exchange, 207, "<d:multistatus xmlns:d='DAV:'/>")
                true
            }
            val pending = async { WebDavTransport(fixture.connection).list() }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
                assertEquals(1, fixture.calls.size)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun privateClientHasNoSharedCacheInterceptorsCookiesRedirectsOrImplicitRetries() {
        val client = WebDavTransport.privateClient()
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertNull(client.cache)
        assertSame(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }
}
