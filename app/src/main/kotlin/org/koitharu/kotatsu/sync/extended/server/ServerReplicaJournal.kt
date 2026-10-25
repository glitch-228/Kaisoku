package org.koitharu.kotatsu.sync.extended.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Auth revisions deliberately do not participate in durable identity. */
data class ServerReplicaLane(val scope: ServerReplicaScope, val namespace: String, val deviceId: String) {
    init {
        require(namespace in ServerReplicaProtocol.namespaces)
        ServerReplicaProtocol.requireUuid(deviceId)
    }
}

sealed interface ServerReplicaUploadOutcome {
    data class Accepted(val receipt: ServerReplicaReceipt) : ServerReplicaUploadOutcome
    data class Conflict(val status: Int, val errorCode: String,
        val current: ServerReplicaMetadata?) : ServerReplicaUploadOutcome
    data class Rejected(val status: Int, val errorCode: String) : ServerReplicaUploadOutcome
}

class ServerReplicaUpload internal constructor(
    val lane: ServerReplicaLane,
    val requestId: String,
    val condition: ServerReplicaCondition,
    bytes: ByteArray,
    val outcome: ServerReplicaUploadOutcome? = null,
) {
    private val content = bytes.copyOf()
    val sha256: String = ServerReplicaProtocol.sha256(content)
    val byteCount: Int get() = content.size
    fun bytes(): ByteArray = content.copyOf()
    internal fun withOutcome(value: ServerReplicaUploadOutcome) =
        ServerReplicaUpload(lane, requestId, condition, content, value)
    init {
        ServerReplicaProtocol.requireUuid(requestId)
        ServerReplicaJson.validate(content)
    }
}

interface ServerReplicaJournal {
    fun load(lane: ServerReplicaLane): ServerReplicaUpload?
    /** Must atomically fail if any pending or unacknowledged terminal record exists. */
    fun prepare(upload: ServerReplicaUpload)
    /** Must persist before returning. An already recorded terminal outcome wins. */
    fun complete(lane: ServerReplicaLane, requestId: String, outcome: ServerReplicaUploadOutcome): ServerReplicaUpload
    /** Pending records can never be forgotten through this API. */
    fun acknowledge(lane: ServerReplicaLane, requestId: String)
}

/** Durable private-directory journal; same-filesystem atomic rename and fsync are required, never silently weakened. */
class FileServerReplicaJournal(directory: File) : ServerReplicaJournal {
    private val directory = directory.canonicalFile
    private val mutex = locks.computeIfAbsent(this.directory.path) { Any() }

    init {
        require(this.directory.isDirectory || this.directory.mkdirs()) { "Cannot create replica journal directory" }
    }

    override fun load(lane: ServerReplicaLane): ServerReplicaUpload? = locked { read(lane) }

    override fun prepare(upload: ServerReplicaUpload) = locked {
        check(upload.outcome == null) { "Cannot prepare a completed upload" }
        check(read(upload.lane) == null) { "Replica lane has an unacknowledged upload" }
        write(upload)
    }

    override fun complete(lane: ServerReplicaLane, requestId: String,
        outcome: ServerReplicaUploadOutcome): ServerReplicaUpload = locked {
        val current = checkNotNull(read(lane)) { "Missing durable upload" }
        check(current.requestId == requestId) { "Replica request changed" }
        if (current.outcome != null) current else current.withOutcome(outcome).also(::write)
    }

    override fun acknowledge(lane: ServerReplicaLane, requestId: String) = locked {
        val current = checkNotNull(read(lane)) { "Missing durable upload" }
        check(current.requestId == requestId && current.outcome != null) {
            "Unknown upload outcome cannot be discarded"
        }
        if (!file(lane).delete()) throw IOException("Cannot acknowledge replica upload")
        syncDirectory()
    }

    private fun <T> locked(block: () -> T): T = synchronized(mutex) {
        FileOutputStream(File(directory, ".lock"), true).channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun file(lane: ServerReplicaLane): File {
        val key = "${lane.scope.endpoint.canonicalUrl}\n${lane.scope.accountId}\n${lane.namespace}\n${lane.deviceId}"
        return File(directory, ServerReplicaProtocol.sha256(key.toByteArray(Charsets.UTF_8)) + ".replica")
    }

    private fun read(lane: ServerReplicaLane): ServerReplicaUpload? {
        val file = file(lane)
        if (!file.exists()) return null
        if (file.length() !in 1..(ServerReplicaProtocol.MAX_BYTES + ServerReplicaWire.METADATA_BYTES + 8192L)) {
            throw IOException("Invalid replica journal size")
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DataInputStream(DigestInputStream(FileInputStream(file), digest)).use { input ->
                check(input.readInt() == 0x4b525032) { "Invalid replica journal version" }
                check(input.readUTF() == lane.scope.endpoint.canonicalUrl && input.readUTF() == lane.scope.accountId &&
                    input.readUTF() == lane.namespace && input.readUTF() == lane.deviceId) {
                    "Replica journal scope changed"
                }
                val requestId = input.readUTF()
                val condition = when (input.readUnsignedByte()) {
                    0 -> ServerReplicaCondition.CreateIfNoneMatch
                    1 -> ServerReplicaCondition.UpdateIfMatch(input.readUTF())
                    else -> error("Invalid replica journal condition")
                }
                val expectedHash = input.readUTF()
                val size = input.readInt()
                check(size in 1..ServerReplicaProtocol.MAX_BYTES) { "Invalid replica journal payload size" }
                val bytes = ByteArray(size).also(input::readFully)
                check(ServerReplicaProtocol.sha256(bytes) == expectedHash) { "Replica journal payload hash mismatch" }
                val outcomeSize = input.readInt()
                check(outcomeSize in 0..ServerReplicaWire.METADATA_BYTES) { "Invalid replica journal outcome size" }
                val outcome = if (outcomeSize == 0) null else decodeOutcome(
                    ByteArray(outcomeSize).also(input::readFully), lane.scope,
                )
                val actualDigest = digest.digest()
                val expectedDigest = ByteArray(32).also(input::readFully)
                check(MessageDigest.isEqual(actualDigest, expectedDigest)) { "Replica journal integrity mismatch" }
                check(input.read() == -1) { "Trailing replica journal data" }
                return ServerReplicaUpload(lane, requestId, condition, bytes, outcome)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid durable replica upload", e)
        } catch (e: IllegalStateException) {
            throw IOException("Invalid durable replica upload", e)
        }
    }

    private fun write(upload: ServerReplicaUpload) {
        val temporary = File.createTempFile("replica-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val output = DataOutputStream(DigestOutputStream(stream, digest))
                output.writeInt(0x4b525032)
                output.writeUTF(upload.lane.scope.endpoint.canonicalUrl)
                output.writeUTF(upload.lane.scope.accountId)
                output.writeUTF(upload.lane.namespace)
                output.writeUTF(upload.lane.deviceId)
                output.writeUTF(upload.requestId)
                when (val condition = upload.condition) {
                    ServerReplicaCondition.CreateIfNoneMatch -> output.writeByte(0)
                    is ServerReplicaCondition.UpdateIfMatch -> { output.writeByte(1); output.writeUTF(condition.etag) }
                }
                output.writeUTF(upload.sha256)
                val bytes = upload.bytes()
                output.writeInt(bytes.size)
                output.write(bytes)
                val outcome = upload.outcome?.let(::encodeOutcome) ?: ByteArray(0)
                output.writeInt(outcome.size)
                output.write(outcome)
                output.flush()
                stream.write(digest.digest())
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file(upload.lane).toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            syncDirectory()
        } finally {
            temporary.delete()
        }
    }

    private fun syncDirectory() = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }

    private fun encodeOutcome(outcome: ServerReplicaUploadOutcome): ByteArray {
        val fields = when (outcome) {
            is ServerReplicaUploadOutcome.Accepted -> mapOf("status" to JsonPrimitive(200),
                "receipt" to JsonObject(mapOf(
                "accepted" to ServerReplicaWire.metadataJson(outcome.receipt.accepted),
                "current" to ServerReplicaWire.metadataJson(outcome.receipt.current),
                "replayed" to JsonPrimitive(outcome.receipt.replayed),
            )))
            is ServerReplicaUploadOutcome.Conflict -> mapOf("status" to JsonPrimitive(outcome.status),
                "error" to JsonPrimitive(outcome.errorCode),
                "current" to (outcome.current?.let(ServerReplicaWire::metadataJson) ?: JsonNull))
            is ServerReplicaUploadOutcome.Rejected -> mapOf("status" to JsonPrimitive(outcome.status),
                "error" to JsonPrimitive(outcome.errorCode))
        }
        return JsonObject(fields).toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeOutcome(bytes: ByteArray, scope: ServerReplicaScope): ServerReplicaUploadOutcome {
        val root = ServerReplicaWire.objectValue(bytes)
        val status = (root["status"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw IOException("Invalid durable replica status")
        val caps = ServerReplicaCapabilities(scope.accountId, ServerReplicaProtocol.namespaces,
            ServerReplicaProtocol.MAX_BYTES, 64, 268435456, 100_000)
        return if (status == 200) {
            val receipt = root["receipt"]?.toString()?.toByteArray(Charsets.UTF_8)
                ?: throw IOException("Missing durable replica receipt")
            ServerReplicaUploadOutcome.Accepted(ServerReplicaWire.receipt(receipt, scope, caps))
        } else {
            val error = ServerReplicaWire.error(bytes, status, scope, caps)
            if (status == 409 || status == 412) {
                ServerReplicaUploadOutcome.Conflict(status, error.errorCode, error.current)
            }
            else {
                check(status in 400..499 || status == 507) { "Invalid durable replica rejection" }
                ServerReplicaUploadOutcome.Rejected(status, error.errorCode)
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
