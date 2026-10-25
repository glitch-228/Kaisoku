package org.koitharu.kotatsu.core.archive

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

data class ArchiveEntryMetadata(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val isEncrypted: Boolean,
)

sealed class ArchiveAccessException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Locked : ArchiveAccessException("This archive is locked. Unlock it for this session to continue.")
    class WrongPassword(cause: Throwable? = null) : ArchiveAccessException("The archive password is incorrect.", cause)
    class Invalid(message: String, cause: Throwable? = null) : ArchiveAccessException(message, cause)
    class Limit(message: String) : ArchiveAccessException(message)
}

/** A read-only, revocable source. No implementation exposes a decrypted disk path. */
interface SeekableArchiveEntry : Closeable {
    val size: Long

    /** Returns -1 at end of input. Position is absolute and reads never modify source geometry. */
    fun readAt(position: Long, target: ByteArray, offset: Int = 0, length: Int = target.size - offset): Int

    fun openStream(): InputStream = object : InputStream() {
        private var position = 0L
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Archive stream is closed")
            return readAt(position, b, off, len).also { if (it > 0) position += it }
        }

        override fun skip(n: Long): Long {
            if (closed) throw IOException("Archive stream is closed")
            val skipped = n.coerceIn(0, (size - position).coerceAtLeast(0))
            position += skipped
            return skipped
        }

        override fun available(): Int = (size - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        // Closing a view does not close the caller-owned seekable handle.
        override fun close() { closed = true }
    }
}

internal fun checkArchiveInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Archive operation cancelled")
}

internal fun validateReadArguments(position: Long, target: ByteArray, offset: Int, length: Int) {
    require(position >= 0) { "Negative archive position" }
    require(offset >= 0 && length >= 0 && offset <= target.size - length) { "Invalid archive read bounds" }
}

/** Reject ambiguity instead of normalizing two different archive names into one output. */
internal fun validateArchivePath(path: String): String {
    if (path.isEmpty() || path.startsWith('/') || path.startsWith('\\') || '\u0000' in path || '\\' in path ||
        path.substringBefore('/').contains(':') || path.split('/').any { it == ".." || it == "." }) {
        throw ArchiveAccessException.Invalid("Archive contains an unsafe entry path")
    }
    if (path.removeSuffix("/").split('/').any { it.isEmpty() }) {
        throw ArchiveAccessException.Invalid("Archive contains an ambiguous entry path")
    }
    return path
}
