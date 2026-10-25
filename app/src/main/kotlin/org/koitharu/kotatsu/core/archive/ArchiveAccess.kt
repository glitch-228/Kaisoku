package org.koitharu.kotatsu.core.archive

import android.content.Context
import java.io.File

/** Process owner. It intentionally has no persistent credential or session store. */
object ArchiveAccess {
    @Volatile private var current: ArchiveRepository? = null
    val repository: ArchiveRepository get() = checkNotNull(current) { "Archive access has not been initialized" }
    val sessions: ArchiveSessionManager get() = repository.sessions

    @Synchronized
    fun initialize(context: Context) {
        if (current != null) return
        val store = ArchiveEntryStore(File(context.cacheDir, "protected-archive-scratch"))
        store.clearAbandonedScratch()
        current = ArchiveRepository(ArchiveSessionManager(), store)
        ArchiveImageUris.observeLocks(sessions)
    }

    fun get(context: Context): ArchiveRepository {
        if (current == null) initialize(context.applicationContext)
        return repository
    }
}
