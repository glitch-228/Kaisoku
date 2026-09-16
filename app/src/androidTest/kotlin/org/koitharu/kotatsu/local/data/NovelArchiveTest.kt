package org.koitharu.kotatsu.local.data

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.util.UUID

class NovelArchiveTest {
    @Test fun singleAndMultipleArchivesReadTextAndImagesWithoutPlugin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "novel-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (format in listOf(DownloadFormat.SINGLE_CBZ, DownloadFormat.MULTIPLE_CBZ)) {
                val source = MangaSource("lnreader:offline-test")
                val chapter = MangaChapter(22L, "Chapter", 1f, 0, "book|||chapter", null, 0L, null, source)
                val manga = Manga(id = 21L, title = format.name, altTitles = emptySet(), url = "book",
                    publicUrl = "https://site/book", rating = 0f, contentRating = null, coverUrl = null,
                    tags = emptySet(), state = null, authors = emptySet(), chapters = listOf(chapter), source = source)
                val text = File(root, "text.html").apply { writeText("<p>Offline text</p><img src='kaisoku-page:1'>") }
                val image = File(root, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val output = LocalMangaOutput.getOrCreate(root, manga, format)
                output.use {
                    it.addPage(IndexedValue(0, chapter), text, 0, "text/html".toMimeTypeOrNull())
                    it.addPage(IndexedValue(0, chapter), image, 1, "image/png".toMimeTypeOrNull())
                    it.flushChapter(chapter)
                    it.finish()
                }
                val parser = LocalMangaParser(output.rootFile)
                assertTrue(parser.isNovel())
                val local = parser.getManga(true).manga
                assertEquals(manga.id, local.id)
                val localChapter = local.chapters!!.single()
                assertEquals(chapter.id, localChapter.id)
                val html = LocalMangaParser(localChapter.url.toUri()).getChapterHtml(localChapter)
                assertTrue(html.contains("Offline text"))
                assertTrue(html.contains("zip:"))
                assertFalse(html.contains("kaisoku-page:"))
                assertFalse(html.contains("https://"))
            }
        } finally {
            root.deleteRecursively() // Only this test's unique temporary directory.
        }
    }
}
