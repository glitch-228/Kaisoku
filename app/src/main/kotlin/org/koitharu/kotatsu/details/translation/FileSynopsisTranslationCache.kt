package org.koitharu.kotatsu.details.translation

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Disposable private derived data. Its bounded envelope is never part of a library backup. */
internal class FileSynopsisTranslationCache(
    private val directory: File,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val maxEntries: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) : SynopsisTranslationCache {

    @Synchronized
    override fun read(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            if (file.length() !in 9..(SynopsisTranslationService.MAX_RESULT_BYTES.toLong() + 8)) return null
            val text = DataInputStream(file.inputStream().buffered()).use { stream ->
                if (stream.readInt() != FORMAT_MAGIC) return null
                val length = stream.readInt()
                if (length !in 1..SynopsisTranslationService.MAX_RESULT_BYTES || file.length() != length.toLong() + 8) {
                    return null
                }
                val bytes = ByteArray(length)
                stream.readFully(bytes)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().takeIf { it.isNotBlank() }
            }
            if (text != null) file.setLastModified(clock())
            text
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    override fun write(key: String, text: String) {
        val target = fileFor(key)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (text.isBlank() || bytes.size > SynopsisTranslationService.MAX_RESULT_BYTES || bytes.size + 8 > maxBytes) {
            return
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Synopsis cache is unavailable")
        val staged = File.createTempFile("synopsis-", ".tmp", directory)
        try {
            DataOutputStream(staged.outputStream().buffered()).use { stream ->
                stream.writeInt(FORMAT_MAGIC)
                stream.writeInt(bytes.size)
                stream.write(bytes)
            }
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setLastModified(clock())
            trim()
        } finally {
            staged.delete()
        }
    }

    private fun fileFor(key: String): File {
        require(KEY_PATTERN.matches(key)) { "Invalid synopsis cache key" }
        return File(directory, "$key.bin")
    }

    private fun trim() {
        val files = directory.listFiles { file -> file.isFile && FILE_PATTERN.matches(file.name) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
        var size = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (size <= maxBytes && count <= maxEntries) break
            val length = file.length()
            if (file.delete()) {
                size -= length
                count--
            }
        }
    }

    private companion object {
        const val FORMAT_MAGIC = 0x53594e31
        val KEY_PATTERN = Regex("[0-9a-f]{64}")
        val FILE_PATTERN = Regex("[0-9a-f]{64}\\.bin")
    }
}
