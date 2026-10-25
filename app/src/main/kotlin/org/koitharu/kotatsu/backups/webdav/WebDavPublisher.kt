package org.koitharu.kotatsu.backups.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class WebDavUploadResult(val backup: WebDavResource, val retainedWarning: String? = null)

/** Journal-before-write, staged publication, readback and conditional, installation-owned retention. */
class WebDavPublisher(
    private val connection: WebDavConnection,
    private val transport: WebDavTransport,
    private val journal: WebDavOwnershipJournal,
    private val clock: () -> Long = System::currentTimeMillis,
    private val token: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun upload(file: File): WebDavUploadResult {
        require(file.isFile && file.length() in 1..WebDavTransport.MAX_BACKUP_BYTES) { "Backup is empty or too large" }
        reconcilePending()
        val createdAt = clock()
        val name = "kaisoku_${STAMP.format(Instant.ofEpochMilli(createdAt))}_${token()}.bk.zip"
        require(WebDavEndpoint.isNativeBackup(name))
        val stage = ".$name.part"
        // In addition to conditional PUT/MOVE, check names before recording ownership or attempting a write.
        if (transport.stat(stage) != null || transport.stat(name) != null) throw IOException("WebDAV filename collision")
        val previousSequence = journal.read(connection).maxOfOrNull { it.sequence } ?: 0L
        check(previousSequence < Long.MAX_VALUE) { "WebDAV ownership sequence is exhausted" }
        val record = WebDavOwnedBackup(name, stage, file.length(), hashFile(file), createdAt,
            sequence = previousSequence + 1L)
        updateJournal { (it + record).boundedJournal() }

        try {
            transport.put(stage, file)
        } catch (e: IOException) {
            // PUT is never repeated. A known rejection did not grant ownership of an existing remote object.
            if (e is WebDavFailure && e.status in 400..499 && e.status != 408 && e.status != 429) {
                updateJournal { it.filterNot { previous -> previous.name == name } }
                throw e
            }
            throw WebDavPublicationUncertain()
        }
        val staged = verify(record, stage) ?: throw WebDavPublicationUncertain()
        var moveFailure: IOException? = null
        try {
            transport.move(stage, name)
        } catch (e: IOException) {
            moveFailure = e
        }
        if (moveFailure is WebDavFailure && moveFailure.status == 412) {
            // A rejected destination collision never grants ownership, even if another file has identical bytes.
            try {
                removeUnchangedStage(staged, record)
            } finally {
                updateJournal { values -> values.filterNot { it.name == name } }
            }
            throw moveFailure
        }
        val final = try {
            verify(record, name)
        } catch (_: IOException) {
            null
        }
        if (final == null) {
            // An explicit rejection plus an unchanged staging object is safe to abandon conditionally.
            // A timeout/unknown result stays journaled and must be reconciled before another upload.
            val knownRejection = moveFailure is WebDavFailure &&
                (moveFailure.status in 400..499 && moveFailure.status != 408 && moveFailure.status != 429 ||
                    moveFailure.status == 501)
            if (knownRejection && transport.stat(name) == null && removeUnchangedStage(staged, record)) {
                updateJournal { it.filterNot { previous -> previous.name == name } }
                throw checkNotNull(moveFailure)
            }
            throw WebDavPublicationUncertain()
        }
        updateJournal { values -> values.map { if (it.name == name) it.copy(published = true,
            verifiedEtag = final.strongEtag) else it } }
        val warning = try {
            retain()
            null
        } catch (e: IOException) {
            // Publication already succeeded. Never retry that upload because a separate retention check failed.
            "The backup was uploaded; retention could not finish (${e.javaClass.simpleName})."
        }
        return WebDavUploadResult(final, warning)
    }

    suspend fun reconcilePending() {
        val pending = journal.read(connection).filterNot { it.published }
        if (pending.size > 8) throw WebDavPublicationUncertain()
        for (record in pending) {
            currentCoroutineContext().ensureActive()
            val final = verify(record, record.name)
            if (final != null) {
                updateJournal { values -> values.map { if (it.name == record.name) it.copy(published = true,
                    verifiedEtag = final.strongEtag) else it } }
                continue
            }
            // A different final object is not ours, even when it happens to use the same name.
            if (transport.stat(record.name) != null) throw WebDavPublicationUncertain()
            val stage = transport.stat(record.stagingName)
            if (stage == null || removeUnchangedStage(stage, record)) {
                updateJournal { values -> values.filterNot { it.name == record.name } }
            } else {
                throw WebDavPublicationUncertain()
            }
        }
    }

    /** Prefixes and matching bytes alone never authorize deletion; strong ETags also prevent a stat/delete race. */
    suspend fun retain() {
        if (connection.keepBackups == 0) return
        val records = journal.read(connection).filter { it.published }.sortedWith(
            compareByDescending<WebDavOwnedBackup> { it.sequence }.thenByDescending { it.name },
        )
        for (record in records.drop(connection.keepBackups.coerceAtLeast(1)).take(32)) {
            currentCoroutineContext().ensureActive()
            val expected = record.verifiedEtag ?: continue
            val resource = transport.stat(record.name)
            if (resource == null) {
                updateJournal { values -> values.filterNot { it.name == record.name } }
                continue
            }
            if (resource.size != record.size || resource.strongEtag != expected) continue
            if (transport.hash(resource) != record.sha256) continue
            if (transport.deleteVerified(resource)) updateJournal { values -> values.filterNot { it.name == record.name } }
        }
    }

    private suspend fun verify(record: WebDavOwnedBackup, name: String): WebDavResource? {
        val remote = transport.stat(name) ?: return null
        if (remote.size != record.size) return null
        return remote.takeIf { transport.hash(it) == record.sha256 }
    }

    private suspend fun removeUnchangedStage(stage: WebDavResource, record: WebDavOwnedBackup): Boolean {
        if (stage.name != record.stagingName || stage.size != record.size || stage.strongEtag == null) return false
        val current = transport.stat(stage.name) ?: return true
        if (current != stage || transport.hash(current) != record.sha256) return false
        return transport.deleteVerified(current)
    }

    private suspend fun updateJournal(change: (List<WebDavOwnedBackup>) -> List<WebDavOwnedBackup>) {
        currentCoroutineContext().ensureActive()
        journal.write(connection, change(journal.read(connection)))
    }

    private fun List<WebDavOwnedBackup>.boundedJournal(): List<WebDavOwnedBackup> {
        val pending = filterNot { it.published }
        check(pending.size <= 8) { "Too many unresolved WebDAV uploads; refresh the connection first" }
        return pending + filter { it.published }.sortedByDescending { it.sequence }
            .take(WebDavConfigurationStore.MAX_RECORDS - pending.size)
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        internal suspend fun hashFile(file: File): String = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
