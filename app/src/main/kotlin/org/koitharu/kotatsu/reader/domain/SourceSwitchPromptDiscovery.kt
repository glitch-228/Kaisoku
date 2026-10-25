package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Both sides of discovery must still belong to the same title and prompt-policy revision. */
internal suspend fun <T> discoverSourceSwitchPrompt(
    expectedRevision: Long,
    currentRevision: () -> Long,
    isEligible: () -> Boolean,
    discover: suspend () -> T,
): T? {
    if (expectedRevision != currentRevision() || !isEligible()) return null
    val result = discover()
    currentCoroutineContext().ensureActive()
    return result.takeIf { expectedRevision == currentRevision() && isEligible() }
}
