package org.koitharu.kotatsu.backups.webdav

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.backups.data.BackupRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configuration: WebDavConfigurationStore,
    private val backups: BackupRepository,
) {
    private val operation = Mutex()
    private var transportRevision: String? = null
    private var transport: WebDavTransport? = null

    suspend fun list(): List<WebDavResource> = connected { _, dav -> dav.list() }

    suspend fun upload(expectedRevision: String? = null): WebDavUploadResult = connected(expectedRevision) { connection, dav ->
        val file = privateFile("upload")
        try {
            val job = currentCoroutineContext().job
            withContext(Dispatchers.IO) {
                file.outputStream().use { stream ->
                    ZipOutputStream(WebDavBackupFiles.boundedOutput(stream) { job.ensureActive() }).use { zip ->
                        backups.createBackup(zip, null, includeCustomCovers = true)
                    }
                }
            }
            WebDavBackupFiles.validate(file)
            WebDavPublisher(connection, dav, configuration).upload(file)
        } finally {
            // Only the uniquely named local file created by this operation is removed.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    suspend fun download(selected: WebDavResource): Uri = connected { _, dav ->
        require(WebDavEndpoint.isNativeBackup(selected.name)) { "Only native backups can be restored" }
        val current = dav.stat(selected.name) ?: throw IOException("The remote backup is no longer available")
        if (selected.size != current.size || selected.etag != current.etag) {
            throw IOException("The remote backup changed; refresh the list and choose it again")
        }
        val file = privateFile("restore")
        var complete = false
        try {
            dav.download(current, file)
            WebDavBackupFiles.validate(file)
            currentCoroutineContext().ensureActive()
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file).also { complete = true }
        } finally {
            if (!complete) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun privateFile(prefix: String): File = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "webdav_native_backups")
        check(root.isDirectory || root.mkdirs()) { "Private backup staging is unavailable" }
        File(root, "$prefix-${UUID.randomUUID()}.bk.zip").also {
            check(it.createNewFile()) { "Private backup staging file already exists" }
        }
    }

    private suspend fun <T> connected(
        expectedRevision: String? = null,
        block: suspend (WebDavConnection, WebDavTransport) -> T,
    ): T = operation.withLock {
        coroutineScope {
            val connection = configuration.connection() ?: throw IOException("Configure a WebDAV connection first")
            if (expectedRevision != null && connection.revision != expectedRevision) {
                throw CancellationException("The scheduled WebDAV configuration changed")
            }
            val remaining = configuration.remainingCooldown(connection, System.currentTimeMillis())
            if (remaining > 0) throw WebDavFailure("WebDAV is temporarily rate limited", 429, remaining)
            val parent = currentCoroutineContext().job
            val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                configuration.changes.collect { revision ->
                    if (revision != connection.revision) parent.cancel(CancellationException("WebDAV connection changed"))
                }
            }
            try {
                if (transportRevision != connection.revision) {
                    transport = WebDavTransport(connection, ensureCurrent = { configuration.requireCurrent(connection) })
                    transportRevision = connection.revision
                }
                try {
                    withTimeout(8 * 60_000L) { block(connection, checkNotNull(transport)) }
                } catch (e: WebDavFailure) {
                    if (e.retryAfterMillis > 0) configuration.recordCooldown(connection,
                        System.currentTimeMillis() + e.retryAfterMillis)
                    throw e
                }
            } finally {
                monitor.cancel()
            }
        }
    }
}
