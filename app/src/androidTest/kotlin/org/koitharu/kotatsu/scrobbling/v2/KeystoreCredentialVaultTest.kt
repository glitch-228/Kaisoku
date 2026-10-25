package org.koitharu.kotatsu.scrobbling.v2

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android Keystore, a fresh app-private test directory, and no account/network operations. */
class KeystoreCredentialVaultTest {
    @Test fun randomizedIvRecreationOverwriteAndAssociatedProviderArePreserved() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "vault-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val value = "private fixture ${UUID.randomUUID()}"
            val vault = KeystoreTrackerCredentialVault(context)
            vault.write("VAULT_FIXTURE", value)
            val file = File(directory, "tracker_credentials/VAULT_FIXTURE.bin")
            val first = file.readBytes()
            assertEquals(1, first[0].toInt())
            assertFalse(first.toString(Charsets.ISO_8859_1).contains(value))
            assertEquals(value, KeystoreTrackerCredentialVault(context).read("VAULT_FIXTURE"))
            vault.write("VAULT_FIXTURE", value)
            val second = file.readBytes()
            assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
            assertEquals(value, vault.read("VAULT_FIXTURE"))
            File(directory, "tracker_credentials/OTHER_FIXTURE.bin").writeBytes(second)
            assertNotNull(runCatching { vault.read("OTHER_FIXTURE") }.exceptionOrNull())
            assertNotNull(runCatching { vault.write("VAULT_FIXTURE", "x".repeat(64 * 1024)) }.exceptionOrNull())
            assertArrayEquals(second, file.readBytes())
            assertEquals(value, vault.read("VAULT_FIXTURE"))
            vault.clear("VAULT_FIXTURE")
            assertNull(vault.read("VAULT_FIXTURE"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
