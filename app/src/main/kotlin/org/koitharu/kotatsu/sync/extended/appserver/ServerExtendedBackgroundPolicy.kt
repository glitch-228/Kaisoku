package org.koitharu.kotatsu.sync.extended.appserver

import kotlinx.coroutines.CancellationException

/** Background failures stop work without rewriting corrupt state; cancellation always retains its semantics. */
internal object ServerExtendedBackgroundPolicy {
    suspend fun <T> run(onFailure: suspend () -> Unit = {}, action: suspend () -> T): T? = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        try { onFailure() } catch (error: CancellationException) { throw error } catch (_: Exception) { Unit }
        null
    }
}
