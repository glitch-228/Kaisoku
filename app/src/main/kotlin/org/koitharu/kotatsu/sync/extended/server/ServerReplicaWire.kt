package org.koitharu.kotatsu.sync.extended.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ServerReplicaWire {
    const val METADATA_BYTES = 512 * 1024
    private val json = Json

    fun objectValue(bytes: ByteArray): JsonObject = try {
        json.parseToJsonElement(ServerReplicaJson.validate(bytes, METADATA_BYTES)) as? JsonObject
            ?: invalid("Expected JSON object")
    } catch (e: ServerReplicaProtocolException) {
        throw e
    } catch (_: IllegalArgumentException) {
        invalid("Invalid replica metadata JSON")
    }

    fun capabilities(bytes: ByteArray, scope: ServerReplicaScope): ServerReplicaCapabilities {
        val root = objectValue(bytes)
        expect(root.int("protocol") == 1, "Unsupported replica protocol")
        expect(root.string("accountId") == scope.accountId, "Replica account changed")
        expect(root.boolean("compareAndSwap") && root.boolean("retainedIdempotency"),
            "Replica CAS recovery unavailable")
        val list = root.array("namespaces").map { it.stringValue() }
        expect(list.isNotEmpty() && list.size <= ServerReplicaProtocol.namespaces.size &&
            list.distinct().size == list.size && list.all { it in ServerReplicaProtocol.namespaces },
            "Unsupported replica namespaces")
        val maximum = root.int("maxPayloadBytes")
        val lanes = root.int("maxLanes")
        val accountBytes = root.long("maxAccountBytes")
        val keys = root.int("maxMutationKeys")
        expect(maximum in 1..ServerReplicaProtocol.MAX_BYTES && lanes in 1..64 &&
            accountBytes in 1..268435456L && keys in 1..100_000, "Unsupported replica limits")
        return ServerReplicaCapabilities(scope.accountId, list.toSet(), maximum, lanes, accountBytes, keys)
    }

    fun manifest(bytes: ByteArray, scope: ServerReplicaScope,
        caps: ServerReplicaCapabilities): List<ServerReplicaMetadata> {
        val root = objectValue(bytes)
        expect(root.int("protocol") == 1 && root["nextCursor"] == JsonNull, "Incomplete replica manifest")
        val items = root.array("replicas")
        expect(items.size <= caps.maxLanes, "Replica manifest exceeds lane limit")
        val result = items.map { metadata(it, scope, caps) }
        expect(result.map { it.namespace to it.deviceId }.distinct().size == result.size, "Duplicate replica lane")
        expect(result.sumOf { it.bytes.toLong() } <= caps.maxAccountBytes, "Replica manifest exceeds account limit")
        return result
    }

    fun metadata(element: JsonElement, scope: ServerReplicaScope,
        caps: ServerReplicaCapabilities): ServerReplicaMetadata {
        val value = element as? JsonObject ?: invalid("Invalid replica metadata")
        val result = ServerReplicaMetadata(value.string("accountId"), value.string("namespace"),
            value.string("deviceId"),
            value.int("payloadVersion"), value.string("revision"), value.string("sha256"), value.int("bytes"),
            value.long("updatedAt"), value.string("etag"))
        expect(result.accountId == scope.accountId && result.namespace in caps.namespaces,
            "Replica metadata scope changed")
        expect(runCatching { ServerReplicaProtocol.requireUuid(result.deviceId) }.isSuccess, "Invalid replica device")
        expect(result.payloadVersion == 1 && result.revision.matches(ServerReplicaProtocol.revisionPattern) &&
            result.revision.toLongOrNull() != null, "Invalid replica version")
        expect(result.sha256.matches(ServerReplicaProtocol.hashPattern) && result.bytes in 1..caps.maxPayloadBytes &&
            result.updatedAt >= 0, "Invalid replica hash or bounds")
        expect(result.etag == "\"r${result.revision}-${result.sha256}\"", "Invalid strong replica ETag")
        return result
    }

    fun receipt(bytes: ByteArray, scope: ServerReplicaScope, caps: ServerReplicaCapabilities): ServerReplicaReceipt {
        val root = objectValue(bytes)
        return ServerReplicaReceipt(metadata(root["accepted"] ?: invalid("Missing accepted metadata"), scope, caps),
            metadata(root["current"] ?: invalid("Missing current metadata"), scope, caps), root.boolean("replayed"))
    }

    fun error(bytes: ByteArray, status: Int, scope: ServerReplicaScope,
        caps: ServerReplicaCapabilities): ServerReplicaHttpException {
        val root = objectValue(bytes)
        val code = root.string("error")
        expect(code.matches(Regex("[a-z0-9_]{1,100}")), "Invalid replica error code")
        val current = root["current"]?.takeUnless { it == JsonNull }?.let { metadata(it, scope, caps) }
        return ServerReplicaHttpException(status, code, current)
    }

    fun metadataJson(value: ServerReplicaMetadata): JsonObject = JsonObject(mapOf(
        "accountId" to JsonPrimitive(value.accountId), "namespace" to JsonPrimitive(value.namespace),
        "deviceId" to JsonPrimitive(value.deviceId), "payloadVersion" to JsonPrimitive(value.payloadVersion),
        "revision" to JsonPrimitive(value.revision), "sha256" to JsonPrimitive(value.sha256),
        "bytes" to JsonPrimitive(value.bytes), "updatedAt" to JsonPrimitive(value.updatedAt),
        "etag" to JsonPrimitive(value.etag),
    ))

    private fun JsonObject.string(name: String): String = get(name)?.stringValue() ?: invalid("Missing $name")
    private fun JsonElement.stringValue(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: invalid("Expected metadata string")
    private fun JsonObject.long(name: String): Long {
        val value = get(name) as? JsonPrimitive ?: invalid("Missing $name")
        expect(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*")), "Invalid metadata integer")
        return value.content.toLongOrNull() ?: invalid("Metadata integer overflow")
    }
    private fun JsonObject.int(name: String): Int = long(name).let {
        expect(it <= Int.MAX_VALUE, "Metadata integer overflow")
        it.toInt()
    }
    private fun JsonObject.boolean(name: String): Boolean {
        val value = get(name) as? JsonPrimitive ?: invalid("Missing $name")
        expect(!value.isString && value.content in setOf("true", "false"), "Invalid metadata boolean")
        return value.content == "true"
    }
    private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: invalid("Missing $name")
    internal fun expect(condition: Boolean, message: String) { if (!condition) invalid(message) }
    internal fun invalid(message: String): Nothing = throw ServerReplicaProtocolException(message)
}
