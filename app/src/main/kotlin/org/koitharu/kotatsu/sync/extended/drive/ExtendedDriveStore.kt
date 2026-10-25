package org.koitharu.kotatsu.sync.extended.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveProtocol.string
import org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveProtocol.stringValue
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ExtendedDriveConfiguration(
    val generation: String = "unconfigured",
    val enabled: Boolean = false,
    val permissionId: String? = null,
    val accountLabel: String? = null,
    val namespaces: Set<String> = emptySet(),
    val automatic: Boolean = false,
)

internal data class ExtendedDriveIdentity(val deviceId: String, val writer: String)
internal class ExtendedDrivePublication(
    val id: String,
    val descriptor: ExtendedDriveDescriptor,
    private val content: ByteArray,
    val previous: ExtendedDriveFile?,
    val accepted: ExtendedDriveFile? = null,
) {
    fun bytes() = content.copyOf()
    init {
        ExtendedDriveProtocol.id(id)
        require(content.size == descriptor.bytes && ExtendedDriveProtocol.hash(content) == descriptor.sha256)
        require(previous?.id == descriptor.previousId && previous?.descriptor?.sha256 == descriptor.previousHash)
        require(accepted == null || accepted.id == id && accepted.descriptor == descriptor)
    }
    fun accepted(file: ExtendedDriveFile) = ExtendedDrivePublication(id, descriptor, content, previous, file)
}

/** Private installation state is never stored in Android/native backup preferences or the legacy Drive journal. */
internal class ExtendedDriveStore(val directory: File) {
    private val stateFile = File(directory, "state.json")
    private val publications = File(directory, "publications")
    private val mutex = locks.computeIfAbsent(directory.canonicalPath) { Mutex() }

    suspend fun <T> withWriter(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Extended Drive storage unavailable")
            RandomAccessFile(File(directory, ".writer-lock"), "rw").channel.use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                checkNotNull(lock) { "Another extended Drive writer is active" }.use { block() }
            }
        }
    }

    @Synchronized fun configuration(): ExtendedDriveConfiguration {
        val value = state()["configuration"] as? JsonObject ?: return ExtendedDriveConfiguration()
        val enabled = value.boolean("enabled")
        val owner = value["permissionId"]?.takeUnless { it == JsonNull }?.stringValue()
        owner?.let(ExtendedDriveProtocol::permission)
        val namespaces = (value["namespaces"] as? JsonArray)?.map { it.stringValue() }?.toSet() ?: error("Missing content")
        require(namespaces.all { it in ExtendedDriveProtocol.namespaces } && (!enabled || owner != null && namespaces.isNotEmpty()))
        return ExtendedDriveConfiguration(value.string("generation"), enabled, owner,
            value["label"]?.takeUnless { it == JsonNull }?.stringValue(), namespaces, value.boolean("automatic"))
    }

    @Synchronized fun configure(value: ExtendedDriveConfiguration, guard: () -> Unit = {}) {
        require(value.namespaces.all { it in ExtendedDriveProtocol.namespaces })
        require(!value.enabled || value.permissionId != null && value.namespaces.isNotEmpty())
        value.permissionId?.let(ExtendedDriveProtocol::permission)
        update("configuration", JsonObject(mapOf("generation" to JsonPrimitive(UUID.randomUUID().toString()),
            "enabled" to JsonPrimitive(value.enabled), "permissionId" to (value.permissionId?.let(::JsonPrimitive) ?: JsonNull),
            "label" to (value.accountLabel?.take(512)?.let(::JsonPrimitive) ?: JsonNull),
            "namespaces" to JsonArray(value.namespaces.sorted().map(::JsonPrimitive)),
            "automatic" to JsonPrimitive(value.automatic))), guard)
    }

    @Synchronized fun identity(): ExtendedDriveIdentity {
        val target = File(directory, "installation.json")
        if (target.exists()) {
            require(target.length() in 1..4096)
            val root = ExtendedDriveProtocol.objectValue(target.readBytes(), 4096)
            return ExtendedDriveIdentity(root.string("device"), root.string("writer")).also {
                ExtendedDriveProtocol.uuid(it.deviceId)
                require(it.writer.matches(Regex("[a-f0-9]{64}")))
            }
        }
        check(publications.listFiles()?.none { it.extension == "pending" } != false &&
            (state()["owned"] as? JsonObject).isNullOrEmpty()) { "Missing Drive writer identity requires review" }
        val identity = ExtendedDriveIdentity(UUID.randomUUID().toString(),
            ExtendedDriveProtocol.hash(UUID.randomUUID().toString().toByteArray()))
        atomic(target) { it.write(JsonObject(mapOf("device" to JsonPrimitive(identity.deviceId),
            "writer" to JsonPrimitive(identity.writer))).toString().toByteArray()) }
        return identity
    }

    @Synchronized fun owned(owner: String, namespace: String): ExtendedDriveFile? =
        (state()["owned"] as? JsonObject)?.get(laneKey(owner, namespace))?.let {
            ExtendedDriveFile.decode(it as JsonObject, owner)
        }

    @Synchronized fun own(owner: String, file: ExtendedDriveFile, guard: () -> Unit) {
        val values = (state()["owned"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        values[laneKey(owner, file.descriptor.namespace)] = file.metadata()
        require(values.size <= 160)
        update("owned", JsonObject(values), guard)
    }

    @Synchronized fun applied(file: ExtendedDriveFile): Boolean =
        (state()["applied"] as? JsonObject)?.get(file.id)?.stringValue() == fingerprint(file)

    @Synchronized fun markApplied(file: ExtendedDriveFile, guard: () -> Unit) {
        val values = (state()["applied"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        values[file.id] = JsonPrimitive(fingerprint(file))
        require(values.size <= 256) { "Too many Drive checkpoints" }
        update("applied", JsonObject(values), guard)
    }

    @Synchronized fun pruneApplied(ids: Set<String>, guard: () -> Unit) {
        val values = (state()["applied"] as? JsonObject)?.filterKeys { it in ids }.orEmpty()
        update("applied", JsonObject(values), guard)
    }

    @Synchronized fun result(value: ExtendedDriveResult, guard: () -> Unit = {}) {
        update("result", JsonObject(mapOf("status" to JsonPrimitive(value.status.name),
            "lanes" to JsonObject(value.lanes.mapValues { JsonPrimitive(it.value.name) }))), guard)
    }
    @Synchronized fun result(): ExtendedDriveResult {
        val value = state()["result"] as? JsonObject ?: return ExtendedDriveResult(ExtendedDriveStatus.DISABLED)
        return ExtendedDriveResult(ExtendedDriveStatus.valueOf(value.string("status")),
            (value["lanes"] as? JsonObject)?.mapValues { ExtendedDriveStatus.valueOf(it.value.stringValue()) }.orEmpty())
    }

    @Synchronized fun publication(owner: String, namespace: String): ExtendedDrivePublication? {
        val file = File(publications, laneKey(owner, namespace) + ".pending")
        if (!file.exists()) return null
        require(file.length() in 1..(ExtendedDriveProtocol.MAX_BYTES + 65536L))
        val digest = MessageDigest.getInstance("SHA-256")
        DataInputStream(DigestInputStream(FileInputStream(file), digest)).use { input ->
            require(input.readInt() == 0x4b454431)
            val length = input.readInt().also { require(it in 1..32768) }
            val header = ExtendedDriveProtocol.objectValue(ByteArray(length).also(input::readFully), 32768)
            val id = header.string("id")
            val descriptor = ExtendedDriveDescriptor.decode(header.string("description"))
            require(descriptor.permissionId == owner && descriptor.namespace == namespace)
            val content = ByteArray(descriptor.bytes).also(input::readFully)
            val expected = digest.digest()
            require(MessageDigest.isEqual(expected, ByteArray(32).also(input::readFully)) && input.read() == -1)
            val previous = (header["previous"] as? JsonObject)?.let { ExtendedDriveFile.decode(it, owner) }
            val accepted = (header["accepted"] as? JsonObject)?.let { ExtendedDriveFile.decode(it, owner) }
            return ExtendedDrivePublication(id, descriptor, content, previous, accepted)
        }
    }

    @Synchronized fun prepare(value: ExtendedDrivePublication, guard: () -> Unit) {
        check(publication(value.descriptor.permissionId, value.descriptor.namespace) == null) { "Drive publication pending" }
        writePublication(value, guard)
    }
    @Synchronized fun accept(value: ExtendedDrivePublication, file: ExtendedDriveFile, guard: () -> Unit) {
        val current = checkNotNull(publication(value.descriptor.permissionId, value.descriptor.namespace))
        check(current.id == value.id && current.descriptor == value.descriptor)
        writePublication(value.accepted(file), guard)
    }
    @Synchronized fun finish(value: ExtendedDrivePublication, guard: () -> Unit) {
        val current = checkNotNull(publication(value.descriptor.permissionId, value.descriptor.namespace))
        check(current.id == value.id && current.accepted != null)
        guard()
        check(File(publications, laneKey(value.descriptor.permissionId, value.descriptor.namespace) + ".pending").delete())
        syncDirectory(publications)
    }

    private fun writePublication(value: ExtendedDrivePublication, guard: () -> Unit) {
        val header = JsonObject(mapOf("id" to JsonPrimitive(value.id), "description" to JsonPrimitive(value.descriptor.description()),
            "previous" to (value.previous?.metadata() ?: JsonNull), "accepted" to (value.accepted?.metadata() ?: JsonNull)))
            .toString().toByteArray(Charsets.UTF_8)
        require(header.size <= 32768)
        val target = File(publications, laneKey(value.descriptor.permissionId, value.descriptor.namespace) + ".pending")
        val retained = publications.listFiles().orEmpty().filter { it.extension == "pending" && it != target }
        require(retained.size < 160 && retained.sumOf { it.length() } + value.descriptor.bytes + header.size + 40L <=
            ExtendedDriveProtocol.MAX_ACCOUNT_BYTES) { "Pending Drive transfers exceed the private storage budget; recover them first" }
        atomic(target, guard) { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val output = DataOutputStream(DigestOutputStream(stream, digest))
            output.writeInt(0x4b454431)
            output.writeInt(header.size)
            output.write(header)
            output.write(value.bytes())
            output.flush()
            stream.write(digest.digest())
        }
    }

    private fun laneKey(owner: String, namespace: String): String {
        ExtendedDriveProtocol.permission(owner)
        require(namespace in ExtendedDriveProtocol.namespaces)
        return ExtendedDriveProtocol.hash("$owner\n$namespace".toByteArray(Charsets.UTF_8))
    }
    private fun fingerprint(file: ExtendedDriveFile) = "${file.version}:${file.descriptor.sha256}:${file.md5}"
    private fun state(): JsonObject {
        if (!stateFile.exists()) return JsonObject(emptyMap())
        require(stateFile.length() in 1..(2 * 1024 * 1024).toLong())
        return ExtendedDriveProtocol.objectValue(stateFile.readBytes(), 2 * 1024 * 1024).also {
            require((it["version"] as? JsonPrimitive)?.content == "1")
        }
    }
    private fun update(name: String, value: kotlinx.serialization.json.JsonElement, guard: () -> Unit = {}) {
        val next = JsonObject(state().toMutableMap().apply { put("version", JsonPrimitive(1)); put(name, value) })
            .toString().toByteArray(Charsets.UTF_8)
        require(next.size <= 2 * 1024 * 1024)
        atomic(stateFile, guard) { it.write(next) }
    }
    private fun JsonObject.boolean(name: String): Boolean {
        val value = get(name) as? JsonPrimitive ?: error("Invalid Drive settings")
        require(!value.isString && value.content in setOf("true", "false"))
        return value.content == "true"
    }
    private fun atomic(target: File, guard: () -> Unit = {}, write: (FileOutputStream) -> Unit) {
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("extended-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { write(it); it.fd.sync() }
            guard()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(parent)
        } finally { temporary.delete() }
    }
    private fun syncDirectory(path: File) = FileChannel.open(path.toPath(), StandardOpenOption.READ).use { it.force(true) }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
