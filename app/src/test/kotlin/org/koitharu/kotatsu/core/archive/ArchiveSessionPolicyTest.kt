package org.koitharu.kotatsu.core.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSessionPolicyTest {
    @Test fun backgroundExpiresAtBoundaryBeforeForegroundContent() {
        var time = 100L
        val policy = ArchiveSessionPolicy({ time })
        policy.onBackground()
        time += 299_999
        assertFalse(policy.isExpired())
        time++
        assertTrue(policy.onForeground())
        assertFalse(policy.isExpired())
    }

    @Test fun activeSpeechDefersCountdownUntilPaused() {
        var time = 0L
        val policy = ArchiveSessionPolicy({ time }, 1)
        policy.onSpeechChanged(true)
        policy.onBackground()
        time = 24 * 60 * 60_000L
        assertFalse(policy.isExpired())
        policy.onSpeechChanged(false)
        time += 59_999
        assertFalse(policy.isExpired())
        time++
        assertTrue(policy.isExpired())
    }

    @Test fun elapsedClockResetDoesNotExtendSession() {
        var time = 50_000L
        val policy = ArchiveSessionPolicy({ time })
        policy.onBackground()
        time = 1
        assertTrue(policy.onForeground())
    }

    @Test fun repeatedBackgroundDoesNotRestartCountdown() {
        var time = 0L
        val policy = ArchiveSessionPolicy({ time }, 1)
        policy.onBackground()
        time = 59_000
        policy.onBackground()
        time = 60_000
        assertTrue(policy.isExpired())
    }
}
