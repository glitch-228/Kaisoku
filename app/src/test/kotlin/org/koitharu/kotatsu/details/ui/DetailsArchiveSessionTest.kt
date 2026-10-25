package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.core.archive.ArchiveDisplaySession

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.archive.ArchiveSessionManager
import org.koitharu.kotatsu.core.archive.ArchiveSessionPolicy
import java.io.File

class DetailsArchiveSessionTest {
    @Test fun lockingDisplayedArchiveRetiresExactlyOnceEvenAfterAnotherUnlock() = fixture { file, sessions ->
        var retired = 0
        ArchiveDisplaySession(sessions, { listOf(file) }) { retired++ }.use {
            sessions.lock(file)
            sessions.remember(file, charArrayOf('b'))
            sessions.lock(file)
            assertEquals(1, retired)
        }
    }

    @Test fun unrelatedLockDoesNotRetireCurrentOrRemoteDisplay() = fixture { file, sessions ->
        var retired = 0
        ArchiveDisplaySession(sessions, { emptyList() }) { retired++ }.use {
            sessions.lock(file)
            assertEquals(0, retired)
        }
    }

    @Test fun fileSelectionIsResolvedAtLockInsteadOfWaitingForMenuInspection() = fixture { file, sessions ->
        var current: Collection<File> = emptyList()
        var retired = 0
        ArchiveDisplaySession(sessions, { current }) { retired++ }.use {
            current = listOf(file)
            sessions.lockAll()
            assertEquals(1, retired)
        }
    }

    @Test fun ordinaryDuplicateLockDoesNotInventSessionRevocation() = fixture { file, sessions ->
        sessions.lock(file)
        var retired = 0
        ArchiveDisplaySession(sessions, { listOf(file) }) { retired++ }.use {
            sessions.lock(file)
            assertEquals(0, retired)
        }
    }

    @Test fun closedDisplayDoesNotRetainAnArchiveLockCallback() = fixture { file, sessions ->
        var retired = 0
        ArchiveDisplaySession(sessions, { listOf(file) }) { retired++ }.close()
        sessions.lock(file)
        assertEquals(0, retired)
    }

    @Test fun backgroundExpiryRetiresBeforeForegroundDisplay() {
        var elapsed = 0L
        val sessions = ArchiveSessionManager(ArchiveSessionPolicy({ elapsed }, 1))
        fixture(sessions) { file, manager ->
            var retired = 0
            ArchiveDisplaySession(manager, { listOf(file) }) { retired++ }.use {
                manager.onBackground()
                elapsed = 60_000
                manager.onForeground()
                assertEquals(1, retired)
            }
        }
    }

    private fun fixture(
        sessions: ArchiveSessionManager = ArchiveSessionManager(),
        block: (File, ArchiveSessionManager) -> Unit,
    ) {
        val file = File.createTempFile("details-session-", ".cbz")
        val original = file.readBytes()
        try {
            sessions.remember(file, charArrayOf('a'))
            block(file, sessions)
            org.junit.Assert.assertArrayEquals(original, file.readBytes())
        } finally {
            sessions.close()
            file.delete()
        }
    }
}
