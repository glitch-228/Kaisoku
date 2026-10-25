package org.koitharu.kotatsu.core.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Publishes a separate verified AES-256 archive. Existing source/output files are never rewritten. */
class EncryptedArchiveExporter(private val archives: ArchiveRepository) {
    fun export(
        source: File,
        destination: File,
        password: CharArray,
        checkCancelled: () -> Unit = ::checkArchiveInterrupted,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        require(password.isNotEmpty()) { "An export password is required" }
        if (source.canonicalFile == destination.canonicalFile || destination.exists()) {
            throw IOException("Encrypted export requires a new destination")
        }
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Export destination is missing")
        if (!parent.isDirectory) throw IOException("Export directory is unavailable")
        val entries = archives.entries(source)
        if (entries.isEmpty()) throw ArchiveAccessException.Invalid("Cannot export an empty archive")
        val total = entries.sumOf { it.size }
        if (total < 0 || total > 8L * 1024 * 1024 * 1024 || entries.any { it.size > ArchiveEntryStore.MAX_ENTRY_BYTES }) {
            throw ArchiveAccessException.Limit("Archive exceeds the encrypted export size limit")
        }
        val stage = File.createTempFile(".encrypted-export-", ".zip", parent)
        val copy = password.copyOf()
        val digests = HashMap<String, ByteArray>()
        val buffer = ByteArray(64 * 1024)
        var published = false
        var completed = 0L
        try {
            ZipOutputStream(stage.outputStream().buffered(), copy).use { output ->
                for (entry in entries) {
                    checkCancelled()
                    val parameters = ZipParameters().apply {
                        fileNameInZip = entry.name
                        compressionMethod = CompressionMethod.DEFLATE
                        isEncryptFiles = !entry.isDirectory
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    output.putNextEntry(parameters)
                    if (!entry.isDirectory) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        archives.withEntry(source, entry.name, checkCancelled = checkCancelled) { input ->
                            while (true) {
                                checkCancelled()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) throw IOException("Export source made no progress")
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                buffer.fill(0, 0, read)
                                completed += read
                                onProgress(completed, total)
                            }
                        }
                        digests[entry.name] = digest.digest()
                    }
                    output.closeEntry()
                }
            }
            ZipFile(stage, copy).use { zip ->
                val actual = zip.fileHeaders
                if (actual.map { it.fileName } != entries.map { it.name }) throw IOException("Encrypted export entry verification failed")
                for (entry in actual.filterNot { it.isDirectory }) {
                    checkCancelled()
                    if (!entry.isEncrypted || entry.aesExtraDataRecord?.aesKeyStrength != AesKeyStrength.KEY_STRENGTH_256) {
                        throw IOException("Encrypted export is missing AES-256 protection")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            checkCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) throw IOException("Export verification made no progress")
                            digest.update(buffer, 0, read)
                            buffer.fill(0, 0, read)
                        }
                    }
                    if (!MessageDigest.isEqual(digests[entry.fileName], digest.digest())) {
                        throw IOException("Encrypted export content verification failed")
                    }
                }
            }
            checkCancelled()
            // Without REPLACE_EXISTING, a destination claimed by another writer remains untouched.
            Files.move(stage.toPath(), destination.toPath())
            published = true
            return destination
        } finally {
            copy.fill('\u0000')
            buffer.fill(0)
            digests.values.forEach { it.fill(0) }
            if (!published) stage.delete()
        }
    }
}
