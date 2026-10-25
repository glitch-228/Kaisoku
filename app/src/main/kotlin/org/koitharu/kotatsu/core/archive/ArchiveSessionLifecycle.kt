package org.koitharu.kotatsu.core.archive

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager

/** One process-level timeout, with no WorkManager jobs or persisted secrets. */
object ArchiveSessionLifecycle : DefaultLifecycleObserver, SharedPreferences.OnSharedPreferenceChangeListener {
    const val KEY_TIMEOUT_MINUTES = "archive_session_timeout_minutes"
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { ArchiveAccess.sessions.enforceTimeout() }
    private var started = false
    private var background = false
    private var speaking = false

    fun start(context: Context) {
        if (started) return
        started = true
        ArchiveAccess.initialize(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        applyTimeout(preferences)
        preferences.registerOnSharedPreferenceChangeListener(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(expiry)
        ArchiveAccess.sessions.onForeground() // Revokes overdue handles synchronously before resumed content.
        background = false
    }

    override fun onStop(owner: LifecycleOwner) {
        background = true
        ArchiveAccess.sessions.onBackground()
        schedule()
    }

    fun onSpeechChanged(active: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { onSpeechChanged(active) }; return }
        if (!started || speaking == active) return
        speaking = active
        ArchiveAccess.sessions.onSpeechChanged(active)
        schedule()
    }

    /** Reader/details call before restoring a protected view after foreground recreation. */
    fun enforceBeforeDisplay() { if (started) ArchiveAccess.sessions.enforceTimeout() }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == KEY_TIMEOUT_MINUTES && sharedPreferences != null) {
            applyTimeout(sharedPreferences)
            ArchiveAccess.sessions.enforceTimeout()
            schedule()
        }
    }

    private fun applyTimeout(preferences: SharedPreferences) {
        val minutes = when (val stored = preferences.all[KEY_TIMEOUT_MINUTES]) {
            is String -> stored.toIntOrNull()
            is Number -> stored.toInt()
            else -> null
        }?.takeIf { it in ArchiveSessionPolicy.ALLOWED_MINUTES } ?: 5
        ArchiveAccess.sessions.policy.timeoutMinutes = minutes
    }

    private fun schedule() {
        handler.removeCallbacks(expiry)
        if (background && !speaking) ArchiveAccess.sessions.policy.remainingMillis()?.let { handler.postDelayed(expiry, it) }
    }
}
