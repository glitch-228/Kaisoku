package org.koitharu.kotatsu.core.archive

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.koitharu.kotatsu.core.util.ext.toZipUri
import java.io.File

/** Requires an isolated test device/emulator; compilation is not execution evidence. */
class ArchiveImageUrisTest {
    @Test fun privateCapabilityHasNoPathAndRevokesOnSessionLock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("archive-uri-test-", ".cbz", context.cacheDir)
        val password = "fixture password".toCharArray()
        try {
            ZipOutputStream(file.outputStream(), password).use { zip ->
                zip.putNextEntry(ZipParameters().apply {
                    fileNameInZip = "private-name/page.png"
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                })
                zip.write("fixture bytes".toByteArray()); zip.closeEntry()
            }
            val archives = ArchiveAccess.get(context)
            archives.unlock(file, password)
            val original = file.toZipUri("private-name/page.png")
            val capability = ArchiveImageUris.resolve(context, original)
            assertEquals("content", capability.scheme)
            assertFalse(capability.toString().contains(file.name))
            assertFalse(capability.toString().contains("private-name"))
            assertEquals(original, ArchiveImageUris.original(context, capability))
            assertEquals("fixture bytes", ArchiveImageUris.openStream(context, capability).bufferedReader().use { it.readText() })
            assertEquals("fixture bytes", context.contentResolver.openInputStream(capability)!!.bufferedReader().use { it.readText() })
            assertTrue(ArchiveImageUris.isSensitive(context, capability))
            archives.sessions.lock(file)
            assertTrue(runCatching { ArchiveImageUris.openStream(context, capability) }.exceptionOrNull() is ArchiveAccessException.Locked)
        } finally {
            password.fill('\u0000')
            ArchiveAccess.sessions.lock(file)
            file.delete()
        }
    }
}
