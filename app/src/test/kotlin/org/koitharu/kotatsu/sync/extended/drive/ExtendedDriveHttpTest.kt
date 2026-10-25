package org.koitharu.kotatsu.sync.extended.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaExchange
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaFixture
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

class ExtendedDriveHttpTest {
    private val executor = Executors.newCachedThreadPool()
    private lateinit var server: ServerReplicaFixture
    private lateinit var transport: ExtendedDriveHttp
    private val owner = "opaque-permission-Id_not-email"
    private val token = "fixture-oauth-token"
    private val body = "{\"id\":\"opaque-hid\",\"progress\":12.5000000000000001}".toByteArray()
    private lateinit var file: ExtendedDriveFile
    @Volatile private var active = true
    private val received = AtomicInteger()
    @Volatile private var handler: (ServerReplicaExchange) -> Unit = { it.reply(file.metadata()) }
    private val session get() = ExtendedDriveSession(owner, token) { check(active) }

    @Before fun setUp() {
        val descriptor = ExtendedDriveDescriptor(owner, UUID.randomUUID().toString(), "a".repeat(64),
            "tracker_links_v1", 1, ExtendedDriveProtocol.hash(body), body.size)
        file = ExtendedDriveFile("allocated-file", descriptor, "1", ExtendedDriveProtocol.md5(body))
        server = ServerReplicaFixture.create(InetSocketAddress("127.0.0.1", 0))
        server.executor = executor
        server.createContext("/") { exchange -> received.incrementAndGet(); handler(exchange); exchange.close() }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        transport = ExtendedDriveHttp(OkHttpClient(), "$base/drive/v3/".toHttpUrl(), "$base/upload/drive/v3/".toHttpUrl())
    }
    @After fun tearDown() { if (::server.isInitialized) server.stop(); executor.shutdownNow() }

    @Test fun authenticatedAboutUsesOpaquePermissionIdAndNeverNeedsEmail() = runBlocking<Unit> {
        var authorization: String? = null
        var fields: String? = null
        handler = { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            fields = exchange.requestURI.rawQuery
            exchange.reply(JsonObject(mapOf("user" to JsonObject(mapOf("permissionId" to JsonPrimitive(owner),
                "me" to JsonPrimitive(true), "displayName" to JsonPrimitive("Fixture"))))))
        }
        val user = transport.user(token) { check(active) }
        assertEquals(owner, user.permissionId)
        assertNull(user.emailAddress)
        assertEquals("Bearer $token", authorization)
        assertTrue(fields!!.contains("permissionId"))
    }

    @Test fun generatesAppDataIdsAndRejectsWrongSpace() = runBlocking<Unit> {
        var query: String? = null
        handler = { exchange ->
            query = exchange.requestURI.rawQuery
            exchange.reply(JsonObject(mapOf("space" to JsonPrimitive("appDataFolder"),
                "ids" to JsonArray(listOf(JsonPrimitive("allocated-file"))))))
        }
        assertEquals("allocated-file", transport.generateId(session))
        assertTrue(query!!.contains("space=appDataFolder"))
        handler = { it.reply(JsonObject(mapOf("space" to JsonPrimitive("drive"),
            "ids" to JsonArray(listOf(JsonPrimitive("allocated-file")))))) }
        assertTrue(runCatching { transport.generateId(session) }.isFailure)
    }

    @Test fun legacyPrefixCannotEnterNewListingAndIncompleteListsFailClosed() = runBlocking<Unit> {
        var query: String? = null
        handler = { exchange ->
            query = exchange.requestURI.rawQuery
            exchange.reply(JsonObject(mapOf("incompleteSearch" to JsonPrimitive(false), "files" to JsonArray(listOf(
                file.metadata(), JsonObject(mapOf("name" to JsonPrimitive("kaisoku_replica_legacy.json.gz"))),
                JsonObject(mapOf("name" to JsonPrimitive("kaisoku_sync.json"))))))))
        }
        assertEquals(listOf(file), transport.list(session))
        assertTrue(query!!.contains("spaces=appDataFolder"))
        assertTrue(query!!.contains("kaisoku_extended_v1_"))
        handler = { it.reply(JsonObject(mapOf("incompleteSearch" to JsonPrimitive(true), "files" to JsonArray(emptyList())))) }
        assertTrue(runCatching { transport.list(session) }.isFailure)
    }

    @Test fun resumableCreateUsesSamePreallocatedIdAndExactUnchangedUtf8Body() = runBlocking<Unit> {
        var metadata: JsonObject? = null
        var uploaded: ByteArray? = null
        var range: String? = null
        handler = { exchange ->
            if (exchange.requestMethod == "POST") {
                metadata = ExtendedDriveProtocol.objectValue(exchange.requestBody.readBytes())
                exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/upload/drive/v3/session")
                exchange.reply(JsonObject(emptyMap()))
            } else {
                uploaded = exchange.requestBody.readBytes()
                range = exchange.requestHeaders.getFirst("Content-Range")
                exchange.reply(file.metadata())
            }
        }
        transport.create(session, file.id, file.descriptor, body)
        assertEquals(JsonPrimitive(file.id), metadata!!["id"])
        assertEquals(JsonArray(listOf(JsonPrimitive("appDataFolder"))), metadata!!["parents"])
        assertArrayEquals(body, uploaded)
        assertEquals("bytes 0-${body.lastIndex}/${body.size}", range)
        assertEquals(2, received.get())
    }

    @Test fun untrustedUploadSessionLocationNeverReceivesTokenOrBody() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.set("Location", "https://another.invalid/upload/drive/v3/session")
            exchange.reply(JsonObject(emptyMap()))
        }
        assertTrue(runCatching { transport.create(session, file.id, file.descriptor, body) }.isFailure)
        assertEquals(1, received.get())
    }

    @Test fun accountChangeAfterCreateInitPreventsPayloadPut() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.requestBody.readBytes()
            active = false
            exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/upload/drive/v3/session")
            exchange.reply(JsonObject(emptyMap()))
        }
        assertTrue(runCatching { transport.create(session, file.id, file.descriptor, body) }.isFailure)
        assertEquals(1, received.get())
    }

    @Test fun metadataAndBothChecksumsAreVerifiedBeforeBytesAreExposed() = runBlocking<Unit> {
        handler = { exchange ->
            if (exchange.requestURI.rawQuery?.contains("alt=media") == true) exchange.bytes(body)
            else exchange.reply(file.metadata())
        }
        assertArrayEquals(body, transport.download(session, file))
        handler = { exchange ->
            if (exchange.requestURI.rawQuery?.contains("alt=media") == true) exchange.bytes("{}".toByteArray())
            else exchange.reply(file.metadata())
        }
        assertTrue(runCatching { transport.download(session, file) }.isFailure)
    }

    @Test fun fileMetadataFromDifferentAccountIsRejected() = runBlocking<Unit> {
        handler = { it.reply(file.metadata()) }
        val other = ExtendedDriveSession("another-opaque-permission", token) {}
        assertTrue(runCatching { transport.metadata(other, file.id) }.isFailure)
    }

    @Test fun create409DoesNotTriggerAutomaticTransportRetry() = runBlocking<Unit> {
        handler = { exchange -> exchange.requestBody.readBytes(); exchange.reply(JsonObject(emptyMap()), 409) }
        val exception = runCatching { transport.create(session, file.id, file.descriptor, body) }.exceptionOrNull()
        assertEquals(409, (exception as ExtendedDriveHttpException).status)
        assertEquals(1, received.get())
    }

    @Test fun responseMetadataBudgetIsBounded() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2 * 1024 * 1024L + 1)
        }
        assertTrue(runCatching { transport.user(token) {} }.isFailure)
    }

    @Test fun noInheritedInvalidTlsTrustOrHostnameBypass() = runBlocking<Unit> {
        val certificate = HeldCertificate.Builder().commonName("127.0.0.1").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val https = ServerReplicaFixture.create(InetSocketAddress("127.0.0.1", 0), serverTls.sslContext())
        val actual = AtomicInteger()
        https.executor = executor
        https.createContext("/") { exchange -> actual.incrementAndGet(); exchange.reply(JsonObject(emptyMap())); exchange.close() }
        https.start()
        val unsafe = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val publicClient = OkHttpClient.Builder().sslSocketFactory(unsafe.sslSocketFactory(), unsafe.trustManager)
            .hostnameVerifier { _, _ -> true }.build()
        try {
            val secure = ExtendedDriveHttp(publicClient, "https://127.0.0.1:${https.address.port}/drive/v3/".toHttpUrl(),
                "https://127.0.0.1:${https.address.port}/upload/drive/v3/".toHttpUrl())
            val exception = runCatching { secure.user(token) {} }.exceptionOrNull()
            assertTrue(exception is SSLHandshakeException)
            assertEquals(0, actual.get())
        } finally { https.stop() }
    }

    @Test fun allHttpOperationsShareTwoRequestBound() = runBlocking<Unit> {
        val active = AtomicInteger(); val peak = AtomicInteger()
        handler = { exchange ->
            val value = active.incrementAndGet(); peak.updateAndGet { maxOf(it, value) }
            try {
                Thread.sleep(25)
                exchange.reply(JsonObject(mapOf("user" to JsonObject(mapOf("permissionId" to JsonPrimitive(owner),
                    "me" to JsonPrimitive(true))))))
            } finally { active.decrementAndGet() }
        }
        coroutineScope { (0 until 8).map { async(Dispatchers.Default) { transport.user(token) {} } }.awaitAll() }
        assertEquals(2, peak.get())
    }

    private fun ServerReplicaExchange.reply(value: JsonObject, status: Int = 200) = bytes(value.toString().toByteArray(), status)
    private fun ServerReplicaExchange.bytes(value: ByteArray, status: Int = 200) {
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, value.size.toLong())
        responseBody.use { it.write(value) }
    }
}
