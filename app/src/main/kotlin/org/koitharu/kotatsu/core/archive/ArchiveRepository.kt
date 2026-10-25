package org.koitharu.kotatsu.core.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore

/** Read-only archive access shared by local books, pages and their derived-content consumers. */
class ArchiveRepository(
    val sessions: ArchiveSessionManager,
    private val entryStore: ArchiveEntryStore,
) {
    private val unlocks = Semaphore(2, true)

    fun entries(file: File): List<ArchiveEntryMetadata> = inspectEntries(file)

    fun isSensitive(file: File): Boolean = entries(file).any { it.isEncrypted }

    /** The caller retains its array. UI integration must clear it when this method returns. */
    fun unlock(file: File, password: CharArray, checkCancelled: () -> Unit = ::checkArchiveInterrupted) {
        if (password.isEmpty()) throw ArchiveAccessException.WrongPassword()
        val copy = password.copyOf()
        unlocks.acquire()
        try {
            translateZipErrors {
                checkDirectoryBounds(file)
                val fingerprint = ArchiveSessionManager.Fingerprint.of(file)
                ZipFile(file, copy).use { zip ->
                    val encrypted = headers(zip).filter { it.isEncrypted && !it.isDirectory }
                        .minByOrNull { it.uncompressedSize }
                        ?: throw ArchiveAccessException.Invalid("This archive has no encrypted file entries")
                    if (encrypted.uncompressedSize > ArchiveEntryStore.MAX_ENTRY_BYTES) {
                        throw ArchiveAccessException.Limit("Archive entry exceeds the password-validation limit")
                    }
                    zip.getInputStream(encrypted).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        try {
                            var size = 0L
                            while (true) {
                                checkCancelled()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) throw IOException("Archive entry stream made no progress")
                                size += read
                                if (size > encrypted.uncompressedSize) {
                                    throw ArchiveAccessException.Invalid("Archive entry is larger than its header")
                                }
                            }
                            if (size != encrypted.uncompressedSize) {
                                throw ArchiveAccessException.Invalid("Archive entry is smaller than its header")
                            }
                        } finally { buffer.fill(0) }
                    }
                }
                checkCancelled()
                if (fingerprint != ArchiveSessionManager.Fingerprint.of(file)) {
                    throw ArchiveAccessException.Invalid("Archive changed while it was being unlocked")
                }
                sessions.remember(file, copy)
            }
        } finally { copy.fill('\u0000'); unlocks.release() }
    }

    fun <T> withEntry(
        file: File,
        name: String,
        limit: Long = ArchiveEntryStore.MAX_ENTRY_BYTES,
        checkCancelled: () -> Unit = ::checkArchiveInterrupted,
        block: (InputStream) -> T,
    ): T = translateZipErrors {
        validateArchivePath(name)
        val metadata = entries(file)
        val entry = metadata.singleOrNull { it.name == name && !it.isDirectory }
            ?: throw ArchiveAccessException.Invalid("Archive entry is missing")
        if (entry.size < 0 || entry.size > limit) throw ArchiveAccessException.Limit("Archive entry exceeds its read limit")
        val token = if (metadata.any { it.isEncrypted }) sessions.token(file) else null
        val password = token?.let(sessions::password)
        try {
            ZipFile(file, password).use { zip ->
                val header = headers(zip).single { it.fileName == name }
                zip.getInputStream(header).use { input ->
                    val stream = GuardedInput(input, entry.size) {
                        checkCancelled()
                        if (token != null) sessions.requireActive(token)
                    }
                    // Registration makes session lock close an in-progress decompressor immediately.
                    if (token != null) sessions.register(token, stream)
                    try {
                        val result = block(stream)
                        // A caller may only inspect a header. Consume to authenticated EOF before
                        // accepting its result; encrypted ZIP integrity checks happen at entry end.
                        if (entry.isEncrypted) {
                            val remaining = ByteArray(64 * 1024)
                            try { while (stream.read(remaining) >= 0) remaining.fill(0) } finally { remaining.fill(0) }
                        }
                        stream.checkActive()
                        result
                    } finally {
                        if (token != null) sessions.unregister(token, stream)
                        stream.close()
                    }
                }
            }
        } finally { password?.fill('\u0000') }
    }

    fun readBytes(file: File, name: String, limit: Long, checkCancelled: () -> Unit = ::checkArchiveInterrupted): ByteArray =
        withEntry(file, name, limit, checkCancelled) { input ->
            // All callers supplying a byte-array limit are metadata/text consumers, not whole archives.
            if (limit > Int.MAX_VALUE) throw ArchiveAccessException.Limit("Archive byte-array read limit is too large")
            input.readBytes()
        }

    fun openSeekable(file: File, name: String, checkCancelled: () -> Unit = ::checkArchiveInterrupted): SeekableArchiveEntry {
        val metadata = entries(file)
        val entry = metadata.singleOrNull { it.name == name && !it.isDirectory }
            ?: throw ArchiveAccessException.Invalid("Archive entry is missing")
        val token = if (metadata.any { it.isEncrypted }) sessions.token(file) else null
        val active = {
            checkCancelled()
            if (token != null) sessions.requireActive(token)
        }
        val stored = withEntry(file, name, checkCancelled = checkCancelled) { input ->
            entryStore.prepare(input, entry.size, active)
        }
        if (token == null) return stored
        val guarded = object : SeekableArchiveEntry {
            override val size: Long = stored.size
            override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int =
                stored.readAt(position, target, offset, length)
            override fun close() {
                sessions.unregister(token, this)
                stored.close()
            }
        }
        sessions.register(token, guarded)
        return guarded
    }

    private class GuardedInput(input: InputStream, private val size: Long, val checkActive: () -> Unit) :
        FilterInputStream(input) {
        private var count = 0L
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkActive()
            if (closed) throw ArchiveAccessException.Locked()
            if (len == 0) return 0
            val read = `in`.read(b, off, len)
            if (read > 0) {
                count += read
                if (count > size) { b.fill(0, off, off + read); throw ArchiveAccessException.Invalid("Archive entry size changed") }
            } else if (read == 0) {
                throw IOException("Archive entry stream made no progress")
            } else if (count != size) {
                throw ArchiveAccessException.Invalid("Archive entry is smaller than its header")
            }
            checkActive()
            return read
        }

        override fun skip(n: Long): Long {
            val buffer = ByteArray(8192)
            try {
                var skipped = 0L
                while (skipped < n) {
                    val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                    if (read < 0) break
                    skipped += read
                }
                return skipped
            } finally { buffer.fill(0) }
        }

        override fun close() { closed = true; super.close() }
    }

    companion object {
        private const val MAX_ENTRIES = 20_000
        private const val MAX_DIRECTORY_BYTES = 16L * 1024 * 1024

        /** Public ZIP directory metadata requires neither an Android context nor a password session. */
        fun inspectEntries(file: File): List<ArchiveEntryMetadata> = translateZipErrors {
            checkArchiveInterrupted()
            checkDirectoryBounds(file)
            ZipFile(file).use { zip -> headers(zip).map { it.metadata() } }
        }

        private fun headers(zip: ZipFile): List<FileHeader> {
            if (zip.isSplitArchive) throw ArchiveAccessException.Invalid("Split ZIP archives are not supported")
            val headers = zip.fileHeaders
            if (headers.size > MAX_ENTRIES) throw ArchiveAccessException.Limit("Archive has too many entries")
            val paths = HashSet<String>()
            for (header in headers) {
                val path = validateArchivePath(header.fileName)
                if (!paths.add(path)) throw ArchiveAccessException.Invalid("Archive has duplicate entry names")
                if (header.uncompressedSize < 0 || header.compressedSize < 0) {
                    throw ArchiveAccessException.Invalid("Archive contains invalid entry sizes")
                }
                val compression = header.aesExtraDataRecord?.compressionMethod ?: header.compressionMethod
                if (compression != CompressionMethod.STORE && compression != CompressionMethod.DEFLATE) {
                    throw ArchiveAccessException.Invalid("Archive compression method is not supported")
                }
            }
            return headers
        }

        private fun FileHeader.metadata() = ArchiveEntryMetadata(
            fileName, uncompressedSize, compressedSize, isDirectory, isEncrypted,
        )

        /** Cap central-directory allocations before handing the file to Zip4j; ZIP64 remains supported. */
        private fun checkDirectoryBounds(file: File) {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                if (length < 22) throw ArchiveAccessException.Invalid("ZIP archive is incomplete")
                val size = minOf(length, 65_557L).toInt()
                val end = ByteArray(size)
                input.seek(length - size)
                input.readFully(end)
                val buffer = ByteBuffer.wrap(end).order(ByteOrder.LITTLE_ENDIAN)
                val offset = (size - 22 downTo 0).firstOrNull { i ->
                    buffer.getInt(i) == 0x06054b50 && i + 22 + (buffer.getShort(i + 20).toInt() and 65535) == size
                } ?: throw ArchiveAccessException.Invalid("ZIP directory is missing")
                if (buffer.getShort(offset + 4).toInt() != 0 || buffer.getShort(offset + 6).toInt() != 0) {
                    throw ArchiveAccessException.Invalid("Split ZIP archives are not supported")
                }
                var entries = buffer.getShort(offset + 10).toLong() and 65535L
                var directoryBytes = buffer.getInt(offset + 12).toLong() and 0xffffffffL
                if (entries == 65535L || directoryBytes == 0xffffffffL) {
                    val locatorOffset = length - size + offset - 20
                    if (locatorOffset < 0) throw ArchiveAccessException.Invalid("ZIP64 directory locator is missing")
                    val locator = ByteArray(20)
                    input.seek(locatorOffset); input.readFully(locator)
                    val loc = ByteBuffer.wrap(locator).order(ByteOrder.LITTLE_ENDIAN)
                    if (loc.getInt(0) != 0x07064b50 || loc.getInt(4) != 0 || loc.getInt(16) != 1) {
                        throw ArchiveAccessException.Invalid("Invalid or split ZIP64 archive")
                    }
                    val recordOffset = loc.getLong(8)
                    if (recordOffset < 0 || recordOffset > length - 56) throw ArchiveAccessException.Invalid("Invalid ZIP64 directory")
                    val record = ByteArray(56)
                    input.seek(recordOffset); input.readFully(record)
                    val zip64 = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
                    if (zip64.getInt(0) != 0x06064b50 || zip64.getInt(16) != 0 || zip64.getInt(20) != 0) {
                        throw ArchiveAccessException.Invalid("Invalid or split ZIP64 archive")
                    }
                    entries = zip64.getLong(32)
                    directoryBytes = zip64.getLong(40)
                }
                if (entries < 0 || entries > MAX_ENTRIES || directoryBytes < 0 || directoryBytes > MAX_DIRECTORY_BYTES) {
                    throw ArchiveAccessException.Limit("ZIP directory exceeds safe archive limits")
                }
            }
        }

        private inline fun <T> translateZipErrors(block: () -> T): T = try { block() } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD) throw ArchiveAccessException.WrongPassword(e)
            throw ArchiveAccessException.Invalid("Cannot read ZIP archive: ${e.message.orEmpty()}", e)
        }
    }
}
