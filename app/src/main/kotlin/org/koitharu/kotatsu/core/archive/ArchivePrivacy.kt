package org.koitharu.kotatsu.core.archive

import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.net.URI

/** Only the public filename and durable archive references may be persisted for protected local titles. */
object ArchivePrivacy {
    fun file(manga: Manga?): File? {
        if (manga?.source?.name != "LOCAL") return null
        return try {
            val uri = URI(manga.url)
            if (uri.scheme != "file" && uri.scheme != "file+zip" && uri.scheme != "zip" && uri.scheme != "cbz") null
            else uri.path?.let(::File)
        } catch (_: Exception) { null }
    }

    fun isSensitive(manga: Manga?): Boolean = file(manga)?.let { file ->
        file.isFile && file.extension.lowercase() in setOf("cbz", "zip", "epub") &&
            ArchiveRepository.inspectEntries(file).any { it.isEncrypted }
    } == true

    /** Missing archives cannot establish that persistent plaintext metadata is safe. */
    fun canPersistMetadata(manga: Manga): Boolean {
        val archive = file(manga) ?: return true
        if (archive.extension.lowercase() !in setOf("cbz", "zip", "epub")) return true
        return archive.isFile && runCatching { !isSensitive(manga) }.getOrDefault(false)
    }

    fun sanitize(manga: Manga): Manga {
        val file = file(manga) ?: return manga
        if (!isSensitive(manga)) return manga
        return manga.copy(
            title = file.nameWithoutExtension.replace('_', ' '),
            altTitles = emptySet(), authors = emptySet(), tags = emptySet(),
            description = null, coverUrl = null, largeCoverUrl = null,
            chapters = manga.chapters?.map { it.copy(title = null, scanlator = null) },
        )
    }

    /** Parcel/Intent writes run on the UI thread. Redact all local archive snapshots without disk IO. */
    fun publicReference(manga: Manga): Manga {
        val file = file(manga) ?: return manga
        if (file.extension.lowercase() !in setOf("cbz", "zip", "epub")) return manga
        return manga.copy(title = file.nameWithoutExtension.replace('_', ' '), altTitles = emptySet(), authors = emptySet(),
            tags = emptySet(), description = null, coverUrl = null, largeCoverUrl = null, chapters = null)
    }
}
