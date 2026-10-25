package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.local.data.importer.ImportTransaction
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryAcquisitionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualHttpStageValidationAndAtomicPublicationPreserveExistingOriginals() = runBlocking {
        val bytes = cbz()
        LibraryHttpFixture { _, socket -> socket.respond(bytes, contentType = "application/vnd.comicbook+zip") }.use { server ->
            val transport = RemoteLibraryTransport(server.policy, { null })
            val link = LibraryLink(server.base + "book", "application/vnd.comicbook+zip", setOf("download"), size = bytes.size.toLong())
            val stageRoot = temporary.newFolder("stage")
            val output = temporary.newFolder("published")
            val existing = File(output, "Book.cbz").apply { writeText("old original") }
            var imports = 0
            val acquired = LibraryAcquisitionTask.run(stageRoot, "Book", "opaque:0001", LibraryFormat.CBZ,
                { transport.download(link, it) }) { staged ->
                imports++
                ImportTransaction.run(output, "Book.cbz", { dir -> File(dir, "Book.cbz").also { staged.copyTo(it) } }, { file ->
                    LibraryAcquisitionValidator.validate(file, LibraryFormat.CBZ)
                    file
                })
            }
            assertEquals(1, imports)
            assertEquals("old original", existing.readText())
            assertNotEquals(existing, acquired.value)
            assertTrue(bytes.contentEquals(acquired.value.readBytes()))
            assertEquals(64, acquired.sha256.length)
            assertTrue(stageRoot.listFiles()!!.isEmpty())
            assertTrue(server.requests.all { it.method == "GET" })
        }
    }

    @Test fun invalidDownloadAndCancellationNeverInvokeTheImporterOrLeaveAStage() = runBlocking {
        val stage = temporary.newFolder("stage")
        var imported = false
        val invalid = runCatching { LibraryAcquisitionTask.run(stage, "Book", "id", LibraryFormat.CBZ,
            { file -> file.writeText("<html>authentication page</html>"); "a".repeat(64) }, { imported = true }) }.exceptionOrNull()
        assertTrue(invalid is RemoteLibraryException)
        assertFalse(imported)
        assertTrue(stage.listFiles()!!.isEmpty())
        val cancelled = runCatching { LibraryAcquisitionTask.run(stage, "Book", "id", LibraryFormat.CBZ,
            { file -> file.writeBytes(cbz()); throw CancellationException("fixture") }, { imported = true }) }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertFalse(imported)
        assertTrue(stage.listFiles()!!.isEmpty())
    }

    @Test fun epubTwoAndThreeAcquisitionsUseTheExistingReflowableParser() {
        for (version in listOf("2.0", "3.0")) {
            val file = File(temporary.root, "Book-$version.epub").apply { writeBytes(epub(version)) }
            val before = file.readBytes()
            LibraryAcquisitionValidator.validate(file, LibraryFormat.EPUB)
            assertTrue(before.contentEquals(file.readBytes()))
            assertThrows(LibraryUnsupportedException::class.java) { LibraryAcquisitionValidator.validate(file, LibraryFormat.CBZ) }
        }
    }

    @Test fun unsafePathsFakeImagesAndUnsupportedFixedLayoutsAreRejected() {
        val traversal = File(temporary.root, "traversal.cbz").apply { writeBytes(zip(mapOf("../outside.png" to PNG))) }
        assertThrows(RemoteLibraryException::class.java) { LibraryAcquisitionValidator.validate(traversal, LibraryFormat.CBZ) }
        val fake = File(temporary.root, "fake.cbz").apply { writeBytes(zip(mapOf("page.png" to "<html>not an image</html>".toByteArray()))) }
        assertThrows(RemoteLibraryException::class.java) { LibraryAcquisitionValidator.validate(fake, LibraryFormat.CBZ) }
        val fixed = File(temporary.root, "fixed.epub").apply { writeBytes(epub("3.0", fixed = true)) }
        assertThrows(RemoteLibraryException::class.java) { LibraryAcquisitionValidator.validate(fixed, LibraryFormat.EPUB) }
    }

    @Test fun encryptedCbzKeepsCiphertextAndDefersContentValidationToExplicitSessionUnlock() {
        val file = File(temporary.root, "protected.cbz")
        val password = "fixture-password".toCharArray()
        try {
            net.lingala.zip4j.io.outputstream.ZipOutputStream(file.outputStream(), password).use { zip ->
                zip.putNextEntry(net.lingala.zip4j.model.ZipParameters().apply {
                    fileNameInZip = "001.png"; isEncryptFiles = true
                    encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                })
                zip.write(PNG); zip.closeEntry()
            }
            val ciphertext = file.readBytes()
            LibraryAcquisitionValidator.validate(file, LibraryFormat.CBZ)
            assertTrue(ciphertext.contentEquals(file.readBytes()))
            assertEquals(listOf(file), temporary.root.listFiles()!!.toList())
            assertTrue(org.koitharu.kotatsu.core.archive.ArchiveRepository.inspectEntries(file).single().isEncrypted)
        } finally { password.fill('\u0000') }
    }

    companion object {
        internal val PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jg6kAAAAASUVORK5CYII=")
        internal fun cbz(): ByteArray = zip(linkedMapOf("001.png" to PNG, "002.png" to PNG))
        internal fun epub(version: String = "3.0", fixed: Boolean = false): ByteArray = zip(linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "OPS/book.opf" to """<package version="$version"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Book 日本語</dc:title>
                ${if (fixed) "<meta property=\"rendition:layout\">pre-paginated</meta>" else ""}</metadata>
                <manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="image" href="image.png" media-type="image/png"/></manifest>
                <spine><itemref idref="one"/></spine></package>""".toByteArray(),
            "OPS/one.xhtml" to "<html><body><p>Chapter text<img src=\"image.png\"/></p></body></html>".toByteArray(),
            "OPS/image.png" to PNG,
        ))
        private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        }.toByteArray()
    }
}
