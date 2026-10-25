package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.UUID

data class LibraryAcquired<T>(val value: T, val sha256: String)

/** The importer owns publication; this task owns only a fresh private download stage. */
object LibraryAcquisitionTask {
    suspend fun <T> run(root: File, title: String, publicationId: String, format: LibraryFormat,
                        download: suspend (File) -> String, import: suspend (File) -> T): LibraryAcquired<T> {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Acquisition staging is unavailable")
        val stage = File(root, UUID.randomUUID().toString())
        if (!stage.mkdir()) throw IOException("Cannot create acquisition stage")
        try {
            val stem = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim().trim('.').take(80).ifBlank { "Book" }
            val file = File(stage, "$stem-${libraryDigest(publicationId).take(12)}.${format.extension}")
            val digest = download(file)
            currentCoroutineContext().ensureActive()
            LibraryAcquisitionValidator.validate(file, format)
            currentCoroutineContext().ensureActive()
            return LibraryAcquired(import(file), digest)
        } finally { stage.deleteRecursively() }
    }
}
