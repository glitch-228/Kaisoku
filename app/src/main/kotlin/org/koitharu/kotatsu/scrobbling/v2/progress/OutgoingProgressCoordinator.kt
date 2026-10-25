package org.koitharu.kotatsu.scrobbling.v2.progress

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OutgoingProgressCoordinator @Inject constructor(
    private val engine: OutgoingProgressEngine,
    private val access: TrackerOutgoingAccess,
    private val work: WorkManager,
) {
    private var observation: Job? = null

    @Synchronized
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (observation?.isActive == true) return
        observation = scope.launch(Dispatchers.IO) {
            engine.changes.debounce(250).collect {
                try { schedule() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { error.printStackTraceDebug() }
            }
        }
    }

    /** Local enqueue only. The caller retains its existing numeric workflow for every unhandled provider. */
    suspend fun onChapterCompleted(mangaId: Long, chapterId: Long): Set<String> {
        val enabled = engine.view().consents.filter { it.automatic }
        if (enabled.isEmpty()) return emptySet()
        val handled = hashSetOf<String>()
        for (account in engine.accounts()) {
            if (enabled.none { it.connection.sameSession(account.connection) }) continue
            try {
                val event = access.completion(account.connection, mangaId, chapterId) ?: continue
                if (engine.enqueueCompletion(account.connection, mangaId, event.second) != OutgoingProgressEnqueue.NOT_ENABLED) {
                    handled += account.connection.provider
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { error.printStackTraceDebug() }
        }
        if (handled.isNotEmpty()) schedule()
        return handled
    }

    suspend fun ownsLegacyQueue(provider: String, localAccount: Long, mangaId: Long): Boolean {
        val consent = engine.view().consents.singleOrNull {
            it.automatic && it.connection.provider == provider
        } ?: return false
        val account = engine.accounts().singleOrNull { it.connection.sameSession(consent.connection) } ?: return false
        val snapshot = access.snapshot(account.connection, mangaId) ?: return false
        return access.ownsLegacyAccount(account.connection, localAccount) && access.hasExactCompletion(mangaId) &&
            snapshot.mediaId.isNotBlank()
    }

    suspend fun schedule() {
        if (engine.view().lanes.none { it.primary?.state == OutgoingProgressState.QUEUED }) return
        val request = OneTimeWorkRequestBuilder<OutgoingProgressWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
    }

    private companion object { const val WORK_NAME = "tracker_raw_progress_v1" }
}

/** Bounded dispatch. Unknown sends remain readback-only; retries can dispatch only definitely queued work. */
class OutgoingProgressWorker(
    context: Context,
    parameters: WorkerParameters,
    private val engine: OutgoingProgressEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        engine.dispatch()
        if (engine.view().lanes.any { it.primary?.state == OutgoingProgressState.QUEUED }) Result.retry() else Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Result.failure() }
}
