package org.koitharu.kotatsu.core.archive

/** Elapsed-time policy; a wall-clock change cannot extend an unlocked background session. */
class ArchiveSessionPolicy(
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    timeoutMinutes: Int = 5,
) {
    @Volatile var timeoutMinutes: Int = timeoutMinutes
        set(value) {
            require(value in ALLOWED_MINUTES)
            field = value
        }

    private var background = false
    private var speechActive = false
    private var countdownStarted: Long? = null

    init { require(timeoutMinutes in ALLOWED_MINUTES) }

    /** Must be called before making any protected view visible. */
    @Synchronized
    fun onForeground(): Boolean {
        val expired = isExpired()
        background = false
        countdownStarted = null
        return expired
    }

    @Synchronized
    fun onBackground(): Boolean {
        val expired = isExpired()
        if (!background) {
            background = true
            if (!speechActive) countdownStarted = elapsedMillis()
        }
        return expired
    }

    @Synchronized
    fun onSpeechChanged(active: Boolean): Boolean {
        val expired = isExpired()
        if (speechActive != active) {
            speechActive = active
            countdownStarted = if (background && !active) elapsedMillis() else null
        }
        return expired
    }

    @Synchronized
    fun isExpired(): Boolean = countdownStarted?.let { start ->
        // A reset elapsed clock is treated as expiry rather than granting an unlimited session.
        val elapsed = elapsedMillis() - start
        elapsed < 0 || elapsed >= timeoutMinutes * 60_000L
    } == true

    @Synchronized
    fun remainingMillis(): Long? = countdownStarted?.let { start ->
        val elapsed = elapsedMillis() - start
        if (elapsed < 0) 0 else (timeoutMinutes * 60_000L - elapsed).coerceAtLeast(0)
    }

    companion object { val ALLOWED_MINUTES = setOf(1, 5, 15, 30) }
}
