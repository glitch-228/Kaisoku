package org.koitharu.kotatsu.local.data.book

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.archive.ArchiveAccess
import org.koitharu.kotatsu.core.archive.ArchiveAccessException
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import kotlinx.coroutines.runBlocking
import java.io.File

/** Exercises the actual local chapter capability, independently of network/provider accounts. */
class LocalEpubParserTest {
    @Test fun originalPackageAndStableSpineAreUsedByTheNovelReaderCapability() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ArchiveAccess.initialize(context)
        val dir = File(context.cacheDir, "epub-fixture-${java.util.UUID.randomUUID()}").apply { mkdir() }
        val file = File(dir, "Book # 日本語.epub")
        try {
            write(file, false)
            val original = file.readBytes()
            val parser = LocalMangaParser(file)
            assertTrue(parser.isNovel())
            val local = parser.getManga(true)
            assertEquals("Fixture book", local.manga.title)
            val chapters = requireNotNull(local.manga.chapters)
            assertEquals(2, chapters.size)
            val html = LocalMangaParser(chapters.first().url.toUri()).getChapterHtml(chapters.first())
            assertTrue(html.contains("First chapter"))
            assertTrue(html.contains("file+zip:"))
            assertFalse(html.contains("https:"))
            assertEquals(chapters.map { it.id }, LocalMangaParser(file).getManga(true).manga.chapters!!.map { it.id })
            assertTrue(original.contentEquals(file.readBytes()))
            assertEquals(listOf(file), dir.listFiles()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun protectedPackageRemainsVisibleAndRequiresAnActiveSessionForText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archives = ArchiveAccess.get(context)
        val file = File.createTempFile("protected-epub-", ".epub", context.cacheDir)
        val password = "fixture-password".toCharArray()
        try {
            write(file, true)
            val parser = LocalMangaParser(file)
            assertTrue(parser.isNovel())
            assertEquals(file.nameWithoutExtension, parser.getManga(false).manga.title)
            assertTrue(runCatching { parser.getManga(true) }.exceptionOrNull() is ArchiveAccessException.Locked)
            archives.unlock(file, password)
            val manga = parser.getManga(true).manga
            val chapter = manga.chapters!!.first()
            assertTrue(LocalMangaParser(chapter.url.toUri()).getChapterHtml(chapter).contains("First chapter"))
            archives.sessions.lock(file)
            assertTrue(runCatching { LocalMangaParser(chapter.url.toUri()).getChapterHtml(chapter) }.exceptionOrNull() is ArchiveAccessException.Locked)
        } finally { archives.sessions.lock(file); password.fill('\u0000'); file.delete() }
    }

    private fun write(file: File, encrypted: Boolean) {
        val data = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OPS/book.opf" to """<package version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Fixture book</dc:title></metadata><manifest><item id="one" href="first.xhtml" media-type="application/xhtml+xml"/><item id="two" href="second.xhtml" media-type="application/xhtml+xml"/><item id="image" href="image.jpg" media-type="image/jpeg"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
            "OPS/first.xhtml" to "<html><body><p>First chapter<img src=\"image.jpg\"/></p></body></html>",
            "OPS/second.xhtml" to "<html><body><p>Second chapter</p></body></html>",
            "OPS/image.jpg" to "image fixture bytes",
        )
        ZipOutputStream(file.outputStream(), "fixture-password".toCharArray()).use { zip ->
            for ((name, text) in data) {
                zip.putNextEntry(ZipParameters().apply {
                    fileNameInZip = name
                    isEncryptFiles = encrypted
                    encryptionMethod = EncryptionMethod.AES
                })
                zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }
}
