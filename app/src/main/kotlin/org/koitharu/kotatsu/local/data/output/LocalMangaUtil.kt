package org.koitharu.kotatsu.local.data.output

import androidx.core.net.toFile
import androidx.core.net.toUri
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.parsers.model.Manga

class LocalMangaUtil(
    private val manga: Manga,
) {

    init {
        require(manga.isLocal) { "Expected LOCAL source but ${manga.source} found" }
    }

    suspend fun deleteChapters(ids: Set<Long>) {
        val file = manga.url.toUri().toFile()
        if (org.koitharu.kotatsu.local.data.hasEpubExtension(file.name)) {
            throw java.io.IOException("EPUB chapters cannot be removed individually; the original book is preserved")
        }
        if (file.isDirectory) {
            LocalMangaDirOutput(file, manga).use { output ->
                output.deleteChapters(ids)
                output.finish()
            }
        } else {
            LocalMangaZipOutput.filterChapters(file, manga, ids)
        }
    }
}
