package org.koitharu.kotatsu.sync.extended.appserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.sync.extended.server.FileServerReplicaJournal
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaClient
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaEndpoint
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaExchange
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaFixture
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaMetadata
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaProtocol
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaWire
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ServerExtendedCoordinatorTest {
    private lateinit var directory: File
    private lateinit var server: ServerReplicaFixture
    private lateinit var accounts: Accounts
    private lateinit var store: ServerExtendedStore
    private val executor = Executors.newCachedThreadPool()
    private val puts = AtomicInteger()
    private val gets = AtomicInteger()
    private val payloads = Payloads()
    private var advertised = ServerExtendedNamespaces.all
    private var manifest = emptyList<ServerReplicaMetadata>()
    private var remoteBodies = emptyMap<String, ByteArray>()
    @Volatile private var put: (ServerReplicaExchange) -> Unit = { exchange ->
        val bytes = exchange.requestBody.readBytes()
        val namespace = exchange.requestURI.path.split('/')[4]
        val device = exchange.requestURI.path.substringAfterLast('/')
        exchange.receipt(metadata(namespace, device, bytes), status = 201)
    }

    @Before fun setUp() {
        directory = Files.createTempDirectory("server-extended-coordinator-").toFile()
        store = ServerExtendedStore(directory)
        server = ServerReplicaFixture.create(InetSocketAddress("127.0.0.1", 0))
        server.executor = executor
        server.createContext("/") { exchange ->
            when {
                exchange.requestURI.path.endsWith("/capabilities") -> { gets.incrementAndGet(); exchange.json(caps()) }
                exchange.requestURI.path == "/resource/v2/replicas" -> {
                    gets.incrementAndGet()
                    exchange.json(JsonObject(mapOf("protocol" to JsonPrimitive(1), "nextCursor" to JsonNull,
                        "replicas" to JsonArray(manifest.map(ServerReplicaWire::metadataJson)))))
                }
                exchange.requestMethod == "PUT" -> { puts.incrementAndGet(); put(exchange) }
                else -> {
                    gets.incrementAndGet()
                    val metadata = manifest.single { it.deviceId == exchange.requestURI.path.substringAfterLast('/') &&
                        it.namespace == exchange.requestURI.path.split('/')[4] }
                    exchange.responseHeaders.set("ETag", metadata.etag)
                    exchange.responseHeaders.set("X-Content-SHA256", metadata.sha256)
                    exchange.responseHeaders.set("X-Replica-Version", "1")
                    exchange.bytes(remoteBodies.getValue(metadata.etag))
                }
            }
            exchange.close()
        }
        server.start()
        accounts = Accounts(ServerReplicaEndpoint.parse("http://127.0.0.1:${server.address.port}", true))
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.stop()
        executor.shutdownNow()
        directory.deleteRecursively()
    }

    @Test fun identityPreservesExactSubjectAndRejectsInvalidClaims() {
        assertEquals("9007199254740993", ServerExtendedIdentity.expectedAccountId(token("9007199254740993")))
        listOf("0", "-1", "1.0", "1e3", "\"7\"", "9223372036854775808", "null").forEach { claim ->
            assertThrows(Exception::class.java) { ServerExtendedIdentity.expectedAccountId(token(claim)) }
        }
        assertThrows(Exception::class.java) { ServerExtendedIdentity.expectedAccountId(token("1,\"user_id\":2")) }
    }

    @Test fun disabledOpeningAndScheduledWithoutAutomationHaveNoNetwork() = runBlocking<Unit> {
        val coordinator = coordinator()
        assertFalse(store.configuration().enabled)
        assertEquals(ServerExtendedStatus.DISABLED, coordinator.sync(ServerExtendedRunMode.MANUAL).status)
        enable()
        assertEquals(ServerExtendedStatus.DISABLED, coordinator.sync(ServerExtendedRunMode.SCHEDULED).status)
        assertEquals(0, gets.get())
        assertEquals(0, puts.get())
    }

    @Test fun explicitCheckThenEnableDoesNotUploadAndBindingIsVerified() = runBlocking<Unit> {
        val coordinator = coordinator()
        val supported = coordinator.checkConnection(true)
        assertEquals(ServerExtendedNamespaces.all, supported)
        assertEquals(accounts.accountId, coordinator.checkedBinding()!!.accountId)
        coordinator.enable(setOf(CHAPTERS), true)
        assertTrue(store.configuration().enabled)
        assertEquals(1, gets.get())
        assertEquals(0, puts.get())
        assertThrows(Exception::class.java) { runBlocking { coordinator.enable(setOf(CHAPTERS), true) } }
    }

    @Test fun allFiveSelectedNamespacesUploadOpaquePayloadsWithoutLegacyRequests() = runBlocking<Unit> {
        enable(ServerExtendedNamespaces.all)
        val result = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.SUCCESS, result.status)
        assertEquals(5, puts.get())
        assertEquals(ServerExtendedNamespaces.all, result.lanes.keys)
        assertEquals(ServerExtendedNamespaces.all, payloads.merged.map { it.first }.toSet())
    }

    @Test fun unavailableNamespaceIsVisibleAndNeverUploaded() = runBlocking<Unit> {
        enable(setOf(CHAPTERS, LINKS))
        advertised = setOf(CHAPTERS)
        val result = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.UNSUPPORTED, result.lanes[LINKS])
        assertEquals(ServerExtendedStatus.SUCCESS, result.lanes[CHAPTERS])
        assertEquals(1, puts.get())
    }

    @Test fun unknownOutcomeRequiresExplicitResumeWithSameKeyAcrossRecreation() = runBlocking<Unit> {
        enable()
        var key: String? = null
        var bytes: ByteArray? = null
        put = { exchange ->
            key = exchange.requestHeaders.getFirst("Idempotency-Key")
            bytes = exchange.requestBody.readBytes()
            exchange.close()
        }
        val first = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.RECOVERY_REQUIRED, first.lanes[CHAPTERS])
        assertEquals(1, puts.get())
        store.configure(store.configuration().copy(automatic = true))
        coordinator().sync(ServerExtendedRunMode.SCHEDULED)
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(1, puts.get())
        store = ServerExtendedStore(directory)
        var replayedKey: String? = null
        var replayedBytes: ByteArray? = null
        put = { exchange ->
            replayedKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            replayedBytes = exchange.requestBody.readBytes()
            exchange.receipt(metadata(CHAPTERS, store.deviceId(), checkNotNull(bytes)), replayed = true)
        }
        assertEquals(ServerExtendedStatus.SUCCESS, coordinator().sync(ServerExtendedRunMode.RESUME_PENDING).status)
        assertEquals(key, replayedKey)
        assertArrayEquals(bytes, replayedBytes)
        assertEquals(2, puts.get())
        assertTrue(File(directory, "uploads").listFiles()!!.none { it.extension == "replica" })
    }

    @Test fun resumeReceiptUsesNewerCurrentPayloadBeforeAcknowledgement() = runBlocking<Unit> {
        enable()
        put = { it.requestBody.readBytes(); it.close() }
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        val newer = "{\"version\":1,\"newer\":true}".toByteArray()
        val current = metadata(CHAPTERS, store.deviceId(), newer, 2)
        manifest = listOf(current)
        remoteBodies = mapOf(current.etag to newer)
        put = { exchange ->
            val original = exchange.requestBody.readBytes()
            exchange.receipt(metadata(CHAPTERS, store.deviceId(), original), current, replayed = true)
        }
        coordinator().sync(ServerExtendedRunMode.RESUME_PENDING)
        assertArrayEquals(newer, payloads.merged.last().second)
        assertEquals(current, store.applied(accounts.account().scope, CHAPTERS, store.deviceId()))
        assertEquals(2, puts.get())
    }

    @Test fun staleCasPausesUntilExplicitResumeAndNeverImmediatelyReuploads() = runBlocking<Unit> {
        enable()
        val currentBytes = "{\"version\":1,\"current\":true}".toByteArray()
        val current = metadata(CHAPTERS, store.deviceId(), currentBytes, 3)
        put = { exchange ->
            exchange.requestBody.readBytes()
            exchange.json(JsonObject(mapOf("error" to JsonPrimitive("stale_replica"),
                "current" to ServerReplicaWire.metadataJson(current))), 412)
        }
        val first = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.CONFLICT, first.lanes[CHAPTERS])
        coordinator().sync(ServerExtendedRunMode.SCHEDULED)
        assertEquals(1, puts.get())
        manifest = listOf(current)
        remoteBodies = mapOf(current.etag to currentBytes)
        coordinator().sync(ServerExtendedRunMode.RESUME_PENDING)
        assertEquals(1, puts.get())
        assertEquals(current, store.applied(accounts.account().scope, CHAPTERS, store.deviceId()))
        assertTrue(File(directory, "uploads").listFiles()!!.none { it.extension == "replica" })
    }

    @Test fun keyConflictCannotBeAcknowledgedOrBypassedByResume() = runBlocking<Unit> {
        enable()
        put = { exchange ->
            exchange.requestBody.readBytes()
            exchange.json(JsonObject(mapOf("error" to JsonPrimitive("idempotency_conflict"))), 409)
        }
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        coordinator().sync(ServerExtendedRunMode.RESUME_PENDING)
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(1, puts.get())
        assertEquals(1, File(directory, "uploads").listFiles()!!.count { it.extension == "replica" })
    }

    @Test fun failedMergeNeverAdvancesCheckpointOrUploadsOmittingIncomingState() = runBlocking<Unit> {
        enable()
        val metadata = metadata(CHAPTERS, UUID.randomUUID().toString(), payloads.bytes(CHAPTERS))
        manifest = listOf(metadata)
        remoteBodies = mapOf(metadata.etag to payloads.bytes(CHAPTERS))
        payloads.failMerge = true
        val result = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.REVIEW_REQUIRED, result.lanes[CHAPTERS])
        assertNull(store.applied(accounts.account().scope, CHAPTERS, metadata.deviceId))
        assertEquals(0, puts.get())
        assertEquals(1, File(directory, "inbox").listFiles()!!.size)
    }

    @Test fun committedCheckpointSkipsDuplicateMergeAndMatchingOwnHashSkipsUpload() = runBlocking<Unit> {
        enable()
        val metadata = metadata(CHAPTERS, store.deviceId(), payloads.bytes(CHAPTERS))
        manifest = listOf(metadata)
        remoteBodies = mapOf(metadata.etag to payloads.bytes(CHAPTERS))
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(1, payloads.merged.size)
        assertEquals(0, puts.get())
        assertEquals(metadata, ServerExtendedStore(directory).applied(accounts.account().scope, CHAPTERS, metadata.deviceId))
    }

    @Test fun accountChangeInsideFinalMergeGuardPreventsCheckpoint() = runBlocking<Unit> {
        enable()
        val metadata = metadata(CHAPTERS, UUID.randomUUID().toString(), payloads.bytes(CHAPTERS))
        manifest = listOf(metadata)
        remoteBodies = mapOf(metadata.etag to payloads.bytes(CHAPTERS))
        payloads.beforeMergeCommit = { accounts.active = false }
        val result = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.STALE_SESSION, result.status)
        assertTrue(payloads.merged.isEmpty())
        assertNull(store.applied(accounts.account().scope, CHAPTERS, metadata.deviceId))
        assertEquals(0, puts.get())
    }

    @Test fun consentDisableDuringPutLeavesRecoverableOutcomeWithoutMerge() = runBlocking<Unit> {
        enable()
        put = { exchange ->
            val bytes = exchange.requestBody.readBytes()
            store.configure(store.configuration().copy(enabled = false))
            exchange.receipt(metadata(CHAPTERS, store.deviceId(), bytes), status = 201)
        }
        val result = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.STALE_SESSION, result.status)
        assertTrue(payloads.merged.isEmpty())
        assertEquals(1, File(directory, "uploads").listFiles()!!.count { it.extension == "replica" })
    }

    @Test fun cancellationDuringPutDoesNotScheduleRetryOrApplyLocalState() = runBlocking<Unit> {
        enable()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        put = { exchange ->
            val bytes = exchange.requestBody.readBytes()
            arrived.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.receipt(metadata(CHAPTERS, store.deviceId(), bytes), status = 201)
        }
        val coordinator = coordinator()
        val operation = launch(Dispatchers.Default) { coordinator.sync(ServerExtendedRunMode.MANUAL) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        operation.cancelAndJoin()
        release.countDown()
        assertEquals(1, puts.get())
        assertTrue(payloads.merged.isEmpty())
        coordinator.sync(ServerExtendedRunMode.MANUAL)
        assertEquals(1, puts.get())
    }

    @Test fun deviceAndConsentPersistWithoutTokensAndCorruptionFailsClosed() {
        enable()
        val device = store.deviceId()
        val restored = ServerExtendedStore(directory)
        assertEquals(device, restored.deviceId())
        assertEquals(store.configuration(), restored.configuration())
        assertFalse(File(directory, "state.json").readText().contains(accounts.token))
        File(directory, "state.json").writeText("{\"version\":1,\"version\":1}")
        assertThrows(Exception::class.java) { ServerExtendedStore(directory).configuration() }
    }

    @Test fun missingDeviceCannotMintNewLaneWhenUploadRemainsUnknown() = runBlocking<Unit> {
        enable()
        put = { it.requestBody.readBytes(); it.close() }
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        File(directory, "device.uuid").delete()
        assertThrows(Exception::class.java) { ServerExtendedStore(directory).deviceId() }
        assertEquals(1, puts.get())
    }

    @Test fun quotaRejectionRequiresManualActionBeforeAnotherWrite() = runBlocking<Unit> {
        enable()
        store.configure(store.configuration().copy(automatic = true))
        put = { exchange ->
            exchange.requestBody.readBytes()
            exchange.json(JsonObject(mapOf("error" to JsonPrimitive("replica_storage_quota"))), 507)
        }
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        coordinator().sync(ServerExtendedRunMode.SCHEDULED)
        coordinator().sync(ServerExtendedRunMode.RESUME_PENDING)
        coordinator().sync(ServerExtendedRunMode.SCHEDULED)
        assertEquals(1, puts.get())
        assertEquals(ServerExtendedStatus.QUOTA, store.paused(accounts.account().scope, CHAPTERS))
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(2, puts.get())
    }

    @Test fun consumedReceiptCrashGapAcknowledgesWithoutAnotherPutOrMerge() = runBlocking<Unit> {
        enable()
        val journal = FileServerReplicaJournal(File(directory, "uploads"))
        val account = accounts.account()
        val session = org.koitharu.kotatsu.sync.extended.server.ServerReplicaSession(
            account.scope, account.token, account.revision) { accounts.checkCurrent(account) }
        val connection = ServerReplicaClient(journal).negotiate(session)
        val upload = connection.prepareUpload(CHAPTERS, store.deviceId(), payloads.bytes(CHAPTERS),
            org.koitharu.kotatsu.sync.extended.server.ServerReplicaCondition.CreateIfNoneMatch)
        val accepted = connection.uploadPending(CHAPTERS, store.deviceId())
        val receipt = (accepted.outcome as org.koitharu.kotatsu.sync.extended.server.ServerReplicaUploadOutcome.Accepted)
            .receipt
        store.markApplied(account.scope, receipt.current) {}
        store.consume(upload.requestId) {}
        store = ServerExtendedStore(directory)
        coordinator().sync(ServerExtendedRunMode.RESUME_PENDING)
        assertEquals(1, puts.get())
        assertTrue(payloads.merged.isEmpty())
        assertFalse(store.consumed(upload.requestId))
        assertTrue(File(directory, "uploads").listFiles()!!.none { it.extension == "replica" })
    }

    @Test fun editDuringUploadRemainsInNextExportAndUsesFreshCas() = runBlocking<Unit> {
        enable()
        var first: ServerReplicaMetadata? = null
        put = { exchange ->
            val bytes = exchange.requestBody.readBytes()
            val metadata = metadata(CHAPTERS, store.deviceId(), bytes)
            first = metadata
            payloads.localVersion = 2
            exchange.receipt(metadata, status = 201)
        }
        coordinator().sync(ServerExtendedRunMode.MANUAL)
        val accepted = checkNotNull(first)
        manifest = listOf(accepted)
        remoteBodies = mapOf(accepted.etag to payloads.bytes(CHAPTERS))
        var condition: String? = null
        var second: ByteArray? = null
        put = { exchange ->
            condition = exchange.requestHeaders.getFirst("If-Match")
            second = exchange.requestBody.readBytes()
            exchange.receipt(metadata(CHAPTERS, store.deviceId(), checkNotNull(second), 2))
        }
        val result = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.SUCCESS, result.status)
        assertEquals(accepted.etag, condition)
        assertArrayEquals(payloads.bytes(CHAPTERS), second)
        assertEquals(2, puts.get())
    }

    @Test fun differentServerBindingCannotSendOrApplyUnderSameNumericAccount() = runBlocking<Unit> {
        enable()
        val binding = checkNotNull(store.configuration().binding)
        store.configure(store.configuration().copy(binding = binding.copy(endpoint = "https://another.example/")))
        val result = coordinator().sync(ServerExtendedRunMode.MANUAL)
        assertEquals(ServerExtendedStatus.STALE_SESSION, result.status)
        assertEquals(0, gets.get())
        assertEquals(0, puts.get())
        assertTrue(payloads.merged.isEmpty())
    }

    @Test fun staleConnectionCheckCannotEnableNewSession() = runBlocking<Unit> {
        val coordinator = coordinator()
        coordinator.checkConnection(true)
        accounts.active = false
        assertThrows(Exception::class.java) { runBlocking { coordinator.enable(setOf(CHAPTERS), true) } }
        assertFalse(store.configuration().enabled)
        assertEquals(0, puts.get())
    }

    private fun enable(namespaces: Set<String> = setOf(CHAPTERS)) {
        store.configure(store.configuration().copy(enabled = true, binding = ServerExtendedBinding.from(accounts.account()),
            namespaces = namespaces, allowCleartext = true))
    }
    private fun coordinator() = ServerExtendedCoordinator(accounts, store, payloads,
        ServerReplicaClient(FileServerReplicaJournal(File(directory, "uploads"))))
    private fun token(claim: String) = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"user_id\":$claim}".toByteArray()) + ".signature"
    private fun caps() = JsonObject(mapOf("protocol" to JsonPrimitive(1), "accountId" to JsonPrimitive(accounts.accountId),
        "namespaces" to JsonArray(advertised.map(::JsonPrimitive)), "maxPayloadBytes" to JsonPrimitive(33554432),
        "maxLanes" to JsonPrimitive(64), "maxAccountBytes" to JsonPrimitive(268435456),
        "maxMutationKeys" to JsonPrimitive(100000), "compareAndSwap" to JsonPrimitive(true),
        "retainedIdempotency" to JsonPrimitive(true)))
    private fun metadata(namespace: String, device: String, bytes: ByteArray, revision: Int = 1): ServerReplicaMetadata {
        val hash = ServerReplicaProtocol.sha256(bytes)
        return ServerReplicaMetadata(accounts.accountId, namespace, device, 1, revision.toString(), hash, bytes.size,
            123456, "\"r$revision-$hash\"")
    }
    private fun ServerReplicaExchange.json(value: JsonObject, status: Int = 200) = bytes(value.toString().toByteArray(), status)
    private fun ServerReplicaExchange.bytes(bytes: ByteArray, status: Int = 200) {
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
    private fun ServerReplicaExchange.receipt(accepted: ServerReplicaMetadata, current: ServerReplicaMetadata = accepted,
        replayed: Boolean = false, status: Int = 200) {
        responseHeaders.set("ETag", accepted.etag)
        responseHeaders.set("X-Replica-Current-ETag", current.etag)
        json(JsonObject(mapOf("accepted" to ServerReplicaWire.metadataJson(accepted),
            "current" to ServerReplicaWire.metadataJson(current), "replayed" to JsonPrimitive(replayed))), status)
    }
    private inner class Accounts(private val endpoint: ServerReplicaEndpoint) : ServerExtendedAccounts {
        val accountId = "9007199254740993"
        val token = token(accountId)
        @Volatile var active = true
        private val nonce = UUID.randomUUID().toString()
        fun account() = ServerExtendedAccount("fixture@example.invalid", "fixture", endpoint, accountId, nonce, token, "1")
        override fun capture(allowCleartext: Boolean, explicitBinding: Boolean): ServerExtendedAccount {
            check(active)
            return account()
        }
        override fun checkCurrent(account: ServerExtendedAccount) { check(active && account.token == token) }
    }
    private class Payloads : ServerExtendedPayloads {
        override val namespaces = ServerExtendedNamespaces.all
        val merged = mutableListOf<Pair<String, ByteArray>>()
        var failMerge = false
        var localVersion = 1
        var beforeMergeCommit: () -> Unit = {}
        fun bytes(namespace: String) =
            "{\"version\":1,\"namespace\":\"$namespace\",\"local\":$localVersion}".toByteArray()
        override suspend fun export(namespace: String, checkCurrent: () -> Unit): ByteArray {
            checkCurrent()
            return bytes(namespace)
        }
        override suspend fun merge(namespace: String, bytes: ByteArray, checkCurrent: () -> Unit) {
            checkCurrent()
            if (failMerge) error("Fixture edition collision")
            beforeMergeCommit()
            checkCurrent()
            merged += namespace to bytes.copyOf()
        }
    }
    companion object {
        private const val CHAPTERS = "chapter_reads_v1"
        private const val LINKS = "tracker_links_v1"
    }
}
