package org.koitharu.kotatsu.remote.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Credentials/network/importers never enter backup, filtering or local metadata dependencies. */
@Singleton
class RemoteLibraryPersistence internal constructor(private val storage: () -> RemoteLibraryStore) {
    @Inject constructor(@ApplicationContext context: Context) : this({
        RemoteLibraryStore(File(context.filesDir, "remote_libraries/connections.json"),
            File(context.noBackupFilesDir, "remote_libraries/verified.json"))
    })

    internal val store by lazy(storage)
    internal val mutation = Mutex()
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> get() = mutableChanges

    suspend fun initialize() = withContext(Dispatchers.IO) { mutation.withLock { publish() } }
    suspend fun connections(): List<RemoteLibraryConnection> = withContext(Dispatchers.IO) {
        mutation.withLock { store.connections().also { publish() } }
    }

    /** Exact, already published lookup. Core filtering getters never initialize disk storage. */
    fun acquiredMetadata(manga: Manga): LibraryAcquiredMetadata? =
        RemoteLibrarySourceRegistry.acquiredMetadata(manga.id, manga.url)

    fun applyAcquiredMetadata(manga: Manga): Manga {
        // Local repository paths explicitly allow durable lookup before the first warmup, but
        // core content getters use the registry API above and remain purely in memory.
        val metadata = store.metadataForLocal(manga.id, manga.url)
        RemoteLibrarySourceRegistry.updateAcquiredMetadata(manga.id, manga.url, metadata)
        return metadata?.applyTo(manga) ?: manga
    }

    suspend fun exportTransfer(): String = withContext(Dispatchers.IO) { mutation.withLock { store.exportTransfer() } }
    suspend fun previewTransfer(value: String): LibraryTransferPreview = withContext(Dispatchers.IO) {
        mutation.withLock { store.previewTransfer(value) }
    }

    suspend fun mergeTransfer(value: String, checkCurrent: () -> Unit = {}): LibraryTransferPreview = withContext(Dispatchers.IO) {
        mutation.withLock {
            // Store revalidates and commits under the same monitor. Conflicts fail before any write.
            checkCurrent()
            store.mergeTransfer(value, checkCurrent).also { changed() }
        }
    }

    internal fun changed() { publish(); mutableChanges.update { it + 1L } }
    private fun publish() { store.publish() }
}
