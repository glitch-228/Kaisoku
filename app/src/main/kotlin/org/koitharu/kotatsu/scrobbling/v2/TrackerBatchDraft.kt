package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TrackerBatchSelection(
    val localMangaId: Long,
    val mediaId: String,
    val confirmed: Boolean,
    val allowRemoteCreate: Boolean = false,
    val categoryId: Long? = null,
    val expectedLinkRevision: Long? = null,
    val expectedSource: String? = null,
    val expectedUrl: String? = null,
)

sealed interface TrackerBatchOutcome {
    data class Applied(val selection: TrackerBatchSelection, val reference: TrackerReference) : TrackerBatchOutcome
    data class Pending(val selection: TrackerBatchSelection, val reason: String) : TrackerBatchOutcome
    data class Failed(val selection: TrackerBatchSelection, val error: Exception) : TrackerBatchOutcome
}

/** Explicit per-item confirmation is mandatory; no operation is inferred from search confidence. */
class TrackerBatchDraft(
    val connection: TrackerConnection,
    selections: List<TrackerBatchSelection>,
) {
    val selections = selections.distinctBy { it.localMangaId }

    init {
        require(selections.size <= BulkTrackerMatchPolicy.MAX_BATCH) { "Choose at most 100 titles per batch" }
        require(selections.all { it.localMangaId != 0L && it.mediaId.isNotBlank() })
    }

    private val gate = Mutex()
    private val completed = linkedMapOf<Long, TrackerBatchOutcome.Applied>()

    suspend fun apply(
        provider: TrackerProvider,
        commitLocal: suspend (TrackerBatchSelection, TrackerRemoteEntry) -> Unit,
        onOutcome: (TrackerBatchOutcome) -> Unit = {},
    ): List<TrackerBatchOutcome> = gate.withLock {
        require(provider.key == connection.owner.provider)
        val outcomes = mutableListOf<TrackerBatchOutcome>()
        for (selection in selections) {
            provider.checkConnection(connection)
            val previous = completed[selection.localMangaId]
            if (previous != null) {
                outcomes += previous
                continue
            }
            val outcome = if (!selection.confirmed) {
                TrackerBatchOutcome.Pending(selection, "Confirm this title and edition before linking")
            } else try {
                val existing = provider.entry(connection, selection.mediaId)
                val remote = when {
                    existing != null -> existing
                    selection.allowRemoteCreate -> provider.patch(connection, selection.mediaId,
                        TrackerPatch.statusOnly("PLANNING"), allowCreate = true)
                    else -> null
                }
                if (remote == null) TrackerBatchOutcome.Pending(selection,
                    "Confirm adding this title to the remote list")
                else {
                    provider.checkConnection(connection)
                    require(remote.reference.owner == connection.owner && remote.reference.mediaId == selection.mediaId)
                    commitLocal(selection, remote)
                    TrackerBatchOutcome.Applied(selection, remote.reference).also {
                        completed[selection.localMangaId] = it
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackerBatchOutcome.Failed(selection, e)
            }
            outcomes += outcome
            onOutcome(outcome)
            if (outcome is TrackerBatchOutcome.Failed && outcome.error is TrackerProviderError &&
                outcome.error.status in setOf(401, 403, 429)) break
        }
        outcomes
    }
}
