package org.koitharu.kotatsu.local.data.importer

import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.core.exceptions.CbrImportException
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbrArchiveCompatibilityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun zipWithCbrExtensionKeepsOriginalBytes() = runTest {
        val source = temporaryFolder.newFile("source.cbr")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("chapter/001.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val original = source.readBytes()
        val result = import(source, "book.cbr")
        assertEquals("book.cbz", result.name)
        assertArrayEquals(original, result.readBytes())
        assertArrayEquals(original, source.readBytes())
        java.util.zip.ZipFile(result).use { assertTrue(it.getEntry("chapter/001.jpg") != null) }
        assertNoStagingFiles()
    }

    @Test
    fun encryptedZipWithRarExtensionRemainsEncrypted() = runTest {
        val page = temporaryFolder.newFile("001.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val source = File(temporaryFolder.root, "encrypted.rar")
        ZipFile(source, "fixture password".toCharArray()).use { zip ->
            zip.addFile(page, ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            })
        }
        val original = source.readBytes()
        val result = import(source, "protected.rar")
        assertArrayEquals(original, result.readBytes())
        assertArrayEquals(original, source.readBytes())
        ZipFile(result).use { assertTrue(it.isEncrypted) }
        assertNoStagingFiles()
    }

    @Test
    fun rawRar5UsesExistingBoundedConverter() = runTest {
        val source = temporaryFolder.newFile("source.rar")
        requireNotNull(javaClass.classLoader?.getResourceAsStream("cbr/comic-rar5.rar")).use {
            source.outputStream().use(it::copyTo)
        }
        val original = source.readBytes()
        val result = import(source, "book.rar")
        java.util.zip.ZipFile(result).use { assertTrue(it.entries().hasMoreElements()) }
        assertArrayEquals(original, source.readBytes())
        assertNoStagingFiles()
    }

    @Test
    fun corruptZipSignaturePreservesExistingDestination() = runTest {
        val existing = temporaryFolder.newFile("book.cbz").apply { writeText("existing") }
        val source = temporaryFolder.newFile("broken.cbr").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3))
        }
        val failure = runCatching { import(source, "book.cbr") }.exceptionOrNull()
        assertTrue(failure is CbrImportException)
        assertEquals("existing", existing.readText())
        assertNoStagingFiles()
    }

    private suspend fun import(source: File, name: String): File = CbrImportTransaction.import(
        name, temporaryFolder.root, copyInput = { source.copyTo(it) },
    )

    private fun assertNoStagingFiles() {
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".cbr-import-") })
    }
}
