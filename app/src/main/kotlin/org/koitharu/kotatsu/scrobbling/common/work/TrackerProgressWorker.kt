package org.koitharu.kotatsu.scrobbling.common.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.scrobbling.common.data.TrackerHttpException
import org.koitharu.kotatsu.scrobbling.common.data.TrackerLibraryRepository
import org.koitharu.kotatsu.scrobbling.common.data.TrackerSessionChangedException
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import java.io.IOException

@Keep
class TrackerProgressWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val library: TrackerLibraryRepository,
    private val rawProgress: javax.inject.Provider<org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressCoordinator>,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serviceId = inputData.getInt("service", 0)
        val account = inputData.getLong("account", 0)
        val manga = inputData.getLong("manga", 0)
        val service = org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.entries.firstOrNull { it.id == serviceId }
            ?: return Result.failure()
        val tracker = library.scrobbler(service)
        val store = tracker.trackerRepository.storage.progress
        while (true) {
            val pending = store.read(account, manga) ?: return Result.success()
            if (!tracker.isEnabled || tracker.trackerRepository.cachedUser?.id != account) return Result.success()
            try {
                if (rawProgress.get().ownsLegacyQueue(service.name, account, manga)) {
                    store.discard(pending)
                    return Result.success()
                }
                if (!tracker.syncProgress(pending)) {
                    store.discard(pending)
                    return Result.success()
                }
                store.acknowledge(pending)
                library.invalidate(tracker.scrobblerService, account)
            } catch (e: TrackerSessionChangedException) {
                return Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrackerHttpException) {
                if (e.code == 429 || e.code >= 500 || e.code == 408) return Result.retry()
                store.fail(pending, e.message.orEmpty())
                return Result.failure()
            } catch (e: org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException) {
                store.fail(pending, e.message.orEmpty())
                return Result.failure()
            } catch (e: IOException) {
                return Result.retry()
            } catch (e: Throwable) {
                store.fail(pending, e.message.orEmpty())
                return Result.failure()
            }
        }
    }
}
