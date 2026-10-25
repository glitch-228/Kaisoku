package org.koitharu.kotatsu.sync.extended.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koitharu.kotatsu.core.util.BoundedJson
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal object ExtendedDriveProtocol {
    const val PREFIX = "kaisoku_extended_v1_"
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_FILES = 128
    const val MAX_ACCOUNT_BYTES = 256L * 1024 * 1024
    val namespaces = linkedSetOf("chapter_reads_v1", "tracker_links_v1", "metadata_overrides_v1",
        "remote_libraries_v1", "foreign_import_state_v1")
    fun hash(bytes: ByteArray) = digest("SHA-256", bytes)
    fun md5(bytes: ByteArray) = digest("MD5", bytes)
    private fun digest(name: String, bytes: ByteArray) = MessageDigest.getInstance(name).digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun uuid(value: String) {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value)
    }
    fun id(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Invalid Drive file ID" } }
    fun permission(value: String) { require(value.isNotBlank() && value.length <= 256 && value.none { it < ' ' }) }
    fun objectValue(bytes: ByteArray, maximum: Int = 1024 * 1024): JsonObject =
        Json.parseToJsonElement(BoundedJson.validateObject(bytes, maximum)) as JsonObject
    fun JsonObject.string(name: String): String = getValue(name).stringValue()
    fun JsonElement.stringValue(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IOException("Invalid extended Drive metadata string")
    fun JsonObject.longString(name: String): Long = string(name).let {
        require(it.matches(Regex("0|[1-9][0-9]{0,18}")))
        it.toLongOrNull() ?: error("Drive metadata integer overflow")
    }
}

data class ExtendedDriveUser(val permissionId: String, val displayName: String?, val emailAddress: String?)

internal data class ExtendedDriveDescriptor(
    val permissionId: String,
    val deviceId: String,
    val writer: String,
    val namespace: String,
    val generation: Long,
    val sha256: String,
    val bytes: Int,
    val previousId: String? = null,
    val previousHash: String? = null,
) {
    init {
        ExtendedDriveProtocol.permission(permissionId)
        ExtendedDriveProtocol.uuid(deviceId)
        require(writer.matches(Regex("[a-f0-9]{64}")) && namespace in ExtendedDriveProtocol.namespaces && generation > 0)
        require(sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 1..ExtendedDriveProtocol.MAX_BYTES)
        require((previousId == null) == (previousHash == null))
        previousId?.let(ExtendedDriveProtocol::id)
        require(previousHash == null || previousHash.matches(Regex("[a-f0-9]{64}")))
    }
    val name: String get() = "${ExtendedDriveProtocol.PREFIX}${deviceId}_${namespace}_${generation}_$sha256.json"
    fun description(): String = JsonObject(mapOf("format" to JsonPrimitive(1), "account" to JsonPrimitive(permissionId),
        "device" to JsonPrimitive(deviceId), "writer" to JsonPrimitive(writer), "namespace" to JsonPrimitive(namespace),
        "generation" to JsonPrimitive(generation.toString()), "sha256" to JsonPrimitive(sha256),
        "bytes" to JsonPrimitive(bytes.toString()), "previousId" to (previousId?.let(::JsonPrimitive) ?: JsonNull),
        "previousHash" to (previousHash?.let(::JsonPrimitive) ?: JsonNull))).toString()
    companion object {
        fun decode(value: String): ExtendedDriveDescriptor = with(ExtendedDriveProtocol) {
            require(value.toByteArray(Charsets.UTF_8).size <= 8192)
            val root = objectValue(value.toByteArray(Charsets.UTF_8), 8192)
            require((root["format"] as? JsonPrimitive)?.content == "1")
            ExtendedDriveDescriptor(root.string("account"), root.string("device"), root.string("writer"),
                root.string("namespace"), root.longString("generation"), root.string("sha256"),
                root.longString("bytes").also { require(it <= MAX_BYTES) }.toInt(),
                root["previousId"]?.takeUnless { it == JsonNull }?.stringValue(),
                root["previousHash"]?.takeUnless { it == JsonNull }?.stringValue())
        }
    }
}

internal data class ExtendedDriveFile(
    val id: String,
    val descriptor: ExtendedDriveDescriptor,
    val version: String,
    val md5: String,
) {
    init {
        ExtendedDriveProtocol.id(id)
        require(version.matches(Regex("[1-9][0-9]{0,18}")) && version.toLongOrNull() != null &&
            md5.matches(Regex("[a-f0-9]{32}")))
    }
    fun metadata(): JsonObject = JsonObject(mapOf("id" to JsonPrimitive(id),
        "name" to JsonPrimitive(descriptor.name), "description" to JsonPrimitive(descriptor.description()),
        "version" to JsonPrimitive(version), "md5Checksum" to JsonPrimitive(md5),
        "size" to JsonPrimitive(descriptor.bytes.toString()), "mimeType" to JsonPrimitive("application/json"),
        "spaces" to JsonArray(listOf(JsonPrimitive("appDataFolder"))), "trashed" to JsonPrimitive(false),
        "ownedByMe" to JsonPrimitive(true)))
    companion object {
        fun decode(root: JsonObject, permission: String): ExtendedDriveFile = with(ExtendedDriveProtocol) {
            val descriptor = ExtendedDriveDescriptor.decode(root.string("description"))
            require(descriptor.permissionId == permission && root.string("name") == descriptor.name &&
                root.string("mimeType") == "application/json" && root.longString("size") == descriptor.bytes.toLong())
            require((root["trashed"] as? JsonPrimitive)?.content == "false" &&
                (root["ownedByMe"] as? JsonPrimitive)?.content == "true")
            require((root["spaces"] as? JsonArray)?.map { it.stringValue() } == listOf("appDataFolder"))
            ExtendedDriveFile(root.string("id"), descriptor, root.string("version"), root.string("md5Checksum"))
        }
    }
}

internal interface ExtendedDrivePayloads {
    val namespaces: Set<String>
    suspend fun export(namespace: String, checkCurrent: () -> Unit): ByteArray
    suspend fun merge(namespace: String, bytes: ByteArray, checkCurrent: () -> Unit)
    suspend fun validateSuccessor(namespace: String, predecessor: ByteArray, candidate: ByteArray)
}

internal class ExtendedDriveSession(
    val permissionId: String,
    val token: String,
    val checkCurrent: () -> Unit,
)

internal interface ExtendedDriveTransport {
    suspend fun user(token: String, guard: () -> Unit): ExtendedDriveUser
    suspend fun list(session: ExtendedDriveSession): List<ExtendedDriveFile>
    suspend fun generateId(session: ExtendedDriveSession): String
    suspend fun metadata(session: ExtendedDriveSession, id: String): ExtendedDriveFile?
    suspend fun download(session: ExtendedDriveSession, file: ExtendedDriveFile): ByteArray
    suspend fun create(session: ExtendedDriveSession, id: String, descriptor: ExtendedDriveDescriptor, bytes: ByteArray)
    suspend fun delete(session: ExtendedDriveSession, file: ExtendedDriveFile)
}

enum class ExtendedDriveStatus {
    DISABLED, READY, RUNNING, SUCCESS, PARTIAL, SIGN_IN_REQUIRED, STALE_SESSION, RECOVERY_REQUIRED,
    REVIEW_REQUIRED, STORAGE_ERROR, NETWORK_ERROR, QUOTA, BUSY,
}

data class ExtendedDriveResult(val status: ExtendedDriveStatus, val lanes: Map<String, ExtendedDriveStatus> = emptyMap())

internal class ExtendedDriveHttpException(val status: Int) : IOException("Extended Drive HTTP $status")
