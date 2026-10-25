package org.koitharu.kotatsu.backups.webdav

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.scrobbling.v2.KeystoreTrackerCredentialVault
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface WebDavOwnershipJournal {
    suspend fun read(connection: WebDavConnection): List<WebDavOwnedBackup>
    suspend fun write(connection: WebDavConnection, records: List<WebDavOwnedBackup>)
}

data class WebDavOwnedBackup(
    val name: String,
    val stagingName: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val verifiedEtag: String? = null,
    val published: Boolean = false,
    val sequence: Long = createdAt,
) {
    init {
        require(WebDavEndpoint.isNativeBackup(name) && WebDavEndpoint.validName(stagingName))
        require(stagingName == ".$name.part")
        require(size in 1..WebDavTransport.MAX_BACKUP_BYTES && createdAt > 0)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(sequence > 0)
        require(verifiedEtag == null || verifiedEtag.length <= 1024 && verifiedEtag.none { it == '\r' || it == '\n' })
    }
}

/** Credentials, scheduling and ownership are device-local; no ordinary settings key or backup section is used. */
@Singleton
class WebDavConfigurationStore @Inject constructor(@ApplicationContext context: Context) : WebDavOwnershipJournal {
    private val vault = KeystoreTrackerCredentialVault(context)
    private val root = File(context.noBackupFilesDir, "webdav_ownership")
    private val mutex = Mutex()
    private val revision = MutableStateFlow<String?>(null)
    val changes: StateFlow<String?> = revision

    suspend fun connection(): WebDavConnection? = mutex.withLock {
        readConnection().also { revision.value = it?.revision }
    }

    suspend fun save(connection: WebDavConnection) = mutex.withLock {
        val previous = readConnection()
        require(previous == null || previous.id != connection.id ||
            previous.endpoint.toString() == connection.endpoint.toString() && previous.username == connection.username) {
            "A changed WebDAV endpoint or account requires a new connection identity"
        }
        val json = JSONObject().put("version", 1).put("id", connection.id).put("revision", connection.revision)
            .put("name", connection.displayName).put("endpoint", connection.endpoint.toString())
            .put("username", connection.username).put("password", connection.password)
            .put("cleartext", connection.allowCleartextLan).put("schedule", connection.scheduleHours)
            .put("wifi", connection.wifiOnly).put("retention", connection.keepBackups)
        vault.write(VAULT_KEY, json.toString())
        revision.value = connection.revision
    }

    suspend fun disconnect() = mutex.withLock {
        vault.clear(VAULT_KEY)
        revision.value = null
    }

    suspend fun requireCurrent(connection: WebDavConnection) = mutex.withLock { checkCurrent(connection) }

    suspend fun remainingCooldown(connection: WebDavConnection, now: Long): Long = mutex.withLock {
        checkCurrent(connection)
        withContext(Dispatchers.IO) {
            val file = AtomicFile(File(root, "${connection.id}.cooldown"))
            if (!file.baseFile.exists()) 0L else {
                val until = file.openRead().use { it.readBytesBounded(32).toString(Charsets.UTF_8).toLongOrNull() }
                    ?: throw IOException("Invalid WebDAV cooldown record")
                (until - now).coerceIn(0L, 7L * 24 * 3600 * 1000)
            }
        }
    }

    suspend fun recordCooldown(connection: WebDavConnection, until: Long) = mutex.withLock {
        checkCurrent(connection)
        withContext(Dispatchers.IO) {
            check(root.isDirectory || root.mkdirs())
            val file = AtomicFile(File(root, "${connection.id}.cooldown"))
            var output: java.io.FileOutputStream? = null
            try {
                output = file.startWrite()
                output.write(until.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        }
    }

    override suspend fun read(connection: WebDavConnection): List<WebDavOwnedBackup> = mutex.withLock {
        checkCurrent(connection)
        withContext(Dispatchers.IO) {
            val file = journalFile(connection)
            if (!file.baseFile.exists()) return@withContext emptyList()
            val bytes = file.openRead().use { it.readBytesBounded(MAX_JOURNAL_BYTES) }
            try {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                check(json.getInt("version") == 1 && json.getString("owner") == connection.id)
                val records = json.getJSONArray("records")
                check(records.length() <= MAX_RECORDS)
                List(records.length()) { i ->
                    val entry = records.getJSONObject(i)
                    WebDavOwnedBackup(entry.getString("name"), entry.getString("staging"), entry.getLong("size"),
                        entry.getString("hash"), entry.getLong("created"),
                        entry.optString("etag").takeIf(String::isNotEmpty), entry.getBoolean("published"),
                        entry.optLong("sequence", entry.getLong("created")))
                }.also { recordsList -> check(recordsList.map { it.name }.distinct().size == recordsList.size) }
            } catch (e: Exception) {
                throw IOException("WebDAV ownership journal is invalid; automatic deletion is disabled", e)
            }
        }
    }

    override suspend fun write(connection: WebDavConnection, records: List<WebDavOwnedBackup>) = mutex.withLock {
        checkCurrent(connection)
        require(records.size <= MAX_RECORDS && records.map { it.name }.distinct().size == records.size)
        withContext(Dispatchers.IO) {
            check(root.isDirectory || root.mkdirs()) { "Private WebDAV storage is unavailable" }
            val values = JSONArray()
            records.forEach { entry ->
                values.put(JSONObject().put("name", entry.name).put("staging", entry.stagingName).put("size", entry.size)
                    .put("hash", entry.sha256).put("created", entry.createdAt).put("published", entry.published)
                    .put("sequence", entry.sequence)
                    .apply { entry.verifiedEtag?.let { put("etag", it) } })
            }
            val bytes = JSONObject().put("version", 1).put("owner", connection.id).put("records", values)
                .toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_JOURNAL_BYTES)
            val file = journalFile(connection)
            var output: java.io.FileOutputStream? = null
            try {
                output = file.startWrite()
                output.write(bytes)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        }
    }

    private suspend fun checkCurrent(connection: WebDavConnection) {
        if (readConnection()?.revision != connection.revision) throw CancellationException("WebDAV connection changed")
    }

    private suspend fun readConnection(): WebDavConnection? {
        val envelope = vault.read(VAULT_KEY) ?: return null
        return try {
            val data = JSONObject(envelope)
            check(data.getInt("version") == 1)
            val cleartext = data.optBoolean("cleartext", false)
            WebDavConnection(data.getString("id"), data.getString("revision"), data.getString("name"),
                WebDavEndpoint.parse(data.getString("endpoint"), cleartext), data.getString("username"),
                data.getString("password"), cleartext, data.optInt("schedule", 0), data.optBoolean("wifi", true),
                data.optInt("retention", 0))
        } catch (_: Exception) {
            // Parser diagnostics may quote input; never attach the protected envelope to an error chain.
            throw IOException("Protected WebDAV configuration is invalid; configure the connection again")
        }
    }

    private fun journalFile(connection: WebDavConnection) = AtomicFile(File(root, "${connection.id}.json"))

    companion object {
        const val MAX_RECORDS = 512
        private const val MAX_JOURNAL_BYTES = 512 * 1024
        private const val VAULT_KEY = "WEBDAV_NATIVE_BACKUPS"
    }
}

internal fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maximum + 1 - total))
        if (count < 0) break
        total += count
        if (total > maximum) throw IOException("Private WebDAV record exceeds its size limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
