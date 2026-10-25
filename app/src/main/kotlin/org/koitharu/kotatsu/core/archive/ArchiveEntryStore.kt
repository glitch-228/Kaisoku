package org.koitharu.kotatsu.core.archive

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bounded seekability for native decoders without a persistent plaintext intermediate. */
class ArchiveEntryStore(
    private val scratchDirectory: File,
    private val memoryLimit: Long = minOf(32L * MIB, Runtime.getRuntime().maxMemory() / 8),
    private val scratchLimit: Long = 1024L * MIB,
) {
    private val memoryBytes = AtomicLong()
    private val scratchBytes = AtomicLong()
    private val preparers = Semaphore(2, true)

    init {
        require(memoryLimit >= 0 && scratchLimit >= 0)
        if (!scratchDirectory.isDirectory && !scratchDirectory.mkdirs()) {
            throw IOException("Cannot create encrypted archive scratch directory")
        }
    }

    /** Invoke once at process startup, before opening any entries; only this component's files are removed. */
    fun clearAbandonedScratch() {
        scratchDirectory.listFiles()?.filter { it.name.startsWith(SCRATCH_PREFIX) && it.name.endsWith(".bin") }
            ?.forEach { file ->
                if (file.canonicalFile.parentFile == scratchDirectory.canonicalFile && file.isFile) file.delete()
            }
    }

    fun prepare(
        input: InputStream,
        size: Long,
        checkActive: () -> Unit = ::checkArchiveInterrupted,
    ): SeekableArchiveEntry {
        if (size < 0 || size > MAX_ENTRY_BYTES) throw ArchiveAccessException.Limit("Archive entry exceeds 512 MiB")
        preparers.acquire()
        try {
            checkActive()
            if (size <= MEMORY_ENTRY_LIMIT && reserve(memoryBytes, size, memoryLimit)) {
                var bytes: ByteArray? = null
                var transferred = false
                try {
                    bytes = ByteArray(size.toInt())
                    readFully(input, bytes, bytes.size, checkActive)
                    if (input.read() != -1) throw ArchiveAccessException.Invalid("Archive entry is larger than its header")
                    checkActive()
                    val handle = MemoryEntry(bytes, checkActive) { memoryBytes.addAndGet(-size) }
                    transferred = true
                    return handle
                } finally {
                    if (!transferred) {
                        bytes?.fill(0)
                        memoryBytes.addAndGet(-size)
                    }
                }
            }
            val chunks = (size + BLOCK_BYTES - 1) / BLOCK_BYTES
            val bytesOnDisk = size + chunks * GCM_TAG_BYTES
            val available = (scratchDirectory.usableSpace - DISK_HEADROOM).coerceAtLeast(0)
            if (!reserve(scratchBytes, bytesOnDisk, minOf(scratchLimit, available))) {
                throw ArchiveAccessException.Limit("Not enough protected scratch space for this archive entry")
            }
            var file: File? = null
            var handle: ScratchEntry? = null
            var transferred = false
            try {
                file = File.createTempFile(SCRATCH_PREFIX, ".bin", scratchDirectory)
                handle = ScratchEntry.create(file, input, size, checkActive) { scratchBytes.addAndGet(-bytesOnDisk) }
                transferred = true
                return handle
            } finally {
                if (!transferred) {
                    if (handle != null) handle.close() else {
                        file?.delete()
                        scratchBytes.addAndGet(-bytesOnDisk)
                    }
                }
            }
        } finally {
            preparers.release()
        }
    }

    internal fun allocatedMemoryBytes(): Long = memoryBytes.get()
    internal fun allocatedScratchBytes(): Long = scratchBytes.get()

    private class MemoryEntry(
        private val bytes: ByteArray,
        private val checkActive: () -> Unit,
        private val release: () -> Unit,
    ) : SeekableArchiveEntry {
        private var closed = false
        override val size = bytes.size.toLong()

        @Synchronized
        override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            validateReadArguments(position, target, offset, length)
            checkActive()
            if (closed) throw ArchiveAccessException.Locked()
            if (length == 0) return 0
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            bytes.copyInto(target, offset, position.toInt(), position.toInt() + count)
            return count
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                closed = true
                bytes.fill(0)
                release()
            }
        }
    }

    private class ScratchEntry(
        private val file: File,
        private val access: RandomAccessFile,
        override val size: Long,
        private val key: ByteArray,
        private val noncePrefix: ByteArray,
        private val checkActive: () -> Unit,
        private val release: () -> Unit,
    ) : SeekableArchiveEntry {
        private var closed = false

        @Synchronized
        override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            validateReadArguments(position, target, offset, length)
            checkActive()
            if (closed) throw ArchiveAccessException.Locked()
            if (length == 0) return 0
            if (position >= size) return -1
            var copied = 0
            val wanted = minOf(length.toLong(), size - position).toInt()
            while (copied < wanted) {
                checkActive()
                val cursor = position + copied
                val block = (cursor / BLOCK_BYTES).toInt()
                val within = (cursor % BLOCK_BYTES).toInt()
                val blockSize = minOf(BLOCK_BYTES.toLong(), size - block.toLong() * BLOCK_BYTES).toInt()
                val encoded = ByteArray(blockSize + GCM_TAG_BYTES)
                var decoded: ByteArray? = null
                try {
                    access.seek(block.toLong() * (BLOCK_BYTES + GCM_TAG_BYTES))
                    access.readFully(encoded)
                    decoded = cipher(Cipher.DECRYPT_MODE, key, noncePrefix, block, size).doFinal(encoded)
                    checkActive()
                    val count = minOf(blockSize - within, wanted - copied)
                    decoded.copyInto(target, offset + copied, within, within + count)
                    copied += count
                } catch (e: java.security.GeneralSecurityException) {
                    throw ArchiveAccessException.Invalid("Protected archive scratch authentication failed", e)
                } finally {
                    encoded.fill(0)
                    decoded?.fill(0)
                }
            }
            return copied
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                closed = true
                key.fill(0)
                noncePrefix.fill(0)
                try { access.close() } finally { file.delete(); release() }
            }
        }

        companion object {
            fun create(file: File, input: InputStream, size: Long, checkActive: () -> Unit,
                       release: () -> Unit): ScratchEntry {
                val random = SecureRandom()
                val key = ByteArray(32).also(random::nextBytes)
                val nonce = ByteArray(8).also(random::nextBytes)
                val buffer = ByteArray(BLOCK_BYTES)
                var access: RandomAccessFile? = null
                var completed = false
                try {
                    access = RandomAccessFile(file, "rw")
                    var remaining = size
                    var block = 0
                    while (remaining > 0) {
                        checkActive()
                        val count = minOf(remaining, BLOCK_BYTES.toLong()).toInt()
                        readFully(input, buffer, count, checkActive)
                        val encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce, block++, size).doFinal(buffer, 0, count)
                        try { access.write(encrypted) } finally { encrypted.fill(0); buffer.fill(0) }
                        remaining -= count
                    }
                    if (input.read() != -1) throw ArchiveAccessException.Invalid("Archive entry is larger than its header")
                    checkActive()
                    completed = true
                    return ScratchEntry(file, access, size, key, nonce, checkActive, release)
                } finally {
                    buffer.fill(0)
                    if (!completed) { key.fill(0); nonce.fill(0); access?.close() }
                }
            }

            private fun cipher(mode: Int, key: ByteArray, noncePrefix: ByteArray, block: Int, size: Long): Cipher {
                val nonce = ByteBuffer.allocate(12).put(noncePrefix).putInt(block).array()
                return Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(ByteBuffer.allocate(12).putLong(size).putInt(block).array())
                }
            }
        }
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val BLOCK_BYTES = 64 * 1024
        private const val GCM_TAG_BYTES = 16
        private const val DISK_HEADROOM = 32L * MIB
        private const val SCRATCH_PREFIX = ".session-entry-"
        const val MEMORY_ENTRY_LIMIT = 8L * MIB
        const val MAX_ENTRY_BYTES = 512L * MIB

        private fun reserve(used: AtomicLong, bytes: Long, limit: Long): Boolean {
            while (true) {
                val current = used.get()
                if (bytes > limit - current) return false
                if (used.compareAndSet(current, current + bytes)) return true
            }
        }

        private fun readFully(input: InputStream, target: ByteArray, count: Int, checkActive: () -> Unit) {
            var offset = 0
            while (offset < count) {
                checkActive()
                val read = input.read(target, offset, minOf(count - offset, BLOCK_BYTES))
                if (read < 0) throw ArchiveAccessException.Invalid("Archive entry is smaller than its header")
                if (read == 0) throw IOException("Archive entry stream made no progress")
                offset += read
            }
        }
    }
}
