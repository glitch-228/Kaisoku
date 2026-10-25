package org.koitharu.kotatsu.local.data.book

import org.koitharu.kotatsu.core.archive.ArchiveRepository
import java.io.File
import java.io.IOException

data class BookArchiveEntry(val name: String, val size: Long, val isDirectory: Boolean = false)

/** Bounds are enforced by the archive backend and again at the package boundary. */
interface BookArchive {
    val entries: List<BookArchiveEntry>
    fun read(name: String, maximumBytes: Long): ByteArray
}

class EpubArchiveSource(private val file: File, private val archives: ArchiveRepository) : BookArchive {
    override val entries = archives.entries(file).map { BookArchiveEntry(it.name, it.size, it.isDirectory) }
    override fun read(name: String, maximumBytes: Long): ByteArray = archives.readBytes(file, name, maximumBytes)
}

data class EpubResource(val id: String, val path: String, val mediaType: String, val properties: Set<String>)
data class EpubChapter(val path: String, val title: String, val manifestId: String)
data class EpubNavigationEntry(val title: String, val chapterPath: String, val fragment: String?)

data class EpubBook(
    val title: String,
    val authors: Set<String>,
    val language: String?,
    val identifier: String?,
    val description: String?,
    val packagePath: String,
    val coverPath: String?,
    val resources: Map<String, EpubResource>,
    val chapters: List<EpubChapter>,
    val navigation: List<EpubNavigationEntry>,
) {
    /** Path rather than position/text content supplies the stable chapter component. */
    fun chapterIdentity(bookIdentity: String, chapter: EpubChapter): String = "$bookIdentity\u0000${chapter.path}"

    fun readChapter(archive: BookArchive, chapter: EpubChapter, imageUri: (String) -> String): String {
        if (chapters.none { it.path == chapter.path }) throw EpubException.Invalid("EPUB chapter is not in the reading spine")
        val bytes = archive.read(chapter.path, EpubParser.MAX_CHAPTER_BYTES)
        return try { EpubHtml.sanitize(bytes, chapter.path, resources, imageUri) } finally { bytes.fill(0) }
    }
}

sealed class EpubException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Invalid(message: String, cause: Throwable? = null) : EpubException(message, cause)
    class Unsupported(message: String) : EpubException(message)
    class Limit(message: String) : EpubException(message)
}
