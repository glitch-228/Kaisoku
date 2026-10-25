package org.koitharu.kotatsu.reader.ui.novel

import org.koitharu.kotatsu.core.archive.ArchiveSessionManager
import java.io.File

/** Cached speech text belongs to one unlock, never to a later unlock of the same archive. */
internal class NovelSpeechArchiveSession(private val sessions: ArchiveSessionManager) {
    private var token: ArchiveSessionManager.Token? = null
    private var captured = false

    fun capture(protectedFile: File?) {
        clear()
        token = protectedFile?.let(sessions::token)
        captured = true
    }

    fun requireActive() {
        if (!captured) throw org.koitharu.kotatsu.core.archive.ArchiveAccessException.Locked()
        token?.let(sessions::requireActive)
    }

    fun clear() { token = null; captured = false }
}
