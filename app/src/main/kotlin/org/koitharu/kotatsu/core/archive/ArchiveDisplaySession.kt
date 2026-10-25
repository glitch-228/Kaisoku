package org.koitharu.kotatsu.core.archive

import org.koitharu.kotatsu.core.archive.ArchiveSessionManager
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A lock retires this display permanently, even if the same archive is immediately unlocked again. */
internal class ArchiveDisplaySession(
    sessions: ArchiveSessionManager,
    currentArchives: () -> Collection<File>,
    retire: () -> Unit,
) : Closeable {
    private val retired = AtomicBoolean(false)
    private val listener = sessions.addLockListener { paths ->
        if (currentArchives().any { it.canonicalPath in paths } && retired.compareAndSet(false, true)) {
            retire()
        }
    }

    override fun close() = listener.close()
}
