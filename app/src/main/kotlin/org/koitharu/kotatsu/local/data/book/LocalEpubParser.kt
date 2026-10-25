package org.koitharu.kotatsu.local.data.book

import androidx.core.net.toUri
import org.koitharu.kotatsu.core.archive.ArchiveAccess
import org.koitharu.kotatsu.core.archive.ArchiveAccessException
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.longHashCode
import java.io.File

/** Retains the imported package; only current chapter text is decompressed, never a replacement book. */
internal class LocalEpubParser(private val file: File) {
    private val archives get() = ArchiveAccess.repository
    private val id get() = file.absolutePath.longHashCode()

    fun getManga(withDetails: Boolean): LocalManga {
        val book = try { EpubParser.parse(EpubArchiveSource(file, archives)) } catch (locked: ArchiveAccessException.Locked) {
            if (withDetails) throw locked
            return LocalManga(generic(), file)
        }
        val chapters = if (withDetails) book.chapters.mapIndexed { index, chapter ->
            MangaChapter(
                id = book.chapterIdentity(file.absolutePath, chapter).longHashCode(),
                title = chapter.title,
                number = (index + 1).toFloat(),
                volume = 0,
                url = file.toUri().buildUpon().fragment(chapter.path).build().toString(),
                source = LocalMangaSource,
                uploadDate = 0,
                scanlator = null,
                branch = null,
            )
        } else null
        return LocalManga(generic().copy(
            title = book.title,
            authors = book.authors,
            description = book.description,
            coverUrl = book.coverPath?.let(::archiveUri),
            chapters = chapters,
        ), file)
    }

    fun readChapter(chapter: MangaChapter): String {
        val archive = EpubArchiveSource(file, archives)
        val book = EpubParser.parse(archive)
        val requested = chapter.url.toUri().fragment
        val entry = book.chapters.singleOrNull { it.path == requested }
            ?: throw EpubException.Invalid("EPUB chapter is not in the current reading spine")
        if (book.chapterIdentity(file.absolutePath, entry).longHashCode() != chapter.id) {
            throw EpubException.Invalid("EPUB chapter identity changed")
        }
        return book.readChapter(archive, entry, ::archiveUri)
    }

    private fun archiveUri(entry: String): String = file.toUri().buildUpon().scheme(URI_SCHEME_ZIP).fragment(entry).build().toString()

    fun generic(): Manga = Manga(
        id = id,
        title = file.nameWithoutExtension.replace('_', ' '),
        altTitles = emptySet(),
        url = file.toUri().toString(),
        publicUrl = file.toUri().toString(),
        rating = -1f,
        contentRating = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        largeCoverUrl = null,
        coverUrl = null,
        description = null,
        chapters = null,
        source = LocalMangaSource,
    )
}
