package org.koitharu.kotatsu.remote.library

import org.koitharu.kotatsu.local.data.book.BookArchive
import org.koitharu.kotatsu.local.data.book.BookArchiveEntry
import org.koitharu.kotatsu.local.data.book.EpubParser
import org.koitharu.kotatsu.core.archive.ArchiveRepository
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Inspects the actual staged package before the existing atomic importer publishes anything. */
object LibraryAcquisitionValidator {
    fun validate(file: File, format: LibraryFormat) {
        if (file.length() < 22) throw RemoteLibraryException("The acquired file is not a ZIP book")
        file.inputStream().use { input ->
            val signature = ByteArray(4)
            if (input.read(signature) != 4 || !signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                throw RemoteLibraryException("The server returned content other than an EPUB/CBZ package")
            }
        }
        try {
            // The common Zip4j-backed inspection enforces central-directory/path/compression
            // bounds before Java's ZIP reader allocates anything. Ciphertext is retained intact;
            // a background acquisition never asks for or persists an archive password.
            val metadata = ArchiveRepository.inspectEntries(file)
            var total = 0L
            for (entry in metadata) {
                if (entry.size < 0 || entry.size > 128L * 1024 * 1024 || total > 1024L * 1024 * 1024 - entry.size) {
                    throw LibraryLimitException("The acquired package exceeds safe expanded-size limits")
                }
                total += entry.size
            }
            if (metadata.any { it.isEncrypted }) {
                if (format == LibraryFormat.EPUB) {
                    if (metadata.none { it.name == "mimetype" } || metadata.none { it.name == "META-INF/container.xml" }) {
                        throw RemoteLibraryException("The encrypted EPUB directory has no reading package")
                    }
                } else if (metadata.none { !it.isDirectory && it.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in
                        setOf("jpg", "jpeg", "png", "gif", "webp", "avif") }) {
                    throw RemoteLibraryException("The encrypted CBZ directory has no supported pages")
                }
                // Actual content, authentication, DRM/layout and geometry are checked by the
                // common reader after explicit session unlock, not inferred from encrypted bytes.
                return
            }
            ZipFile(file).use { archive ->
                val entries = archive.entries().toList()
                if (entries.size > MAX_LIBRARY_PAGES || entries.isEmpty()) throw LibraryLimitException("The acquired package has too many entries")
                val seen = HashSet<String>()
                var expanded = 0L
                for (entry in entries) {
                    val name = entry.name
                    if (name.isEmpty() || name.startsWith('/') || '\\' in name || '\u0000' in name || ':' in name.substringBefore('/') ||
                        name.removeSuffix("/").split('/').any { it.isEmpty() || it == "." || it == ".." } || !seen.add(name)) {
                        throw RemoteLibraryException("The acquired package contains unsafe or duplicate entry paths")
                    }
                    if (entry.size < 0 || entry.size > 128L * 1024 * 1024 || expanded > 1024L * 1024 * 1024 - entry.size) {
                        throw LibraryLimitException("The acquired package exceeds safe expanded-size limits")
                    }
                    expanded += entry.size
                }
                if (format == LibraryFormat.EPUB) {
                    EpubParser.parse(object : BookArchive {
                        override val entries = entries.map { BookArchiveEntry(it.name, it.size, it.isDirectory) }
                        override fun read(name: String, maximumBytes: Long): ByteArray {
                            val entry = archive.getEntry(name) ?: throw RemoteLibraryException("Required EPUB entry is missing")
                            if (entry.size > maximumBytes || maximumBytes > Int.MAX_VALUE) throw LibraryLimitException("EPUB entry is too large")
                            return archive.getInputStream(entry).use { it.readBytesLimited(maximumBytes.toInt()) }.also {
                                if (it.size.toLong() != entry.size) throw RemoteLibraryException("The EPUB entry is truncated")
                            }
                        }
                    })
                } else {
                    // A renamed EPUB must keep its actual format; HTML/ACSM/DRM files are not comics.
                    if (archive.getEntry("mimetype") != null && archive.getEntry("META-INF/container.xml") != null) {
                        throw LibraryUnsupportedException("The server advertised CBZ but returned EPUB; choose its EPUB acquisition")
                    }
                    val first = entries.firstOrNull { !it.isDirectory && it.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in
                        setOf("jpg", "jpeg", "png", "gif", "webp", "avif") }
                        ?: throw RemoteLibraryException("The acquired CBZ contains no supported images")
                    archive.getInputStream(first).use { input ->
                        val prefix = ByteArray(16)
                        var size = 0
                        while (size < prefix.size) { val count = input.read(prefix, size, prefix.size - size); if (count < 0) break; size += count }
                        if (!isImage(prefix, size)) throw RemoteLibraryException("The acquired comic does not contain actual image data")
                    }
                }
            }
        } catch (error: RemoteLibraryException) { throw error }
        catch (error: IOException) { throw RemoteLibraryException("The acquired book package is invalid", error) }
    }

    private fun isImage(bytes: ByteArray, size: Int): Boolean =
        (size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
            (size >= 8 && bytes.copyOf(8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) ||
            (size >= 6 && bytes.copyOf(6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) ||
            (size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") ||
            (size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) in setOf("avif", "avis"))
}
