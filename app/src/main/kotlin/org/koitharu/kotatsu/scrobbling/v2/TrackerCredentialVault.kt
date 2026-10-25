package org.koitharu.kotatsu.scrobbling.v2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TrackerCredentialVault {
    suspend fun read(provider: String): String?
    suspend fun write(provider: String, value: String)
    suspend fun clear(provider: String)
}

/** App-private atomic envelope protected by Android Keystore and excluded from every Android backup. */
class KeystoreTrackerCredentialVault(context: Context) : TrackerCredentialVault {
    private val root = File(context.noBackupFilesDir, "tracker_credentials")

    override suspend fun read(provider: String): String? = withContext(Dispatchers.IO) {
        val file = file(provider)
        if (!file.baseFile.exists()) return@withContext null
        val envelope = file.openRead().use { stream ->
            val maximum = MAX_ENVELOPE_BYTES + 1
            val bytes = ByteArray(maximum)
            var count = 0
            while (count < maximum) {
                val read = stream.read(bytes, count, maximum - count)
                if (read < 0) break
                count += read
            }
            check(count in 29..MAX_ENVELOPE_BYTES) { "Protected tracker connection is invalid; reconnect" }
            bytes.copyOf(count)
        }
        check(envelope[0] == 1.toByte()) { "Protected tracker connection version is unsupported" }
        val nonce = envelope.copyOfRange(1, 13)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(provider.toByteArray(Charsets.UTF_8))
        try {
            cipher.doFinal(envelope, 13, envelope.size - 13).toString(Charsets.UTF_8)
        } finally {
            envelope.fill(0)
        }
    }

    override suspend fun write(provider: String, value: String) = withContext(Dispatchers.IO) {
        val file = file(provider)
        val plain = value.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_ENVELOPE_BYTES - 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val encrypted = try {
            // Android Keystore requires its own random IV when randomized encryption is enabled.
            cipher.init(Cipher.ENCRYPT_MODE, key())
            check(cipher.iv?.size == 12) { "Protected tracker encryption is unavailable" }
            cipher.updateAAD(provider.toByteArray(Charsets.UTF_8))
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val nonce = cipher.iv
        if (!root.isDirectory) check(root.mkdirs() || root.isDirectory) { "Protected tracker storage is unavailable" }
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(1)
            output.write(nonce)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        } finally {
            encrypted.fill(0)
        }
    }

    override suspend fun clear(provider: String) = withContext(Dispatchers.IO) { file(provider).delete() }

    private fun file(provider: String): AtomicFile {
        require(Regex("[A-Z][A-Z0-9_]{0,63}").matches(provider))
        return AtomicFile(File(root, "$provider.bin"))
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        val KEY_LOCK = Any()
        const val KEY_ALIAS = "kaisoku_tracker_credentials_v1"
        const val MAX_ENVELOPE_BYTES = 64 * 1024
    }
}
