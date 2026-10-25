package org.koitharu.kotatsu.core.archive

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.SecureRandom

/** Private display capabilities; reader/library identities retain their original archive URI. */
object ArchiveImageUris {
    internal data class Capability(val original: Uri, val file: File, val entry: String, val token: ArchiveSessionManager.Token)
    private val capabilities = object : LinkedHashMap<String, Capability>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Capability>?): Boolean = size > 128
    }
    private val random = SecureRandom()

    internal fun observeLocks(sessions: ArchiveSessionManager) {
        sessions.addLockListener { paths -> synchronized(capabilities) {
            capabilities.entries.removeAll { it.value.token.fingerprint.path in paths }
        } }
    }

    fun resolve(context: Context, original: Uri): Uri {
        if (!original.isZipUri()) return original
        val file = File(original.path ?: throw ArchiveAccessException.Invalid("Archive image path is missing"))
        val entry = original.fragment?.takeIf { it.isNotEmpty() } ?: throw ArchiveAccessException.Invalid("Archive image entry is missing")
        val archives = ArchiveAccess.get(context)
        if (!archives.isSensitive(file)) return original
        val token = archives.sessions.token(file)
        val key = synchronized(capabilities) {
            capabilities.entries.firstOrNull { it.value.original == original && it.value.token == token }?.key ?: run {
                val bytes = ByteArray(24).also(random::nextBytes)
                bytes.joinToString("") { "%02x".format(it.toInt() and 255) }.also {
                    capabilities[it] = Capability(original, file, validateArchivePath(entry), token)
                }
            }
        }
        return Uri.Builder().scheme("content").authority(context.packageName + ".archive").appendPath(key).build()
    }

    internal fun capability(context: Context, uri: Uri): Capability {
        if (!isPrivate(context, uri)) throw ArchiveAccessException.Locked()
        val key = uri.pathSegments.singleOrNull() ?: throw ArchiveAccessException.Locked()
        val result = synchronized(capabilities) { capabilities[key] } ?: throw ArchiveAccessException.Locked()
        ArchiveAccess.get(context).sessions.requireActive(result.token)
        return result
    }

    fun original(context: Context, uri: Uri): Uri? = if (isPrivate(context, uri)) {
        synchronized(capabilities) { uri.pathSegments.singleOrNull()?.let { capabilities[it]?.original } }
    } else uri

    /** Capture before revocation removes the private capability; never fall back to a guessed path. */
    fun displayArchive(context: Context, uri: Uri): File? = when {
        isPrivate(context, uri) -> capability(context, uri).file
        uri.isZipUri() -> uri.path?.let(::File)
        else -> null
    }

    fun isSensitive(context: Context, uri: Uri): Boolean {
        if (isPrivate(context, uri)) return true
        if (uri.scheme != "file" && !uri.isZipUri()) return false
        val file = uri.path?.let(::File) ?: return false
        if (!file.isFile) return false
        return ArchiveAccess.get(context).repositorySensitiveOrFalse(file)
    }

    /** An IO failure is not evidence that a known archive became safe to cache. */
    private fun ArchiveRepository.repositorySensitiveOrFalse(file: File): Boolean =
        if (file.extension.lowercase() in setOf("cbz", "zip", "epub")) isSensitive(file) else false

    fun openStream(context: Context, uri: Uri): InputStream {
        val archives = ArchiveAccess.get(context)
        val (file, name) = when {
            isPrivate(context, uri) -> capability(context, uri).let { it.file to it.entry }
            uri.isZipUri() -> File(uri.path ?: throw ArchiveAccessException.Invalid("Archive path is missing")) to
                (uri.fragment ?: throw ArchiveAccessException.Invalid("Archive entry is missing"))
            uri.scheme == "file" -> return File(uri.path ?: throw ArchiveAccessException.Invalid("File path is missing")).inputStream()
            else -> return context.contentResolver.openInputStream(uri) ?: throw ArchiveAccessException.Invalid("Cannot open local image")
        }
        val handle = archives.openSeekable(file, name)
        return object : FilterInputStream(handle.openStream()) {
            override fun close() { try { super.close() } finally { handle.close() } }
        }
    }

    private fun isPrivate(context: Context, uri: Uri) = uri.scheme == "content" && uri.authority == context.packageName + ".archive"
}
