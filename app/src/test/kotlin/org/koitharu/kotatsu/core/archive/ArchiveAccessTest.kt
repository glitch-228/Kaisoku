package org.koitharu.kotatsu.core.archive

import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry

class ArchiveAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun repository(memory: Long = 32L * 1024 * 1024): Pair<ArchiveRepository, ArchiveEntryStore> {
        val store = ArchiveEntryStore(temporary.newFolder(), memoryLimit = memory)
        return ArchiveRepository(ArchiveSessionManager(), store) to store
    }

    @Test fun ordinaryAndAesAndZipCryptoHaveTheSameEntryBytes() {
        for (encryption in listOf(null, EncryptionMethod.AES, EncryptionMethod.ZIP_STANDARD)) {
            val source = fixture(encryption)
            val (archives, _) = repository()
            assertEquals(encryption != null, archives.isSensitive(source))
            if (encryption != null) {
                expect<ArchiveAccessException.Locked> { archives.readBytes(source, "chapter/page.txt", 8192) }
                expect<ArchiveAccessException.WrongPassword> { archives.unlock(source, "incorrect".toCharArray()) }
                assertFalse(archives.sessions.isUnlocked(source))
                archives.unlock(source, PASSWORD.toCharArray())
            }
            assertArrayEquals(CONTENT, archives.readBytes(source, "chapter/page.txt", 8192))
            archives.openSeekable(source, "chapter/page.txt").use { entry ->
                val out = ByteArray(17)
                assertEquals(17, entry.readAt(73, out))
                assertArrayEquals(CONTENT.copyOfRange(73, 90), out)
            }
        }
    }

    @Test fun lockingRevokesMemoryAndScratchAndClearsReservations() {
        for (memory in listOf(0L, 1024 * 1024L)) {
            val (archives, storage) = repository(memory)
            val source = fixture(EncryptionMethod.AES)
            archives.unlock(source, PASSWORD.toCharArray())
            val entry = archives.openSeekable(source, "chapter/page.txt")
            assertTrue(storage.allocatedMemoryBytes() + storage.allocatedScratchBytes() > 0)
            archives.sessions.lock(source)
            expect<ArchiveAccessException.Locked> { entry.readAt(0, ByteArray(1)) }
            assertEquals(0, storage.allocatedMemoryBytes())
            assertEquals(0, storage.allocatedScratchBytes())
            entry.close()
        }
    }

    @Test fun replacingTheArchiveInvalidatesTheCredential() {
        val (archives, _) = repository()
        val source = fixture(EncryptionMethod.AES)
        archives.unlock(source, PASSWORD.toCharArray())
        val entry = archives.openSeekable(source, "chapter/page.txt")
        source.setLastModified(source.lastModified() + 10_000)
        expect<ArchiveAccessException.Locked> { entry.readAt(0, ByteArray(1)) }
        assertFalse(archives.sessions.isUnlocked(source))
    }

    @Test fun seekableScratchIsEncryptedAndRandomAccessIsExact() {
        val directory = temporary.newFolder()
        val store = ArchiveEntryStore(directory, memoryLimit = 0)
        val bytes = ByteArray(200_001) { ((it * 19 + 71) % 251).toByte() }
        store.prepare(bytes.inputStream(), bytes.size.toLong()).use { entry ->
            val scratch = directory.listFiles()!!.single()
            assertFalse(scratch.readBytes().toString(Charsets.ISO_8859_1)
                .contains(bytes.copyOf(64).toString(Charsets.ISO_8859_1)))
            for (offset in listOf(0L, 65_530L, 131_000L, 199_995L)) {
                val output = ByteArray(90)
                val count = entry.readAt(offset, output)
                assertArrayEquals(bytes.copyOfRange(offset.toInt(), (offset + count).toInt()), output.copyOf(count))
            }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals(0, store.allocatedScratchBytes())
    }

    @Test fun scratchTamperingIsRejectedAndResourcesAreReleased() {
        val directory = temporary.newFolder()
        val store = ArchiveEntryStore(directory, memoryLimit = 0)
        val entry = store.prepare(CONTENT.inputStream(), CONTENT.size.toLong())
        val scratch = directory.listFiles()!!.single()
        RandomAccessFile(scratch, "rw").use { it.seek(11); val original = it.read(); it.seek(11); it.write(original xor 1) }
        expect<ArchiveAccessException.Invalid> { entry.readAt(0, ByteArray(64)) }
        entry.close()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals(0, store.allocatedScratchBytes())
    }

    @Test fun cancelledAndLyingStreamsDoNotRetainPlaintextOrReservations() {
        val directory = temporary.newFolder()
        for (memory in listOf(0L, 1024L)) {
            val store = ArchiveEntryStore(directory, memoryLimit = memory)
            expect<ArchiveAccessException.Invalid> { store.prepare(ByteArray(16).inputStream(), 8) }
            expect<ArchiveAccessException.Invalid> { store.prepare(ByteArray(4).inputStream(), 8) }
            expect<InterruptedIOException> {
                store.prepare(CONTENT.inputStream(), CONTENT.size.toLong()) { throw InterruptedIOException("cancelled") }
            }
            assertEquals(0, store.allocatedMemoryBytes())
            assertEquals(0, store.allocatedScratchBytes())
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun traversalAndDuplicateIdentityAreRejectedBeforeReading() {
        val file = temporary.newFile("unsafe.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt")); zip.write(1); zip.closeEntry()
        }
        val (archives, _) = repository()
        expect<ArchiveAccessException.Invalid> { archives.entries(file) }
        for (path in listOf("a/../b", "/outside", "C:/outside", "a\\b", "a//b", "a/./b")) {
            expect<ArchiveAccessException.Invalid> { validateArchivePath(path) }
        }
    }

    @Test fun stagedEncryptedExportPreservesOriginalAndExistingDestination() {
        val source = fixture(null)
        val before = source.readBytes()
        val (archives, _) = repository()
        val destination = File(temporary.root, "protected.cbz")
        EncryptedArchiveExporter(archives).export(source, destination, PASSWORD.toCharArray())
        assertTrue(archives.isSensitive(destination))
        archives.unlock(destination, PASSWORD.toCharArray())
        assertArrayEquals(CONTENT, archives.readBytes(destination, "chapter/page.txt", 8192))
        val exported = destination.readBytes()
        expect<java.io.IOException> { EncryptedArchiveExporter(archives).export(source, destination, PASSWORD.toCharArray()) }
        assertArrayEquals(exported, destination.readBytes())
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun cancelledExportPublishesNothingAndPreservesOriginal() {
        val source = fixture(null)
        val before = source.readBytes()
        val (archives, _) = repository()
        val destination = File(temporary.root, "cancelled.cbz")
        var checks = 0
        expect<InterruptedIOException> {
            EncryptedArchiveExporter(archives).export(source, destination, PASSWORD.toCharArray(), checkCancelled = {
                if (++checks > 6) throw InterruptedIOException("cancelled")
            })
        }
        assertFalse(destination.exists())
        assertArrayEquals(before, source.readBytes())
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".encrypted-export-") })
    }

    private fun fixture(encryption: EncryptionMethod?): File {
        val file = File(temporary.newFolder(), "original.cbz")
        ZipOutputStream(file.outputStream(), PASSWORD.toCharArray()).use { zip ->
            zip.putNextEntry(ZipParameters().apply {
                fileNameInZip = "chapter/page.txt"
                isEncryptFiles = encryption != null
                if (encryption != null) encryptionMethod = encryption
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            })
            zip.write(CONTENT)
            zip.closeEntry()
        }
        return file
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got $error", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    companion object {
        private const val PASSWORD = "test-only password"
        private val CONTENT = ByteArray(8192) { ((it * 19 + 71) % 251).toByte() }
    }
}
