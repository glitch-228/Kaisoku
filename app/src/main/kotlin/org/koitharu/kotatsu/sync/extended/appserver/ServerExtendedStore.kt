package org.koitharu.kotatsu.sync.extended.appserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koitharu.kotatsu.core.util.BoundedJson
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaCapabilities
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaEndpoint
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaMetadata
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaProtocol
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaScope
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaWire
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Owner supplies a no-backup directory. Never stores credentials or changes the upload journal lifecycle. */
class ServerExtendedStore(private val directory: File) {
    private val stateFile = File(directory, "state.json")
    private val deviceFile = File(directory, "device.uuid")
    private var root: JsonObject? = null

    @Synchronized fun configuration(): ServerExtendedConfiguration {
        val value = state()["configuration"] as? JsonObject
            ?: return ServerExtendedConfiguration(generation = "unconfigured")
        val binding = (value["binding"] as? JsonObject)?.let {
            ServerExtendedBinding(it.string("name"), it.string("type"), it.string("endpoint"),
                it.string("accountId"), it.string("nonce")).also { row ->
                ServerReplicaEndpoint.parse(row.endpoint, allowCleartextPrivateAddress = true)
                ServerReplicaScope(ServerReplicaEndpoint.parse(row.endpoint, true), row.accountId)
                ServerReplicaProtocol.requireUuid(row.installationNonce)
            }
        }
        val namespaces = value.array("namespaces").map { it.primitiveString() }.toSet()
        check(namespaces.all { it in ServerExtendedNamespaces.all }) { "Invalid extended namespace settings" }
        return ServerExtendedConfiguration(value.string("generation"), value.boolean("enabled"), binding, namespaces,
            value.boolean("allowCleartext"), value.boolean("automatic"), value.boolean("syncOnStart"),
            value.boolean("wifiOnly"), value.integer("interval").also { check(it in 15..10080) })
    }

    @Synchronized fun configure(value: ServerExtendedConfiguration, guard: () -> Unit = {}) {
        require(value.namespaces.all { it in ServerExtendedNamespaces.all } && value.intervalMinutes in 15..10080)
        require(!value.enabled || value.binding != null && value.namespaces.isNotEmpty())
        val binding = value.binding?.let { JsonObject(mapOf("name" to JsonPrimitive(it.accountName),
            "type" to JsonPrimitive(it.accountType), "endpoint" to JsonPrimitive(it.endpoint),
            "accountId" to JsonPrimitive(it.accountId), "nonce" to JsonPrimitive(it.installationNonce))) } ?: JsonNull
        val configuration = JsonObject(mapOf("generation" to JsonPrimitive(UUID.randomUUID().toString()),
            "enabled" to JsonPrimitive(value.enabled), "binding" to binding,
            "namespaces" to JsonArray(value.namespaces.sorted().map(::JsonPrimitive)),
            "allowCleartext" to JsonPrimitive(value.allowCleartext), "automatic" to JsonPrimitive(value.automatic),
            "syncOnStart" to JsonPrimitive(value.syncOnStart), "wifiOnly" to JsonPrimitive(value.wifiOnly),
            "interval" to JsonPrimitive(value.intervalMinutes)))
        update("configuration", configuration, guard)
    }

    @Synchronized fun deviceId(): String {
        if (deviceFile.exists()) return deviceFile.readText(Charsets.US_ASCII).also(ServerReplicaProtocol::requireUuid)
        check(File(directory, "uploads").listFiles()?.none { it.extension == "replica" } != false) {
            "Replica device identity is missing; pending uploads require recovery"
        }
        val device = UUID.randomUUID().toString()
        atomic(deviceFile, device.toByteArray(Charsets.US_ASCII))
        return device
    }

    @Synchronized fun applied(scope: ServerReplicaScope, namespace: String, device: String): ServerReplicaMetadata? {
        val element = (state()["applied"] as? JsonObject)?.get(key(scope, namespace, device)) ?: return null
        val caps = ServerReplicaCapabilities(scope.accountId, ServerExtendedNamespaces.all,
            ServerReplicaProtocol.MAX_BYTES, 64, 268435456, 100000)
        return ServerReplicaWire.metadata(element, scope, caps)
    }

    @Synchronized fun markApplied(scope: ServerReplicaScope, metadata: ServerReplicaMetadata, guard: () -> Unit) {
        check(metadata.accountId == scope.accountId)
        val next = ((state()["applied"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
        next[key(scope, metadata.namespace, metadata.deviceId)] = ServerReplicaWire.metadataJson(metadata)
        check(next.size <= 2048) { "Extended replica checkpoint limit reached" }
        update("applied", JsonObject(next), guard)
    }

    @Synchronized fun paused(scope: ServerReplicaScope, namespace: String): ServerExtendedStatus? {
        val value = (state()["pauses"] as? JsonObject)?.get(pauseKey(scope, namespace)) ?: return null
        return ServerExtendedStatus.valueOf(value.primitiveString())
    }

    @Synchronized fun pause(scope: ServerReplicaScope, namespace: String, status: ServerExtendedStatus?,
        guard: () -> Unit) {
        val values = (state()["pauses"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val key = pauseKey(scope, namespace)
        if (status == null) values.remove(key) else values[key] = JsonPrimitive(status.name)
        check(values.size <= 160) { "Extended paused-scope limit reached" }
        update("pauses", JsonObject(values), guard)
    }

    private fun pauseKey(scope: ServerReplicaScope, namespace: String): String {
        require(namespace in ServerExtendedNamespaces.all)
        return ServerReplicaProtocol.sha256("${scope.endpoint.canonicalUrl}\n${scope.accountId}\n$namespace"
            .toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun consumed(requestId: String): Boolean =
        (state()["consumed"] as? JsonArray)?.any { it.primitiveString() == requestId } == true

    @Synchronized fun consume(requestId: String, guard: () -> Unit) {
        ServerReplicaProtocol.requireUuid(requestId)
        val values = (state()["consumed"] as? JsonArray)?.map { it.primitiveString() }?.toMutableSet() ?: mutableSetOf()
        values += requestId
        check(values.size <= 1000) { "Unacknowledged replica receipt limit reached" }
        update("consumed", JsonArray(values.sorted().map(::JsonPrimitive)), guard)
    }

    @Synchronized fun forgetConsumed(requestId: String, guard: () -> Unit) {
        val values = (state()["consumed"] as? JsonArray)?.filter { it.primitiveString() != requestId } ?: emptyList()
        update("consumed", JsonArray(values), guard)
    }

    @Synchronized fun saveResult(result: ServerExtendedResult, guard: () -> Unit = {}) {
        require(result.lanes.size <= 64)
        update("result", JsonObject(mapOf("status" to JsonPrimitive(result.status.name),
            "time" to JsonPrimitive(System.currentTimeMillis()),
            "lanes" to JsonObject(result.lanes.mapValues { JsonPrimitive(it.value.name) }))), guard)
    }

    @Synchronized fun lastResult(): ServerExtendedResult {
        val result = state()["result"] as? JsonObject ?: return ServerExtendedResult(ServerExtendedStatus.DISABLED)
        val status = ServerExtendedStatus.valueOf(result.string("status"))
        val lanes = (result["lanes"] as? JsonObject)?.mapValues {
            ServerExtendedStatus.valueOf(it.value.primitiveString())
        }
            ?: emptyMap()
        return ServerExtendedResult(status, lanes)
    }

    @Synchronized fun retainConflict(scope: ServerReplicaScope, metadata: ServerReplicaMetadata, bytes: ByteArray,
        guard: () -> Unit) {
        require(bytes.size <= ServerReplicaProtocol.MAX_BYTES && ServerReplicaProtocol.sha256(bytes) == metadata.sha256)
        val inbox = File(directory, "inbox")
        val target = File(inbox, key(scope, metadata.namespace, metadata.deviceId) + ".json")
        val total = inbox.listFiles()?.sumOf { it.length() } ?: 0L
        check(total - (if (target.exists()) target.length() else 0L) + bytes.size <= 268435456L) {
            "Extended conflict inbox is full"
        }
        check(target.exists() || (inbox.listFiles()?.size ?: 0) < 64) { "Extended conflict inbox is full" }
        atomic(target, bytes, guard)
    }

    private fun key(scope: ServerReplicaScope, namespace: String, device: String): String {
        require(namespace in ServerExtendedNamespaces.all)
        ServerReplicaProtocol.requireUuid(device)
        return ServerReplicaProtocol.sha256("${scope.endpoint.canonicalUrl}\n${scope.accountId}\n$namespace\n$device"
            .toByteArray(Charsets.UTF_8))
    }

    private fun state(): JsonObject = root ?: if (!stateFile.exists()) JsonObject(emptyMap()).also { root = it } else {
        if (stateFile.length() !in 1..MAX_STATE_BYTES.toLong()) throw IOException("Invalid extended sync state size")
        val bytes = stateFile.readBytes()
        val value = Json.parseToJsonElement(BoundedJson.validateObject(bytes, MAX_STATE_BYTES)) as? JsonObject
            ?: throw IOException("Invalid extended sync state")
        check(value.integer("version") == 1) { "Unsupported extended sync state" }
        value.also { root = it }
    }

    private fun update(name: String, value: kotlinx.serialization.json.JsonElement, guard: () -> Unit) {
        val next = JsonObject(state().toMutableMap().apply { put("version", JsonPrimitive(1)); put(name, value) })
        val bytes = next.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_STATE_BYTES) { "Extended sync state is full" }
        atomic(stateFile, bytes, guard)
        root = next
    }

    private fun atomic(target: File, bytes: ByteArray, guard: () -> Unit = {}) {
        val parent = checkNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Extended sync storage is unavailable")
        val stage = File.createTempFile("state-", ".tmp", parent)
        try {
            FileOutputStream(stage).use { it.write(bytes); it.fd.sync() }
            guard()
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { stage.delete() }
    }

    private fun JsonObject.string(name: String) = getValue(name).primitiveString()
    private fun kotlinx.serialization.json.JsonElement.primitiveString(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid extended sync state string")
    private fun JsonObject.array(name: String) = get(name) as? JsonArray ?: error("Invalid extended sync state array")
    private fun JsonObject.boolean(name: String): Boolean {
        val item = get(name) as? JsonPrimitive ?: error("Invalid extended sync state boolean")
        check(!item.isString && item.content in setOf("true", "false"))
        return item.content == "true"
    }
    private fun JsonObject.integer(name: String): Int {
        val item = get(name) as? JsonPrimitive ?: error("Invalid extended sync state number")
        check(!item.isString)
        return item.content.toIntOrNull() ?: error("Invalid extended sync state number")
    }

    companion object { private const val MAX_STATE_BYTES = 2 * 1024 * 1024 }
}
