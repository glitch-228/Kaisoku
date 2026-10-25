package org.koitharu.kotatsu.remote.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.archive.ArchiveAccess
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.importer.SingleMangaImporter
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual importer/local-reader path using only task-created files and preference namespaces. */
@RunWith(AndroidJUnit4::class)
class LibraryImporterTest {
    @Test fun epubAndCbzStagesUseExistingImporterAndRemainReadableOffline() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ArchiveAccess.get(app)
        val root = File(app.cacheDir, "remote-import-${UUID.randomUUID()}").apply { mkdir() }
        val prefix = root.name + "-"
        val preferences = HashSet<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name; preferences += scoped
                return app.getSharedPreferences(scoped, mode)
            }
        }
        try {
            val output = File(root, "output").apply { mkdir() }
            val settings = AppSettings(context).apply { mangaStorageDir = output }
            val importer = SingleMangaImporter(context, LocalStorageManager(context, settings), MutableSharedFlow<LocalManga?>())
            val connection = RemoteLibraryConnection.create("Home", "https://library.invalid/opds/catalog", RemoteLibraryKind.OPDS)
            val store = RemoteLibraryStore(File(root, "connections.json")).apply { save(connection) }
            for (format in LibraryFormat.entries) {
                val stage = File(root, "stage-${format.name}")
                val acquired = LibraryAcquisitionTask.run(stage, "Offline book", "edition-${format.name}", format,
                    { file -> write(file, format); sha(file) }) { file ->
                    val result = importer.import(file.toUri())
                    assertTrue(result.failures.isEmpty())
                    result.imported.single()
                }
                assertTrue(stage.listFiles()!!.isEmpty())
                val local = acquired.value
                assertTrue(local.file.isFile)
                assertEquals(acquired.sha256, sha(local.file))
                val parser = LocalMangaParser(local.file)
                val details = parser.getManga(true).manga
                val chapters = details.chapters!!
                assertTrue(chapters.isNotEmpty())
                assertEquals(chapters.map { it.id }, LocalMangaParser(local.file).getManga(true).manga.chapters!!.map { it.id })
                if (format == LibraryFormat.EPUB) {
                    assertTrue(parser.isNovel())
                    val html = parser.getChapterHtml(chapters.first())
                    assertTrue(html.contains("Offline chapter")); assertTrue(html.contains("file+zip:")); assertFalse(html.contains("https:"))
                } else {
                    assertEquals(1, parser.getPages(chapters.first()).size)
                }
                val record = LibraryAcquisitionRecord(connection.id, "edition-${format.name}", local.manga.id,
                    local.manga.url, acquired.sha256, format)
                store.record(record)
                assertEquals(listOf(record), RemoteLibraryStore(File(root, "connections.json")).acquired(connection.id, record.publicationId))
            }
            assertEquals(2, output.listFiles()!!.size)
            assertEquals(2, store.acquisitions().size)
        } finally {
            preferences.forEach(app::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }

    private fun sha(file: File): String = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun write(file: File, format: LibraryFormat) {
        val image = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jg6kAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
        val entries = if (format == LibraryFormat.CBZ) mapOf("001.png" to image) else linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "OPS/book.opf" to """<package version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Offline book</dc:title></metadata>
                <manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="image" href="image.png" media-type="image/png"/></manifest>
                <spine><itemref idref="one"/></spine></package>""".toByteArray(),
            "OPS/one.xhtml" to "<html><body><p>Offline chapter<img src=\"image.png\"/></p></body></html>".toByteArray(),
            "OPS/image.png" to image,
        )
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }
}
