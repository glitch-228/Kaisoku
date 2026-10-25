package org.koitharu.kotatsu.core.archive

import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry

class ArchiveFileSystemTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readOnlyViewPreservesDirectoriesNamesAndRandomReads() {
        val nested = ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("image.txt")); zip.write("nested content".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val file = File(temporary.root, "book.cbz")
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, value) in mapOf("index.json" to "{}".toByteArray(), "chapters/第一章.zip" to nested)) {
                output.putNextEntry(ZipParameters().apply { fileNameInZip = name })
                output.write(value); output.closeEntry()
            }
        }
        val archives = ArchiveRepository(ArchiveSessionManager(), ArchiveEntryStore(temporary.newFolder()))
        ArchiveFileSystem(file, archives).use { fs ->
            assertEquals(listOf("/chapters".toPath(), "/index.json".toPath()), fs.list("/".toPath()))
            assertTrue(fs.metadata("/chapters".toPath()).isDirectory)
            assertEquals("{}", fs.source("/index.json".toPath()).buffer().use { it.readUtf8() })
            assertEquals(listOf("/chapters/第一章.zip".toPath()), fs.list("/chapters".toPath()))
            fs.openReadOnly("/index.json".toPath()).use { handle ->
                val bytes = ByteArray(1)
                assertEquals(1, handle.read(1, bytes, 0, 1))
                assertArrayEquals("}".toByteArray(), bytes)
            }
            fs.openZip("/chapters/第一章.zip".toPath()).use { zip ->
                assertEquals("nested content", zip.source("/image.txt".toPath()).buffer().use { it.readUtf8() })
            }
            expectIOException { fs.delete("/index.json".toPath()) }
            expectIOException { fs.sink("/overwrite".toPath()) }
            expectIOException { fs.createDirectories("/newdir".toPath()) }
            assertFalse(fs.exists("/missing".toPath()))
        }
    }

    private fun expectIOException(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Expected read-only failure")
    }
}
