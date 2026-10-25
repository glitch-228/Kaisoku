package org.koitharu.kotatsu.backups.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.backups.domain.BackupSection
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

internal object WebDavBackupFiles {
    /** The native restore chooser only receives a complete bounded ZIP with an actual native index. */
    suspend fun validate(file: File): Set<BackupSection> = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 1..WebDavTransport.MAX_BACKUP_BYTES) { "Backup is empty or too large" }
        val sections = linkedSetOf<BackupSection>()
        val names = hashSetOf<String>()
        // Streaming local headers alone do not prove the transfer includes its complete central directory.
        val directoryNames = ZipFile(file).use { zip ->
            val known = linkedSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (known.size >= 64 || !known.add(entry.name) || entry.isDirectory ||
                    entry.size < 0 || entry.size > MAX_ENTRY_BYTES) throw IOException("Invalid native backup directory")
            }
            known
        }
        var total = 0L
        var entries = 0
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (++entries > 64 || !names.add(entry.name)) throw IOException("Invalid native backup entries")
                if (entry.size > MAX_ENTRY_BYTES) throw IOException("Native backup entry is too large")
                var entryBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    entryBytes += count
                    if (total > MAX_EXPANDED_BYTES || entryBytes > MAX_ENTRY_BYTES) {
                        throw IOException("Native backup exceeds its expanded size limit")
                    }
                }
                BackupSection.of(entry)?.let(sections::add)
                zip.closeEntry()
            }
        }
        if (names != directoryNames) throw IOException("Native backup directory does not match its entries")
        if (BackupSection.INDEX !in sections || sections.size < 2) throw IOException("This is not a supported native backup")
        sections
    }

    fun boundedOutput(output: OutputStream, cancellation: () -> Unit): OutputStream = object : FilterOutputStream(output) {
        private var total = 0L
        override fun write(b: Int) {
            cancellation()
            if (++total > WebDavTransport.MAX_BACKUP_BYTES) throw IOException("Native backup is too large for WebDAV")
            out.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            cancellation()
            if (len < 0 || total + len > WebDavTransport.MAX_BACKUP_BYTES) {
                throw IOException("Native backup is too large for WebDAV")
            }
            total += len
            out.write(b, off, len)
        }
    }

    private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
}
