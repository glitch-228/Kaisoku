package org.koitharu.kotatsu.remote.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class LibraryTransferPreview(val connectionsToAdd: List<RemoteLibraryConnection>,
                                  val acquisitionsToAdd: List<LibraryAcquisitionRecord>,
                                  val conflicts: List<String>)

/** Versioned durable identity/acquisition metadata. Passwords and derived catalog caches are absent. */
class RemoteLibraryStore(private val file: File,
                         private val verificationFile: File = File(file.parentFile, file.name + ".verified")) {
    private var connections = LinkedHashMap<String, RemoteLibraryConnection>()
    private var acquisitions = LinkedHashMap<String, LibraryAcquisitionRecord>()
    private var localAcquisitions = HashMap<Pair<Long, String>, LibraryAcquisitionRecord>()
    private var verifications = readVerifications()

    init {
        if (file.exists()) {
            val state = decode(readBounded(file))
            connections.putAll(state.first.associateBy { it.id })
            acquisitions.putAll(state.second.associateBy(::acquisitionKey))
            indexLocals()
        }
    }

    @Synchronized fun connections(): List<RemoteLibraryConnection> = connections.values.toList()
    @Synchronized fun connection(id: String): RemoteLibraryConnection? = connections[id]
    @Synchronized fun acquisitions(): List<LibraryAcquisitionRecord> = acquisitions.values.toList()
    @Synchronized fun acquired(connectionId: String, publicationId: String): List<LibraryAcquisitionRecord> =
        acquisitions.values.filter { it.connectionId == connectionId && it.publicationId == publicationId }
    @Synchronized fun acquiredForLocal(mangaId: Long, url: String): LibraryAcquisitionRecord? = localAcquisitions[mangaId to url]

    /** Called only by local-repository I/O paths. Filtering getters use the published memory snapshot. */
    @Synchronized internal fun metadataForLocal(mangaId: Long, url: String): LibraryAcquiredMetadata? {
        val records = acquisitions.values.filter { it.localMangaId == mangaId && it.localUrl == url }
        return acquiredMetadata(records, records.filter(::isVerified))
    }

    @Synchronized internal fun publish() {
        RemoteLibrarySourceRegistry.replaceAll(connections.values, acquisitions.values, acquisitions.values.filter(::isVerified))
    }

    @Synchronized internal fun recordVerified(record: LibraryAcquisitionRecord, verification: LibraryAcquisitionVerification) {
        requireVerification(record, verification)
        record(record)
        markVerified(record, verification)
    }

    /** Recheck receipt, connection scope and current file atomically; a transferred receipt is never proof. */
    @Synchronized internal fun markVerified(record: LibraryAcquisitionRecord, verification: LibraryAcquisitionVerification) {
        require(acquisitions[acquisitionKey(record)] == record) { "The acquisition reference changed during verification" }
        requireVerification(record, verification)
        val next = LinkedHashMap(verifications).apply { put(verificationKey(record), verification.stamp) }
        persistVerifications(next)
        verifications = next
    }

    private fun requireVerification(record: LibraryAcquisitionRecord, verification: LibraryAcquisitionVerification) {
        val connection = connections[record.connectionId] ?: throw RemoteLibraryException("This library connection is unavailable")
        if (verification.record != record || verification.scope != LibraryAcquisitionVerifier.scope(connection) ||
            verification.stamp != LibraryAcquisitionFileStamp.capture(LibraryAcquisitionVerifier.localFile(record.localUrl))) {
            throw RemoteLibraryException("The acquisition changed during verification; reopen it before continuing")
        }
    }

    private fun verificationKey(record: LibraryAcquisitionRecord): String {
        val connection = connections[record.connectionId] ?: return ""
        return libraryDigest(LibraryAcquisitionVerifier.scope(connection) + "\u0000" + encode(emptyList(), listOf(record)))
    }

    private fun isVerified(record: LibraryAcquisitionRecord): Boolean {
        val expected = verifications[verificationKey(record)] ?: return false
        return try { expected == LibraryAcquisitionFileStamp.capture(LibraryAcquisitionVerifier.localFile(record.localUrl)) }
        catch (_: IOException) { false }
        catch (_: IllegalArgumentException) { false }
        catch (_: SecurityException) { false }
    }

    private fun readVerifications(): Map<String, LibraryAcquisitionFileStamp> = try {
        if (!verificationFile.exists()) emptyMap() else {
            val root = Json.parseToJsonElement(readBounded(verificationFile)) as? JsonObject
                ?: throw RemoteLibraryException("Invalid local acquisition verification")
            require((root["version"] as? JsonPrimitive)?.intOrNull == 1)
            val records = root["verified"] as? JsonObject ?: throw RemoteLibraryException("Missing local acquisition verification")
            require(records.size <= MAX_ACQUISITIONS)
            records.mapValues { (key, element) ->
                require(Regex("[a-f0-9]{64}").matches(key))
                val stamp = element as? JsonObject ?: throw RemoteLibraryException("Invalid local acquisition verification")
                LibraryAcquisitionFileStamp(stamp.required("path"),
                    (stamp["size"] as? JsonPrimitive)?.longOrNull ?: throw RemoteLibraryException("Invalid verification size"),
                    stamp.required("modified"), stamp.required("created"), stamp.required("fileKey", true))
            }
        }
    } catch (_: IOException) { emptyMap() }
    catch (_: IllegalArgumentException) { emptyMap() }
    catch (_: SecurityException) { emptyMap() }

    private fun persistVerifications(value: Map<String, LibraryAcquisitionFileStamp>) {
        // Drop stale receipts so repeated explicit downloads cannot grow this device-local file forever.
        val current = acquisitions.values.mapTo(HashSet(), ::verificationKey)
        val retained = value.filterKeys { it in current }
        val json = buildJsonObject {
            put("version", 1)
            put("verified", buildJsonObject { retained.forEach { (key, stamp) -> put(key, buildJsonObject {
                put("path", stamp.path); put("size", stamp.size); put("modified", stamp.modified)
                put("created", stamp.created); put("fileKey", stamp.fileKey)
            }) } })
        }.toString()
        writeAtomic(verificationFile, json)
    }

    @Synchronized fun save(connection: RemoteLibraryConnection) {
        LibraryUrlPolicy.forConnection(connection)
        val old = connections[connection.id]
        require(old == null || sameScope(old, connection)) { "Changing the server URL or account requires a new connection" }
        require(connections.size < MAX_CONNECTIONS || old != null) { "Too many library connections" }
        val next = LinkedHashMap(connections).apply { put(connection.id, connection) }
        persist(next.values, acquisitions.values)
        connections = next
    }

    @Synchronized fun record(record: LibraryAcquisitionRecord) {
        validateRecord(record)
        require(record.connectionId in connections) { "Unknown acquired library connection" }
        val next = LinkedHashMap(acquisitions).apply { put(acquisitionKey(record), record) }
        if (next.size > MAX_ACQUISITIONS) throw LibraryLimitException("Too many saved library acquisition references")
        persist(connections.values, next.values)
        acquisitions = next
        indexLocals()
    }

    @Synchronized fun exportTransfer(): String = encode(connections.values, acquisitions.values)

    /** Missing section is handled by the caller as no update. UUID/scope conflicts are never rebound. */
    @Synchronized fun previewTransfer(value: String): LibraryTransferPreview {
        val (incomingConnections, incomingAcquisitions) = decode(value)
        val conflicts = ArrayList<String>()
        val additions = ArrayList<RemoteLibraryConnection>()
        for (incoming in incomingConnections) {
            val existing = connections[incoming.id]
            if (existing == null) additions += incoming.copy(enabled = false)
            else if (!sameScope(existing, incoming)) conflicts += incoming.id
        }
        val accepted = (connections.keys + additions.map { it.id }).toSet() - conflicts.toSet()
        val records = incomingAcquisitions.filter { incoming ->
            if (incoming.connectionId !in accepted) false else {
                val old = acquisitions[acquisitionKey(incoming)]
                when {
                    old == null -> true
                    old == incoming -> false
                    else -> { conflicts += "${incoming.connectionId}/${incoming.publicationId}"; false }
                }
            }
        }
        if (connections.size + additions.size > MAX_CONNECTIONS || acquisitions.size + records.size > MAX_ACQUISITIONS) {
            throw LibraryLimitException("Library transfer exceeds local record limits")
        }
        return LibraryTransferPreview(additions, records, conflicts.distinct())
    }

    @Synchronized fun mergeTransfer(value: String, checkCurrent: () -> Unit = {}): LibraryTransferPreview {
        checkCurrent()
        val preview = previewTransfer(value)
        if (preview.conflicts.isNotEmpty()) throw RemoteLibraryException(
            "Library restore has ${preview.conflicts.size} conflicting connection/acquisition identities; review them before restoring")
        val nextConnections = LinkedHashMap(connections).apply { preview.connectionsToAdd.forEach { put(it.id, it) } }
        val nextAcquisitions = LinkedHashMap(acquisitions).apply { preview.acquisitionsToAdd.forEach { put(acquisitionKey(it), it) } }
        persist(nextConnections.values, nextAcquisitions.values, checkCurrent)
        connections = nextConnections
        acquisitions = nextAcquisitions
        indexLocals()
        return preview
    }

    private fun indexLocals() {
        localAcquisitions = HashMap<Pair<Long, String>, LibraryAcquisitionRecord>().apply {
            acquisitions.values.forEach { put(it.localMangaId to it.localUrl, it) }
        }
    }

    private fun persist(connections: Collection<RemoteLibraryConnection>, records: Collection<LibraryAcquisitionRecord>,
        checkCurrent: () -> Unit = {}) {
        writeAtomic(file, encode(connections, records), checkCurrent)
    }

    private fun writeAtomic(target: File, json: String, checkCurrent: () -> Unit = {}) {
        val value = json.toByteArray(Charsets.UTF_8)
        if (value.size > MAX_STORE_BYTES) throw LibraryLimitException("Library connection metadata is too large")
        val parent = target.parentFile ?: throw IOException("Invalid library storage path")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Library connection storage is unavailable")
        val stage = File(parent, ".connections-${UUID.randomUUID()}")
        try {
            stage.outputStream().use { it.write(value); it.fd.sync() }
            checkCurrent()
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }

    companion object {
        private const val MAX_CONNECTIONS = 32
        private const val MAX_ACQUISITIONS = 10_000
        private const val MAX_STORE_BYTES = 16 * 1024 * 1024

        internal fun sameScope(first: RemoteLibraryConnection, second: RemoteLibraryConnection): Boolean =
            first.id == second.id && first.catalogUrl == second.catalogUrl && first.scopeUrl == second.scopeUrl &&
                first.account == second.account && first.kind == second.kind

        private fun acquisitionKey(record: LibraryAcquisitionRecord): String = "${record.connectionId}\u0000${record.publicationId}\u0000${record.sha256}"

        private fun encode(connections: Collection<RemoteLibraryConnection>, records: Collection<LibraryAcquisitionRecord>): String = buildJsonObject {
            put("version", 1)
            put("connections", JsonArray(connections.map { connection -> buildJsonObject {
                put("id", connection.id); put("name", connection.name); put("catalogUrl", connection.catalogUrl)
                put("scopeUrl", connection.scopeUrl); put("kind", connection.kind.name); put("account", connection.account)
                put("content", connection.content.name); put("enabled", connection.enabled)
                put("allowCleartextLan", connection.allowCleartextLan)
            } }))
            put("acquisitions", JsonArray(records.map { record -> buildJsonObject {
                put("connectionId", record.connectionId); put("publicationId", record.publicationId)
                put("localMangaId", record.localMangaId); put("localUrl", record.localUrl)
                put("sha256", record.sha256); put("format", record.format.name)
                record.metadata?.let { metadata -> put("metadata", buildJsonObject {
                    put("title", metadata.title)
                    put("authors", JsonArray(metadata.authors.map(::JsonPrimitive)))
                    put("subjects", JsonArray(metadata.subjects.map(::JsonPrimitive)))
                    metadata.description?.let { put("description", it) }
                    put("content", metadata.content.name)
                }) }
            } }))
        }.toString()

        private fun decode(value: String): Pair<List<RemoteLibraryConnection>, List<LibraryAcquisitionRecord>> {
            if (value.toByteArray(Charsets.UTF_8).size > MAX_STORE_BYTES) throw LibraryLimitException("Library transfer is too large")
            // This transfer is shallow and data-only. Bound nesting before invoking the JSON parser.
            var depth = 0; var quoted = false; var escaped = false
            for (char in value) {
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> if (++depth > 8) throw RemoteLibraryException("Invalid library transfer nesting"); '}', ']' -> depth-- }
            }
            val root = Json.parseToJsonElement(value) as? JsonObject ?: throw RemoteLibraryException("Invalid library transfer")
            if ((root["version"] as? JsonPrimitive)?.intOrNull != 1) throw LibraryUnsupportedException("Unsupported library transfer version")
            val connectionArray = root["connections"] as? JsonArray ?: throw RemoteLibraryException("Library connections section is missing")
            val recordArray = root["acquisitions"] as? JsonArray ?: throw RemoteLibraryException("Library acquisition section is missing")
            if (connectionArray.size > MAX_CONNECTIONS || recordArray.size > MAX_ACQUISITIONS) throw LibraryLimitException("Library transfer exceeds record limits")
            val connections = connectionArray.map { element ->
                val item = element as? JsonObject ?: throw RemoteLibraryException("Invalid library connection")
                RemoteLibraryConnection(item.required("id"), item.required("name"), item.required("catalogUrl"),
                    item.required("scopeUrl"), RemoteLibraryKind.valueOf(item.required("kind")), item.required("account", true),
                    RemoteLibraryContent.valueOf(item.required("content")), (item["enabled"] as? JsonPrimitive)?.booleanOrNull
                        ?: throw RemoteLibraryException("Library connection enablement is missing"),
                    (item["allowCleartextLan"] as? JsonPrimitive)?.booleanOrNull ?: false).also { LibraryUrlPolicy.forConnection(it) }
            }
            if (connections.map { it.id }.toSet().size != connections.size) throw RemoteLibraryException("Duplicate library connection identities")
            val records = recordArray.map { element ->
                val item = element as? JsonObject ?: throw RemoteLibraryException("Invalid library acquisition")
                LibraryAcquisitionRecord(item.required("connectionId"), item.required("publicationId"),
                    (item["localMangaId"] as? JsonPrimitive)?.longOrNull ?: throw RemoteLibraryException("Invalid local acquisition identity"),
                    item.required("localUrl"), item.required("sha256"), LibraryFormat.valueOf(item.required("format")),
                    (item["metadata"] as? JsonObject)?.let { metadata -> LibraryAcquiredMetadata(metadata.required("title"),
                        metadata.strings("authors"), metadata.strings("subjects"), (metadata["description"] as? JsonPrimitive)?.contentOrNull,
                        RemoteLibraryContent.valueOf(metadata.required("content"))) }).also(::validateRecord)
            }
            if (records.map(::acquisitionKey).toSet().size != records.size || records.any { record -> connections.none { it.id == record.connectionId } }) {
                throw RemoteLibraryException("Invalid or duplicated acquisition references")
            }
            return connections to records
        }

        private fun validateRecord(record: LibraryAcquisitionRecord) {
            require(UUID.fromString(record.connectionId).toString() == record.connectionId)
            require(record.publicationId.isNotBlank() && record.publicationId.length <= 4096 && record.localMangaId != 0L)
            require(Regex("[a-f0-9]{64}").matches(record.sha256))
            require(record.localUrl.length <= 8192 && record.localUrl.startsWith("file:///"))
            record.metadata?.let { metadata ->
                require(metadata.title.isNotBlank() && metadata.title.length <= 1000 && metadata.authors.size <= 100 && metadata.subjects.size <= 100)
                require(metadata.authors.all { it.length <= 500 } && metadata.subjects.all { it.length <= 200 } && (metadata.description?.length ?: 0) <= 16_384)
            }
        }
        private fun readBounded(file: File): String {
            if (file.length() !in 1..MAX_STORE_BYTES.toLong()) throw LibraryLimitException("Library connection storage size is invalid")
            return file.inputStream().use { input ->
                val bytes = input.readBytesLimited(MAX_STORE_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
        }
        private fun JsonObject.required(key: String, allowEmpty: Boolean = false): String = (this[key] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.length <= 8192 && (allowEmpty || it.isNotBlank()) } ?: throw RemoteLibraryException("Invalid library field: $key")
        private fun JsonObject.strings(key: String): Set<String> = (this[key] as? JsonArray)?.mapTo(LinkedHashSet()) {
            (it as? JsonPrimitive)?.contentOrNull ?: throw RemoteLibraryException("Invalid acquired metadata")
        } ?: throw RemoteLibraryException("Missing acquired metadata")
    }
}

internal fun java.io.InputStream.readBytesLimited(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() > maximum - count) throw LibraryLimitException("Library input exceeds safe size limits")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
