package org.koitharu.kotatsu.sync.extended.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate authenticated transport; never inherits source cookies, CAPTCHA handlers or invalid-TLS settings. */
class ServerReplicaClient(private val journal: ServerReplicaJournal) {
    private val transfers = sharedTransfers
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val guard = checkNotNull(chain.request().tag(RequestGuard::class.java))
            guard.session.ensureCurrentForNetwork(guard.context)
            chain.proceed(chain.request())
        }
        .build()

    suspend fun negotiate(session: ServerReplicaSession): ServerReplicaConnection {
        val response = execute(session, request(session, "capabilities").build(), ServerReplicaWire.METADATA_BYTES)
        if (response.status != 200) throw ServerReplicaHttpException(response.status, "capabilities_unavailable")
        response.requireJson()
        val capabilities = ServerReplicaWire.capabilities(response.bytes, session.scope)
        session.ensureCurrent()
        return ServerReplicaConnection(this, journal, session, capabilities)
    }

    internal fun request(session: ServerReplicaSession, vararg path: String): Request.Builder = Request.Builder()
        .url(session.scope.endpoint.resource(*path))
        .header("Authorization", "Bearer ${session.bearerToken}")
        .header("Accept", "application/json")
        .header("Accept-Encoding", "identity")
        .header("Cache-Control", "no-store")

    internal suspend fun execute(session: ServerReplicaSession, request: Request,
        maximum: Int): WireResponse = transfers.withPermit {
        session.ensureCurrent()
        val context = currentCoroutineContext()
        val result = suspendCancellableCoroutine { continuation ->
            val guarded = request.newBuilder().tag(RequestGuard::class.java, RequestGuard(session, context)).build()
            val call = client.newCall(guarded)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            context.ensureActive()
                            session.ensureCurrentNow()
                            val declared = it.body.contentLength()
                            ServerReplicaWire.expect(declared < 0 || declared <= maximum,
                                "Replica response exceeds byte limit")
                            val encoding = it.headers.values("Content-Encoding")
                            ServerReplicaWire.expect(encoding.isEmpty() || encoding == listOf("identity"),
                                "Unsupported replica response encoding")
                            val output = ByteArrayOutputStream(minOf(maximum, 64 * 1024))
                            val buffer = ByteArray(64 * 1024)
                            it.body.byteStream().use { input ->
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
                                    if (count < 0) break
                                    ServerReplicaWire.expect(output.size() + count <= maximum,
                                        "Replica response exceeds byte limit")
                                    output.write(buffer, 0, count)
                                }
                            }
                            val bytes = output.toByteArray()
                            ServerReplicaWire.expect(declared < 0 || declared == bytes.size.toLong() || it.code == 304,
                                "Incomplete replica response")
                            context.ensureActive()
                            session.ensureCurrentNow()
                            WireResponse(it.code, it.headers, bytes)
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
        session.ensureCurrent()
        result
    }

    private class RequestGuard(val session: ServerReplicaSession, val context: CoroutineContext)

    companion object {
        private val sharedTransfers = Semaphore(2)
    }

    internal class WireResponse(val status: Int, val headers: Headers, val bytes: ByteArray) {
        fun singleHeader(name: String): String {
            val values = headers.values(name)
            ServerReplicaWire.expect(values.size == 1 && values.single().length <= 512,
                "Missing or duplicate replica header")
            return values.single()
        }
        fun requireJson() {
            val media = singleHeader("Content-Type")
            val jsonMedia = Regex("application/json(?:\\s*;\\s*charset=\"?utf-8\"?)?", RegexOption.IGNORE_CASE)
            ServerReplicaWire.expect(media.matches(jsonMedia),
                "Replica response must be UTF-8 JSON")
        }
    }
}

/** Obtain only by explicit capability negotiation. Every method retains the negotiated account/server session guard. */
class ServerReplicaConnection internal constructor(
    private val client: ServerReplicaClient,
    private val journal: ServerReplicaJournal,
    val session: ServerReplicaSession,
    val capabilities: ServerReplicaCapabilities,
) {
    suspend fun manifest(): List<ServerReplicaMetadata> {
        val response = client.execute(session, client.request(session, "replicas").build(),
            ServerReplicaWire.METADATA_BYTES)
        if (response.status != 200) throw error(response)
        response.requireJson()
        val result = ServerReplicaWire.manifest(response.bytes, session.scope, capabilities)
        session.ensureCurrent()
        return result
    }

    suspend fun download(namespace: String, deviceId: String, ifNoneMatch: String? = null): ServerReplicaDownload {
        lane(namespace, deviceId)
        ifNoneMatch?.let(ServerReplicaProtocol::requireEtag)
        val request = client.request(session, "replicas", namespace, deviceId)
            .apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }.build()
        val response = client.execute(session, request, capabilities.maxPayloadBytes)
        val result = when (response.status) {
            404 -> ServerReplicaDownload.Missing
            304 -> {
                val etag = response.singleHeader("ETag")
                ServerReplicaWire.expect(ifNoneMatch != null && etag == ifNoneMatch && response.bytes.isEmpty(),
                    "Unexpected replica NotModified")
                requirePayloadHeaders(response, etag)
                ServerReplicaDownload.NotModified(etag)
            }
            200 -> {
                response.requireJson()
                val etag = response.singleHeader("ETag")
                requirePayloadHeaders(response, etag)
                val hash = response.singleHeader("X-Content-SHA256")
                ServerReplicaWire.expect(ServerReplicaProtocol.sha256(response.bytes) == hash,
                    "Replica body hash mismatch")
                ServerReplicaJson.validate(response.bytes, capabilities.maxPayloadBytes)
                ServerReplicaDownload.Downloaded(response.bytes, etag, hash, 1)
            }
            else -> throw error(response)
        }
        session.ensureCurrent()
        return result
    }

    suspend fun pending(namespace: String, deviceId: String): ServerReplicaUpload? {
        val lane = lane(namespace, deviceId)
        session.ensureCurrent()
        val result = journalOperation { journal.load(lane) }
        session.ensureCurrent()
        return result
    }

    /** A pending lane rejects replacement, including after process death or authentication refresh. */
    suspend fun prepareUpload(namespace: String, deviceId: String, bytes: ByteArray,
        condition: ServerReplicaCondition): ServerReplicaUpload {
        val lane = lane(namespace, deviceId)
        session.ensureCurrent()
        val upload = withContext(Dispatchers.IO) {
            val copy = bytes.copyOf()
            ServerReplicaJson.validate(copy, capabilities.maxPayloadBytes)
            ServerReplicaUpload(lane, UUID.randomUUID().toString(), condition, copy)
        }
        journalOperation { journal.prepare(upload) }
        session.ensureCurrent()
        return upload
    }

    /** Explicit attempt only: pending means replay this stored request, never mint a replacement request. */
    suspend fun uploadPending(namespace: String, deviceId: String): ServerReplicaUpload {
        val lane = lane(namespace, deviceId)
        session.ensureCurrent()
        val upload = checkNotNull(journalOperation { journal.load(lane) }) { "No durable replica upload" }
        if (upload.outcome != null) { session.ensureCurrent(); return upload }
        val bytes = withContext(Dispatchers.IO) {
            upload.bytes().also { ServerReplicaJson.validate(it, capabilities.maxPayloadBytes) }
        }
        val context = currentCoroutineContext()
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength(): Long = bytes.size.toLong()
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                session.ensureCurrentForNetwork(context)
                sink.write(bytes)
            }
        }
        val request = client.request(session, "replicas", namespace, deviceId)
            .header("X-Replica-Version", "1")
            .header("X-Content-SHA256", upload.sha256)
            .header("Idempotency-Key", upload.requestId)
            .apply { when (val condition = upload.condition) {
                ServerReplicaCondition.CreateIfNoneMatch -> header("If-None-Match", "*")
                is ServerReplicaCondition.UpdateIfMatch -> header("If-Match", condition.etag)
            } }.put(body).build()
        val response = client.execute(session, request, ServerReplicaWire.METADATA_BYTES)
        val outcome = when (response.status) {
            200, 201 -> {
                response.requireJson()
                val receipt = ServerReplicaWire.receipt(response.bytes, session.scope, capabilities)
                validateReceipt(upload, receipt, response)
                ServerReplicaUploadOutcome.Accepted(receipt)
            }
            409, 412 -> {
                val error = error(response)
                error.current?.let { requireLane(it, lane) }
                ServerReplicaUploadOutcome.Conflict(error.status, error.errorCode, error.current)
            }
            507 -> {
                val error = error(response)
                if (error.errorCode !in setOf("replica_lane_quota", "replica_storage_quota",
                        "mutation_journal_quota", "replica_revision_exhausted")) throw error
                ServerReplicaUploadOutcome.Rejected(error.status, error.errorCode)
            }
            in 400..499 -> {
                // 408/425/429 may be transient; preserve the exact request for an explicit later attempt.
                val error = error(response)
                if (response.status in setOf(408, 425, 429)) throw error
                ServerReplicaUploadOutcome.Rejected(error.status, error.errorCode)
            }
            else -> throw error(response)
        }
        session.ensureCurrent()
        val result = journalOperation { journal.complete(lane, upload.requestId, outcome) }
        session.ensureCurrent()
        return result
    }

    /** Call after consuming the terminal result; the owner checks current scope inside its own DB commit too. */
    suspend fun acknowledge(namespace: String, deviceId: String, requestId: String) {
        val lane = lane(namespace, deviceId)
        session.ensureCurrent()
        journalOperation { journal.acknowledge(lane, requestId) }
        session.ensureCurrent()
    }

    private suspend fun <T> journalOperation(block: () -> T): T = withContext(Dispatchers.IO) {
        session.ensureCurrent()
        val result = block()
        session.ensureCurrent()
        result
    }

    private fun lane(namespace: String, deviceId: String): ServerReplicaLane {
        require(namespace in capabilities.namespaces) { "Replica namespace was not negotiated" }
        return ServerReplicaLane(session.scope, namespace, deviceId)
    }

    private fun error(response: ServerReplicaClient.WireResponse): ServerReplicaHttpException {
        if (response.bytes.isEmpty() || !response.headers["Content-Type"].orEmpty().startsWith("application/json")) {
            return ServerReplicaHttpException(response.status, "http_${response.status}")
        }
        response.requireJson()
        return ServerReplicaWire.error(response.bytes, response.status, session.scope, capabilities)
    }

    private fun requirePayloadHeaders(response: ServerReplicaClient.WireResponse, etag: String) {
        ServerReplicaWire.expect(runCatching { ServerReplicaProtocol.requireEtag(etag) }.isSuccess,
            "Invalid replica ETag")
        val hash = response.singleHeader("X-Content-SHA256")
        ServerReplicaWire.expect(response.singleHeader("X-Replica-Version") == "1" &&
            hash.matches(ServerReplicaProtocol.hashPattern) && etag.substringAfter('-').dropLast(1) == hash,
            "Replica header hash or version mismatch")
    }

    private fun requireLane(metadata: ServerReplicaMetadata, lane: ServerReplicaLane) = ServerReplicaWire.expect(
        metadata.accountId == lane.scope.accountId && metadata.namespace == lane.namespace &&
            metadata.deviceId == lane.deviceId,
        "Replica receipt lane changed",
    )

    private fun validateReceipt(upload: ServerReplicaUpload, receipt: ServerReplicaReceipt,
        response: ServerReplicaClient.WireResponse) {
        requireLane(receipt.accepted, upload.lane)
        requireLane(receipt.current, upload.lane)
        val accepted = receipt.accepted
        val current = receipt.current
        ServerReplicaWire.expect(accepted.sha256 == upload.sha256 && accepted.bytes == upload.byteCount,
            "Replica receipt does not acknowledge this payload")
        val expectedRevision = when (val condition = upload.condition) {
            ServerReplicaCondition.CreateIfNoneMatch -> 1L
            is ServerReplicaCondition.UpdateIfMatch -> condition.etag
                .substring(2, condition.etag.indexOf('-')).toLong().let {
                ServerReplicaWire.expect(it < Long.MAX_VALUE, "Replica revision exhausted")
                it + 1
            }
        }
        ServerReplicaWire.expect(accepted.revision.toLong() == expectedRevision &&
            current.revision.toLong() >= accepted.revision.toLong() &&
            (current.revision != accepted.revision || current == accepted) &&
            (receipt.replayed || current == accepted), "Inconsistent replica receipt revisions")
        ServerReplicaWire.expect(response.singleHeader("ETag") == accepted.etag &&
            response.singleHeader("X-Replica-Current-ETag") == current.etag, "Replica receipt headers disagree")
        ServerReplicaWire.expect(response.status == (if (!receipt.replayed && expectedRevision == 1L) 201 else 200),
            "Unexpected replica receipt status")
    }
}
