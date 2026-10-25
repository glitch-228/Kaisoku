package org.koitharu.kotatsu.reader.ui.pager

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import org.koitharu.kotatsu.core.prefs.AppSettings

/** Retain existing decoded holders; never allocate another full-page bitmap or persistent cache. */
internal fun retainedPageCount(enabled: Boolean, lowRam: Boolean, heapMiB: Int, default: Int): Int = when {
    !enabled || lowRam || heapMiB < 256 -> default
    heapMiB < 384 -> default + 1
    else -> default + 2
}

internal fun Context.retainedPageCount(default: Int): Int {
    val memory = getSystemService<ActivityManager>() ?: return default
    val enabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        .getBoolean(AppSettings.KEY_EXTRA_PAGE_RETENTION, false)
    return retainedPageCount(enabled, memory.isLowRamDevice, memory.memoryClass, default)
}
