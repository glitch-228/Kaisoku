package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional outgoing-review guard. Existing independently confirmed legacy editing contracts are unchanged. */
internal class TrackerProgressWritePolicy(
    val desired: TrackerRawProgress,
    val expected: TrackerRawProgress?,
    val allowLower: Boolean,
    val allowUnknown: Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TrackerProgressWritePolicy>
}

internal suspend fun <T> withTrackerProgressWritePolicy(
    desired: TrackerRawProgress,
    expected: TrackerRawProgress?,
    allowLower: Boolean,
    allowUnknown: Boolean,
    block: suspend () -> T,
): T = withContext(TrackerProgressWritePolicy(desired, expected, allowLower, allowUnknown)) { block() }

/** Invoke after the provider's final fresh entry read, never by performing HTTP inside a request guard. */
internal suspend fun checkTrackerProgressWrite(before: TrackerRemoteEntry?) {
    val policy = currentCoroutineContext()[TrackerProgressWritePolicy] ?: return
    requireNotNull(before) { "This title is no longer on the tracker list; progress cannot create it" }
    checkTrackerProgressValues(policy.desired, policy.expected, before.progress, policy.allowLower, policy.allowUnknown)
}

internal fun checkTrackerProgressValues(desired: TrackerRawProgress, expected: TrackerRawProgress?,
    current: TrackerRawProgress?, allowLower: Boolean, allowUnknown: Boolean) {
    require(desired.unit == "chapter") { "Unsupported tracker progress unit" }
    if (allowLower || allowUnknown) {
        check(current?.unit == expected?.unit &&
            (desired.number == null || current?.number == expected?.number) &&
            (desired.volume == null || current?.volume == expected?.volume) &&
            (desired.entryId == null || current?.entryId == expected?.entryId)) {
            "Remote progress changed since review; prepare a new preview"
        }
    }
    if (desired.entryId != null) {
        require(allowUnknown) { "Chapter entry identities require an explicit reviewed write" }
        return
    }
    fun component(next: String?, old: String?) {
        if (next == null) return
        val number = requireNotNull(TrackerRawProgress(next).numericNumber()) { "Invalid requested progress" }
        require(number.signum() >= 0)
        val previous = old?.let { TrackerRawProgress(it).numericNumber() }
            ?.takeIf { current?.unit == "chapter" && it.signum() >= 0 }
        if (previous == null) require(allowUnknown) { "Unknown remote progress needs explicit review" }
        else require(allowLower || number >= previous) { "Newer remote progress was preserved; review the value again" }
    }
    component(desired.number, current?.number)
    component(desired.volume, current?.volume)
}
