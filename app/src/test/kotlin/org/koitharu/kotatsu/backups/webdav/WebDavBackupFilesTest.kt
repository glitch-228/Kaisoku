package org.koitharu.kotatsu.backups.webdav

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.backups.domain.BackupSection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebDavBackupFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completeNativeZipRetainsSectionsAndRejectsNonNativeOrCorruptedDownloads() = runTest {
        val archive = temporary.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, contents) in mapOf("index" to "[]", "history" to "[]", "exact_completion_v1" to "[]")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(contents.toByteArray()); zip.closeEntry()
            }
        }
        assertEquals(setOf(BackupSection.INDEX, BackupSection.HISTORY, BackupSection.EXACT_COMPLETION),
            WebDavBackupFiles.validate(archive))
        val truncatedDirectory = temporary.newFile().apply { writeBytes(archive.readBytes().dropLast(22).toByteArray()) }
        assertTrue(runCatching { WebDavBackupFiles.validate(truncatedDirectory) }.exceptionOrNull() is IOException)
        val invalid = temporary.newFile().apply { writeText("not a backup") }
        assertTrue(runCatching { WebDavBackupFiles.validate(invalid) }.exceptionOrNull() is IOException)
        archive.writeBytes(archive.readBytes().copyOf(12))
        assertTrue(runCatching { WebDavBackupFiles.validate(archive) }.exceptionOrNull() is IOException)
    }

    @Test fun excessiveEntriesAndOversizedOutputAreBoundedAndDoNotRewriteOriginals() = runTest {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(65) { index -> zip.putNextEntry(ZipEntry("unknown-$index")); zip.write(1); zip.closeEntry() }
        }
        val bytes = file.readBytes()
        assertTrue(runCatching { WebDavBackupFiles.validate(file) }.exceptionOrNull() is IOException)
        assertArrayEquals(bytes, file.readBytes())
        var cancellationChecks = 0
        val sink = ByteArrayOutputStream()
        val bounded = WebDavBackupFiles.boundedOutput(sink) { cancellationChecks++ }
        bounded.write(byteArrayOf(1, 2, 3))
        bounded.write(4)
        assertEquals(2, cancellationChecks)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.toByteArray())
    }

    @Test fun malformedEntityAndOversizedListingsNeverReachXmlExpansion() {
        val endpoint = WebDavEndpoint.parse("https://example.test/dav/")
        for (xml in listOf("<not-dav/>", "<d:multistatus xmlns:d='DAV:'>",
            "<!DOCTYPE a [<!ENTITY x 'secret'>]><d:multistatus xmlns:d='DAV:'>&x;</d:multistatus>")) {
            assertThrows(IOException::class.java) { WebDavListing.parse(xml.toByteArray(), endpoint) }
        }
        assertThrows(IOException::class.java) { WebDavListing.parse(ByteArray(WebDavListing.MAX_BYTES + 1), endpoint) }
    }

    @Test fun unknownFailedAndOutOfScopePropertiesCannotBecomeRestorableFiles() {
        val endpoint = WebDavEndpoint.parse("https://example.test/dav/")
        val xml = """<d:multistatus xmlns:d="DAV:">
            <d:response><d:href>https://other.test/dav/x.bk.zip</d:href><d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/dav/bad.bk.zip</d:href><d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Missing</d:status></d:propstat></d:response>
            <d:response><d:href>/dav/good.bk.zip</d:href><d:propstat><d:prop><d:getcontentlength>2</d:getcontentlength><d:getetag>W/"weak"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>"""
        val item = WebDavListing.parse(xml.toByteArray(), endpoint).single()
        assertEquals("good.bk.zip", item.name)
        assertNull(item.strongEtag)
    }
}
