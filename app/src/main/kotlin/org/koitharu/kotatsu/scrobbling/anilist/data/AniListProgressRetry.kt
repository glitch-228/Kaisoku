package org.koitharu.kotatsu.scrobbling.anilist.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Acknowledge only the progress sent by this attempt, preserving newer queued chapters. */
internal suspend fun retryAniListProgress(
	mutex: Mutex,
	readPending: () -> Int,
	clearPending: () -> Unit,
	push: suspend (Int) -> Unit,
): Boolean {
	val chapter = mutex.withLock { readPending() }
	if (chapter <= 0) return true
	push(chapter)
	return mutex.withLock {
		if (readPending() > chapter) {
			false
		} else {
			clearPending()
			true
		}
	}
}
