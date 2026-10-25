package org.koitharu.kotatsu.sync.extended.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

class ServerReplicaClientTest {
    private lateinit var server: ServerReplicaFixture
    private lateinit var directory: File
    private val executor = Executors.newCachedThreadPool()
    private lateinit var session: ServerReplicaSession
    @Volatile private var active = true
    private val account = "9007199254740993"
    private val device = "12345678-1234-4234-8234-123456789abc"
    private val namespace = "chapter_reads_v1"
    private val payload = "{\"id\":\"opaque-hid\",\"chapter\":12.500000000000001,\"deleted\":true}".toByteArray()
    private val calls = AtomicInteger()
    @Volatile private var handler: (ServerReplicaExchange) -> Unit = { it.reply(200, manifest()) }

    @Before fun setUp() {
        directory = Files.createTempDirectory("extended-server-client-").toFile()
        server = ServerReplicaFixture.create(InetSocketAddress("127.0.0.1", 0))
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestURI.path == "/resource/v2/capabilities") exchange.reply(200, caps())
                else { calls.incrementAndGet(); handler(exchange) }
            } finally { exchange.close() }
        }
        server.start()
        session = ServerReplicaSession(ServerReplicaScope(ServerReplicaEndpoint.parse(
            "http://127.0.0.1:${server.address.port}", allowCleartextPrivateAddress = true), account),
            "fixture-token", "auth-generation-1") { check(active) { "Stale fixture session" } }
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.stop()
        executor.shutdownNow()
        directory.deleteRecursively()
    }

    @Test fun endpointRequiresHttpsAndExplicitPrivateLiteral() {
        listOf("http://127.0.0.1", "http://example.com", "https://user:pass@example.com", "https://example.com?q=1",
            "https://example.com#fragment").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ServerReplicaEndpoint.parse(value) }
        }
        listOf("http://example.com", "http://8.8.8.8", "http://localhost", "http://169.254.1.2").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerReplicaEndpoint.parse(value, allowCleartextPrivateAddress = true)
            }
        }
        listOf("http://127.0.0.1", "http://10.2.3.4", "http://172.16.0.1", "http://192.168.2.3", "http://[::1]",
            "http://[fd00::1]").forEach { ServerReplicaEndpoint.parse(it, allowCleartextPrivateAddress = true) }
        assertEquals("https://example.com/prefix/resource/v2/capabilities",
            ServerReplicaEndpoint.parse("https://example.com/prefix/").resource("capabilities").toString())
    }

    @Test fun negotiationBindsExactAccountAndBearerWithoutCookies() = runBlocking<Unit> {
        handler = { exchange ->
            assertEquals("Bearer fixture-token", exchange.requestHeaders.getFirst("Authorization"))
            assertNull(exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Set-Cookie", "session=ignored")
            exchange.reply(200, manifest())
        }
        val connection = connect()
        assertEquals(account, connection.capabilities.accountId)
        connection.manifest()
        connection.manifest()
        assertEquals(2, calls.get())
    }

    @Test fun capabilityAccountAndCasMismatchFailClosed() = runBlocking<Unit> {
        val wrong = ServerReplicaSession(ServerReplicaScope(session.scope.endpoint, "7"), "fixture-token", "other") {}
        failure<ServerReplicaProtocolException> { ServerReplicaClient(journal()).negotiate(wrong) }
        val root = ServerReplicaWire.objectValue(caps()).toMutableMap()
        root["compareAndSwap"] = JsonPrimitive(false)
        failure<ServerReplicaProtocolException> {
            ServerReplicaWire.capabilities(JsonObject(root).toString().toByteArray(), session.scope)
        }
        root["compareAndSwap"] = JsonPrimitive(true)
        root["accountId"] = JsonPrimitive(9007199254740993L)
        failure<ServerReplicaProtocolException> {
            ServerReplicaWire.capabilities(JsonObject(root).toString().toByteArray(), session.scope)
        }
    }

    @Test fun manifestAccepts64LanesAndRejectsDuplicateOverflowAndPagination() = runBlocking<Unit> {
        val connection = connect()
        val items = (0 until 64).map { index -> metadata(deviceId = "12345678-1234-4234-8234-%012d".format(index)) }
        handler = { it.reply(200, manifest(items)) }
        assertEquals(64, connection.manifest().size)
        handler = { it.reply(200, manifest(items + items.first())) }
        failure<ServerReplicaProtocolException> { connection.manifest() }
        handler = { it.reply(200, manifest(listOf(items.first(), items.first()))) }
        failure<ServerReplicaProtocolException> { connection.manifest() }
        handler = { it.reply(200, "{\"protocol\":1,\"replicas\":[],\"nextCursor\":\"next\"}".toByteArray()) }
        failure<ServerReplicaProtocolException> { connection.manifest() }
    }

    @Test fun downloadPreservesExactRawBytesAndConditional304() = runBlocking<Unit> {
        val meta = metadata()
        handler = { exchange ->
            exchange.payloadHeaders(meta)
            if (exchange.requestHeaders.getFirst("If-None-Match") == meta.etag) exchange.reply(304, ByteArray(0))
            else exchange.reply(200, payload)
        }
        val connection = connect()
        val downloaded = connection.download(namespace, device) as ServerReplicaDownload.Downloaded
        assertArrayEquals(payload, downloaded.bytes())
        val copy = downloaded.bytes(); copy[0] = 0
        assertArrayEquals(payload, downloaded.bytes())
        assertEquals(ServerReplicaDownload.NotModified(meta.etag), connection.download(namespace, device, meta.etag))
    }

    @Test fun unexpected304WrongHashWeakEtagAndVersionAreRejected() = runBlocking<Unit> {
        val connection = connect()
        handler = { it.payloadHeaders(metadata()); it.reply(304, ByteArray(0)) }
        failure<ServerReplicaProtocolException> { connection.download(namespace, device) }
        handler = { it.payloadHeaders(metadata(bytes = "{}".toByteArray())); it.reply(200, payload) }
        failure<ServerReplicaProtocolException> { connection.download(namespace, device) }
        handler = {
            it.payloadHeaders(metadata())
            it.responseHeaders.set("ETag", "W/${metadata().etag}")
            it.reply(200, payload)
        }
        failure<ServerReplicaProtocolException> { connection.download(namespace, device) }
        handler = {
            it.payloadHeaders(metadata())
            it.responseHeaders.set("X-Replica-Version", "2")
            it.reply(200, payload)
        }
        failure<ServerReplicaProtocolException> { connection.download(namespace, device) }
    }

    @Test fun strictPayloadRejectsMalformedUtf8DuplicatesNumbersSurrogatesAndDepth() {
        val bad = listOf(byteArrayOf(123, 34, 120, 34, 58, 34, 0xc0.toByte(), 0xaf.toByte(), 34, 125),
            "{\"a\":1,\"\\u0061\":2}".toByteArray(), "{\"x\":01}".toByteArray(),
            "{\"x\":\"\\ud800\"}".toByteArray(), "{\"x\":\"\\udc00\"}".toByteArray(),
            ("{\"x\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}").toByteArray(), "{} false".toByteArray())
        bad.forEach { bytes ->
            assertThrows(ServerReplicaProtocolException::class.java) { ServerReplicaJson.validate(bytes) }
        }
        assertEquals(payload.toString(Charsets.UTF_8), ServerReplicaJson.validate(payload))
        ServerReplicaJson.validate("{\"x\":\"\\ud83d\\ude00\",\"huge\":1e9999999}".toByteArray())
    }

    @Test fun malformedPayloadNeverReachesUploadAndUnknownPendingCannotBeReplacedOrAcknowledged() = runBlocking<Unit> {
        val connection = connect()
        failure<ServerReplicaProtocolException> {
            connection.prepareUpload(namespace, device, "{\"a\":1,\"a\":2}".toByteArray(),
                ServerReplicaCondition.CreateIfNoneMatch)
        }
        val pending = connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        failure<IllegalStateException> {
            connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        }
        failure<IllegalStateException> { connection.acknowledge(namespace, device, pending.requestId) }
        assertEquals(0, calls.get())
    }

    @Test fun createAndUpdateUseDistinctCasAndDurableReceipts() = runBlocking<Unit> {
        val connection = connect()
        handler = { exchange ->
            assertEquals("PUT", exchange.requestMethod)
            assertEquals("*", exchange.requestHeaders.getFirst("If-None-Match"))
            assertNull(exchange.requestHeaders.getFirst("If-Match"))
            assertEquals("1", exchange.requestHeaders.getFirst("X-Replica-Version"))
            assertEquals(ServerReplicaProtocol.sha256(payload), exchange.requestHeaders.getFirst("X-Content-SHA256"))
            assertArrayEquals(payload, exchange.requestBody.readBytes())
            exchange.receipt(metadata(), metadata(), false, 201)
        }
        val request = connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        val accepted = connection.uploadPending(namespace, device)
        assertTrue(accepted.outcome is ServerReplicaUploadOutcome.Accepted)
        val restored = FileServerReplicaJournal(directory).load(request.lane)!!
        assertEquals(accepted.outcome, restored.outcome)
        connection.acknowledge(namespace, device, request.requestId)
        assertNull(connection.pending(namespace, device))
        handler = { exchange ->
            assertEquals(metadata().etag, exchange.requestHeaders.getFirst("If-Match"))
            assertNull(exchange.requestHeaders.getFirst("If-None-Match"))
            exchange.requestBody.readBytes()
            exchange.receipt(metadata(2), metadata(2), false)
        }
        connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.UpdateIfMatch(metadata().etag))
        connection.uploadPending(namespace, device)
        assertEquals(2, calls.get())
    }

    @Test fun lostResponseReplaysSameDurableKeyBodyAndConditionAfterRecreation() = runBlocking<Unit> {
        var key: String? = null
        handler = { exchange ->
            key = exchange.requestHeaders.getFirst("Idempotency-Key")
            assertArrayEquals(payload, exchange.requestBody.readBytes())
            exchange.close()
        }
        val first = connect()
        val pending = first.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        failure<IOException> { first.uploadPending(namespace, device) }
        assertEquals(1, calls.get()) // the transport did not automatically retry a write
        assertEquals(pending.requestId, key)
        handler = { exchange ->
            assertEquals(key, exchange.requestHeaders.getFirst("Idempotency-Key"))
            assertEquals("*", exchange.requestHeaders.getFirst("If-None-Match"))
            assertArrayEquals(payload, exchange.requestBody.readBytes())
            exchange.receipt(metadata(), metadata(2, "{\"newer\":true}".toByteArray()), true)
        }
        val second = connect()
        val recovered = second.uploadPending(namespace, device).outcome as ServerReplicaUploadOutcome.Accepted
        assertTrue(recovered.receipt.replayed)
        assertEquals("1", recovered.receipt.accepted.revision)
        assertEquals("2", recovered.receipt.current.revision)
        assertEquals(2, calls.get())
        second.uploadPending(namespace, device) // terminal result is read locally
        assertEquals(2, calls.get())
    }

    @Test fun staleCasIsDurableConflictAndRequiresExplicitAcknowledgement() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.requestBody.readBytes()
            exchange.reply(412, JsonObject(mapOf("error" to JsonPrimitive("stale_replica"),
                "current" to ServerReplicaWire.metadataJson(metadata(3)))).toString().toByteArray())
        }
        val connection = connect()
        val pending = connection.prepareUpload(namespace, device, payload,
            ServerReplicaCondition.UpdateIfMatch(metadata().etag))
        val conflict = connection.uploadPending(namespace, device).outcome as ServerReplicaUploadOutcome.Conflict
        assertEquals("3", conflict.current!!.revision)
        assertEquals(conflict, FileServerReplicaJournal(directory).load(pending.lane)!!.outcome)
        failure<IllegalStateException> {
            connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.UpdateIfMatch(metadata(3).etag))
        }
        connection.acknowledge(namespace, device, pending.requestId)
        connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.UpdateIfMatch(metadata(3).etag))
    }

    @Test fun mismatchingSuccessReceiptStaysPending() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.requestBody.readBytes()
            exchange.receipt(metadata(bytes = "{}".toByteArray()), metadata(), false, 201)
        }
        val connection = connect()
        connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        failure<ServerReplicaProtocolException> { connection.uploadPending(namespace, device) }
        assertNull(connection.pending(namespace, device)!!.outcome)
    }

    @Test fun transientFailuresStayPendingAndNoAutomatic503Retry() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.set("Retry-After", "0")
            exchange.reply(503, "{\"error\":\"unavailable\"}".toByteArray())
        }
        val connection = connect()
        connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        failure<ServerReplicaHttpException> { connection.uploadPending(namespace, device) }
        assertEquals(1, calls.get())
        assertNull(connection.pending(namespace, device)!!.outcome)
    }

    @Test fun sessionChangeBeforeNetworkAndAfterReplyPreventsCommit() = runBlocking<Unit> {
        val connection = connect()
        connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        active = false
        failure<IllegalStateException> { connection.uploadPending(namespace, device) }
        assertEquals(0, calls.get())
        active = true
        handler = { exchange ->
            exchange.requestBody.readBytes()
            active = false
            exchange.receipt(metadata(), metadata(), false, 201)
        }
        failure<IllegalStateException> { connection.uploadPending(namespace, device) }
        active = true
        assertNull(connection.pending(namespace, device)!!.outcome)
        assertEquals(1, calls.get())
    }

    @Test fun cancellationDuringUploadRetainsPendingAcrossReconnect() = runBlocking<Unit> {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        handler = { exchange ->
            exchange.requestBody.readBytes()
            arrived.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.receipt(metadata(), metadata(), false, 201)
        }
        val connection = connect()
        val pending = connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        val operation = launch(Dispatchers.Default) { connection.uploadPending(namespace, device) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        operation.cancelAndJoin()
        release.countDown()
        assertNull(FileServerReplicaJournal(directory).load(pending.lane)!!.outcome)
    }

    @Test fun journalAccountServerScopeIsolationAndRefreshRecovery() = runBlocking<Unit> {
        val connection = connect()
        val pending = connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        val refreshed = ServerReplicaSession(session.scope, "fresh-token", "auth-generation-2") {}
        val recovered = ServerReplicaClient(journal()).negotiate(refreshed).pending(namespace, device)!!
        assertEquals(pending.requestId, recovered.requestId)
        assertNull(journal().load(
            ServerReplicaLane(ServerReplicaScope(session.scope.endpoint, "2"), namespace, device)))
        val otherEndpoint = ServerReplicaEndpoint.parse("https://another.example")
        assertNull(journal().load(ServerReplicaLane(ServerReplicaScope(otherEndpoint, account), namespace, device)))
        val persisted = directory.listFiles()!!.filter { it.extension == "replica" }.single().readBytes()
        assertFalse(persisted.toString(Charsets.ISO_8859_1).contains("fixture-token"))
        assertFalse(persisted.toString(Charsets.ISO_8859_1).contains("fresh-token"))
    }

    @Test fun journalCorruptionFailsClosedWithoutOverwritingUnknownRequest() = runBlocking<Unit> {
        val connection = connect()
        connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        val file = directory.listFiles()!!.single { it.extension == "replica" }
        file.appendBytes(byteArrayOf(1))
        failure<IOException> { connection.pending(namespace, device) }
        failure<IOException> {
            connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        }
        assertEquals(0, calls.get())
    }

    @Test fun redirectCannotSendCredentialsToAnotherEndpoint() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/redirect-target")
            exchange.reply(302, ByteArray(0))
        }
        failure<ServerReplicaHttpException> { connect().manifest() }
        assertEquals(1, calls.get())
    }

    @Test fun globalTransportAllowsAtMostTwoConcurrentRequestsAcrossClients() = runBlocking<Unit> {
        val current = AtomicInteger()
        val peak = AtomicInteger()
        handler = { exchange ->
            val count = current.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try { Thread.sleep(50); exchange.reply(200, manifest()) } finally { current.decrementAndGet() }
        }
        val one = connect()
        val two = connect()
        coroutineScope {
            (0 until 8).map { async(Dispatchers.Default) { (if (it % 2 == 0) one else two).manifest() } }.awaitAll()
        }
        assertEquals(2, peak.get())
        assertEquals(8, calls.get())
    }

    @Test fun boundedMetadataAndDeclaredPayloadAreRejected() = runBlocking<Unit> {
        val connection = connect()
        handler = { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, (ServerReplicaWire.METADATA_BYTES + 1).toLong())
        }
        failure<ServerReplicaProtocolException> { connection.manifest() }
        val small = caps(maximum = 32)
        val caps = ServerReplicaWire.capabilities(small, session.scope)
        val bounded = ServerReplicaConnection(ServerReplicaClient(journal()), journal(), session, caps)
        handler = { exchange -> exchange.payloadHeaders(metadata()); exchange.reply(200, ByteArray(33) { 32 }) }
        failure<ServerReplicaProtocolException> { bounded.download(namespace, device) }
    }

    @Test fun untrustedTlsCertificateIsRejectedWithNoBypass() = runBlocking<Unit> {
        val certificate = HeldCertificate.Builder().commonName("127.0.0.1")
            .addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val https = ServerReplicaFixture.create(InetSocketAddress("127.0.0.1", 0), tls.sslContext())
        val received = AtomicInteger()
        https.executor = executor
        https.createContext("/") { exchange ->
            received.incrementAndGet()
            exchange.reply(200, caps())
            exchange.close()
        }
        https.start()
        try {
            val endpoint = ServerReplicaEndpoint.parse("https://127.0.0.1:${https.address.port}")
            val secure = ServerReplicaSession(ServerReplicaScope(endpoint, account), "fixture-token", "tls") {}
            failure<SSLHandshakeException> { ServerReplicaClient(journal()).negotiate(secure) }
            assertEquals(0, received.get())
        } finally { https.stop() }
    }

    @Test fun unknownLengthChunkedPayloadIsBounded() = runBlocking<Unit> {
        val caps = ServerReplicaWire.capabilities(caps(maximum = 32), session.scope)
        val connection = ServerReplicaConnection(ServerReplicaClient(journal()), journal(), session, caps)
        handler = { exchange ->
            exchange.payloadHeaders(metadata())
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(ByteArray(100) { 32 }) }
        }
        failure<ServerReplicaProtocolException> { connection.download(namespace, device) }
    }

    @Test fun authenticatedRejectionIsRecordedWithoutAuthenticatorOrAutomaticWriteRetry() = runBlocking<Unit> {
        handler = { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer realm=fixture")
            exchange.reply(401, "{\"error\":\"unknown_account\"}".toByteArray())
        }
        val connection = connect()
        val request = connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        val rejection = connection.uploadPending(namespace, device).outcome as ServerReplicaUploadOutcome.Rejected
        assertEquals(401, rejection.status)
        assertEquals(1, calls.get())
        assertEquals(rejection, FileServerReplicaJournal(directory).load(request.lane)!!.outcome)
    }

    @Test fun knownQuotaResponseIsDefinitiveAndUnknown507RemainsPending() = runBlocking<Unit> {
        val connection = connect()
        handler = { exchange ->
            exchange.requestBody.readBytes()
            exchange.reply(507, "{\"error\":\"replica_storage_quota\"}".toByteArray())
        }
        val first = connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        val rejected = connection.uploadPending(namespace, device).outcome as ServerReplicaUploadOutcome.Rejected
        assertEquals(507, rejected.status)
        assertEquals(rejected, FileServerReplicaJournal(directory).load(first.lane)!!.outcome)
        connection.acknowledge(namespace, device, first.requestId)
        handler = { exchange ->
            exchange.requestBody.readBytes()
            exchange.reply(507, "{\"error\":\"unknown_failure\"}".toByteArray())
        }
        connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        failure<ServerReplicaHttpException> { connection.uploadPending(namespace, device) }
        assertNull(connection.pending(namespace, device)!!.outcome)
    }

    @Test fun journalDetectsMutationKeyCorruptionEvenWhenUuidRemainsValid() = runBlocking<Unit> {
        val connection = connect()
        val request = connection.prepareUpload(namespace, device, payload, ServerReplicaCondition.CreateIfNoneMatch)
        val file = directory.listFiles()!!.single { it.extension == "replica" }
        val bytes = file.readBytes()
        val needle = request.requestId.toByteArray()
        val index = (0..bytes.size - needle.size).first { offset ->
            needle.indices.all { bytes[offset + it] == needle[it] }
        }
        bytes[index] = if (bytes[index] == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte()
        file.writeBytes(bytes)
        failure<IOException> { connection.pending(namespace, device) }
        assertEquals(0, calls.get())
    }

    private suspend fun connect(): ServerReplicaConnection = ServerReplicaClient(journal()).negotiate(session)
    private fun journal() = FileServerReplicaJournal(directory)
    private fun caps(maximum: Int = ServerReplicaProtocol.MAX_BYTES) = JsonObject(mapOf(
        "protocol" to JsonPrimitive(1), "accountId" to JsonPrimitive(account),
        "namespaces" to JsonArray(ServerReplicaProtocol.namespaces.map(::JsonPrimitive)),
        "maxPayloadBytes" to JsonPrimitive(maximum), "maxLanes" to JsonPrimitive(64),
        "maxAccountBytes" to JsonPrimitive(268435456), "maxMutationKeys" to JsonPrimitive(100000),
        "compareAndSwap" to JsonPrimitive(true), "retainedIdempotency" to JsonPrimitive(true),
    )).toString().toByteArray()
    private fun metadata(revision: Int = 1, bytes: ByteArray = payload,
        deviceId: String = device): ServerReplicaMetadata {
        val hash = ServerReplicaProtocol.sha256(bytes)
        return ServerReplicaMetadata(account, namespace, deviceId, 1, revision.toString(), hash, bytes.size,
            1792530000000, "\"r$revision-$hash\"")
    }
    private fun manifest(items: List<ServerReplicaMetadata> = emptyList()) = JsonObject(mapOf(
        "protocol" to JsonPrimitive(1), "replicas" to JsonArray(items.map(ServerReplicaWire::metadataJson)),
        "nextCursor" to JsonNull,
    )).toString().toByteArray()
    private fun ServerReplicaExchange.payloadHeaders(metadata: ServerReplicaMetadata) {
        responseHeaders.set("ETag", metadata.etag)
        responseHeaders.set("X-Content-SHA256", metadata.sha256)
        responseHeaders.set("X-Replica-Version", "1")
    }
    private fun ServerReplicaExchange.receipt(accepted: ServerReplicaMetadata, current: ServerReplicaMetadata, replayed: Boolean,
        status: Int = 200) {
        responseHeaders.set("ETag", accepted.etag)
        responseHeaders.set("X-Replica-Current-ETag", current.etag)
        reply(status, JsonObject(mapOf(
            "accepted" to ServerReplicaWire.metadataJson(accepted),
            "current" to ServerReplicaWire.metadataJson(current),
            "replayed" to JsonPrimitive(replayed))).toString().toByteArray())
    }
    private fun ServerReplicaExchange.reply(status: Int, bytes: ByteArray) {
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, if (status == 304) -1 else bytes.size.toLong())
        if (status != 304) responseBody.use { it.write(bytes) }
    }
    private suspend inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error.javaClass.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}
