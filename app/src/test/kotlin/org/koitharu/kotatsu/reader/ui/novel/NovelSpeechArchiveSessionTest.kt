package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.core.archive.ArchiveAccessException
import org.koitharu.kotatsu.core.archive.ArchiveSessionManager
import org.koitharu.kotatsu.core.archive.ArchiveSessionPolicy

class NovelSpeechArchiveSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cachedTextCannotStartWithoutAnUnlockedArchive() {
        val sessions = ArchiveSessionManager()
        val guard = NovelSpeechArchiveSession(sessions)
        expectLocked { guard.capture(temporary.newFile()) }
    }

    @Test fun lockRejectsDelayedVoiceServiceAndCachedChunkConsumers() {
        val sessions = ArchiveSessionManager()
        val file = temporary.newFile().apply { writeText("encrypted fixture identity") }
        sessions.remember(file, charArrayOf('p'))
        val guard = NovelSpeechArchiveSession(sessions)
        guard.capture(file)
        guard.requireActive()
        sessions.lock(file)
        repeat(3) { expectLocked { guard.requireActive() } }
    }

    @Test fun aNewUnlockDoesNotAuthorizeTheOldDecryptedRequest() {
        val sessions = ArchiveSessionManager()
        val file = temporary.newFile()
        sessions.remember(file, charArrayOf('p'))
        val guard = NovelSpeechArchiveSession(sessions)
        guard.capture(file)
        sessions.lock(file)
        sessions.remember(file, charArrayOf('p'))
        assertTrue(sessions.isUnlocked(file))
        expectLocked { guard.requireActive() }
        guard.capture(file)
        guard.requireActive()
    }

    @Test fun archiveReplacementRejectsPreviouslyPreparedChunks() {
        val sessions = ArchiveSessionManager()
        val file = temporary.newFile()
        sessions.remember(file, charArrayOf('p'))
        val guard = NovelSpeechArchiveSession(sessions)
        guard.capture(file)
        file.writeText("replacement is a different archive")
        expectLocked { guard.requireActive() }
    }

    @Test fun pausedBackgroundExpiryCannotBeCancelledByQueuedResume() {
        var now = 0L
        val sessions = ArchiveSessionManager(ArchiveSessionPolicy({ now }, 1))
        val file = temporary.newFile()
        sessions.remember(file, charArrayOf('p'))
        val guard = NovelSpeechArchiveSession(sessions)
        guard.capture(file)
        sessions.onSpeechChanged(true)
        sessions.onBackground()
        now = 10 * 60_000L
        guard.requireActive()
        sessions.onSpeechChanged(false)
        now += 60_000L
        expectLocked { guard.requireActive() }
        sessions.onSpeechChanged(true)
        expectLocked { guard.requireActive() }
    }

    @Test fun activePlaybackDefersExpiryUntilPauseAndForegroundChecksTheBoundary() {
        var now = 0L
        val sessions = ArchiveSessionManager(ArchiveSessionPolicy({ now }, 5))
        val file = temporary.newFile()
        sessions.remember(file, charArrayOf('p'))
        val guard = NovelSpeechArchiveSession(sessions)
        guard.capture(file)
        sessions.onSpeechChanged(true)
        sessions.onBackground()
        now = 86_400_000
        guard.requireActive()
        sessions.onSpeechChanged(false)
        now += 299_999
        guard.requireActive()
        now++
        sessions.onForeground()
        expectLocked { guard.requireActive() }
    }

    @Test fun ordinaryNovelsDoNotRequireOrRetainArchiveSessions() {
        val sessions = ArchiveSessionManager()
        val guard = NovelSpeechArchiveSession(sessions)
        guard.capture(null)
        sessions.lockAll()
        guard.requireActive()
        val file = temporary.newFile()
        sessions.remember(file, charArrayOf('p'))
        guard.capture(file)
        guard.clear()
        sessions.lock(file)
        guard.capture(null)
        guard.requireActive()
    }

    @Test fun stoppedOrRejectedRequestsCannotReclassifyCachedPrivateTextAsOrdinary() {
        val sessions = ArchiveSessionManager()
        val guard = NovelSpeechArchiveSession(sessions)
        expectLocked { guard.requireActive() }
        guard.capture(null)
        guard.requireActive()
        guard.clear()
        expectLocked { guard.requireActive() }
        expectLocked { guard.capture(temporary.newFile()) }
        expectLocked { guard.requireActive() }
    }

    private fun expectLocked(action: () -> Unit) {
        assertTrue(runCatching(action).exceptionOrNull() is ArchiveAccessException.Locked)
    }
}
