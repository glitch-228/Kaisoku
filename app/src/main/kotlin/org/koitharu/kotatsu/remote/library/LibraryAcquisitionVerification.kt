package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Device-local evidence. It is deliberately absent from the connection/acquisition transfer format. */
internal data class LibraryAcquisitionVerification(
    val record: LibraryAcquisitionRecord,
    val scope: String,
    val stamp: LibraryAcquisitionFileStamp,
)

internal data class LibraryAcquisitionFileStamp(
    val path: String,
    val size: Long,
    val modified: String,
    val created: String,
    val fileKey: String,
) {
    companion object {
        fun capture(file: File): LibraryAcquisitionFileStamp {
            val path = file.toPath().toRealPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile || attributes.size() > MAX_ACQUIRED_BYTES) {
                throw RemoteLibraryException("This acquired original is unavailable")
            }
            return LibraryAcquisitionFileStamp(path.toString(), attributes.size(), attributes.lastModifiedTime().toString(),
                attributes.creationTime().toString(), attributes.fileKey()?.toString().orEmpty())
        }
    }
}

/** Only explicit acquisition/open calls hash a file. Normal metadata/filtering never hashes the library. */
internal object LibraryAcquisitionVerifier {
    suspend fun verify(record: LibraryAcquisitionRecord, connection: RemoteLibraryConnection): LibraryAcquisitionVerification {
        val file = localFile(record.localUrl)
        val before = LibraryAcquisitionFileStamp.capture(file)
        val digest = MessageDigest.getInstance("SHA-256")
        val context = currentCoroutineContext()
        val started = System.nanoTime()
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                context.ensureActive()
                if (System.nanoTime() - started > TimeUnit.MINUTES.toNanos(5)) {
                    throw LibraryLimitException("Acquired book verification took too long; retry opening it")
                }
                val count = input.read(buffer)
                if (count < 0) break
                if (total > MAX_ACQUIRED_BYTES - count) throw LibraryLimitException("The acquired original is too large")
                total += count
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != record.sha256 || total != before.size || before != LibraryAcquisitionFileStamp.capture(file)) {
            throw RemoteLibraryException("The saved file differs from this library acquisition; choose it explicitly in local storage")
        }
        return LibraryAcquisitionVerification(record, scope(connection), before)
    }

    fun localFile(value: String): File {
        val uri = try { URI(value) } catch (error: java.net.URISyntaxException) {
            throw RemoteLibraryException("Invalid acquired book path", error)
        }
        if (uri.scheme != "file" || !uri.rawAuthority.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
            throw RemoteLibraryException("Invalid acquired book path")
        }
        return File(uri)
    }

    fun scope(connection: RemoteLibraryConnection): String = libraryDigest(connection.cacheScope + "\u0000" + connection.kind.name)
}

private const val MAX_ACQUIRED_BYTES = 512L * 1024 * 1024
