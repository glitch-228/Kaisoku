package org.koitharu.kotatsu.scrobbling.common.data

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.completedTrackerChapter
import org.koitharu.kotatsu.scrobbling.common.work.TrackerProgressWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerSyncCoordinator @Inject constructor(
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    private val db: MangaDatabase,
    private val workManager: WorkManager,
    private val rawProgress: javax.inject.Provider<org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressCoordinator>,
) {
    suspend fun onChapterCompleted(manga: Manga, chapterId: Long) {
        val handled = try { rawProgress.get().onChapterCompleted(manga.id, chapterId) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { error.printStackTraceDebug(); emptySet<String>() }
        val chapter = completedTrackerChapter(manga, chapterId) ?: return
        for (scrobbler in scrobblers) {
            if (scrobbler.scrobblerService.name in handled) continue
            val owner = scrobbler.trackerRepository.cachedUser?.id ?: continue
            if (!scrobbler.isEnabled) continue
            val storage = scrobbler.trackerRepository.storage
            try {
                val session = storage.snapshot()
                storage.withSession(session) {
                    val link = db.getScrobblingDao().find(scrobbler.scrobblerService.id, manga.id, owner) ?: return@withSession
                    val pending = PendingTrackerProgress(owner, manga.id, link.targetId, link.id, chapter, System.currentTimeMillis())
                    if (!storage.progress.enqueue(pending)) return@withSession
                    schedule(scrobbler, owner, manga.id)
                }
            } catch (_: TrackerSessionChangedException) {
                // Logout or account switching cannot interrupt the reader or other trackers.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTraceDebug()
            }
        }
    }

    init {
        processLifecycleScope.launch(Dispatchers.IO) {
            scrobblers.forEach { tracker -> launch {
                tracker.trackerRepository.storage.sessionChanges.collect {
                    val owner = tracker.trackerRepository.cachedUser?.id
                    if (owner != null && tracker.isEnabled) {
                        tracker.trackerRepository.storage.progress.all(owner).forEach { pending ->
                            schedule(tracker, owner, pending.mangaId)
                        }
                    }
                }
            } }
        }
    }

    private suspend fun schedule(scrobbler: Scrobbler, owner: Long, mangaId: Long) {
        val request = OneTimeWorkRequestBuilder<TrackerProgressWorker>()
            .setInputData(Data.Builder().putInt("service", scrobbler.scrobblerService.id)
                .putLong("account", owner).putLong("manga", mangaId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // An appended successor closes the enqueue/worker-finish race. It reads the coalesced value.
        try {
            workManager.enqueueUniqueWork("tracker_progress_${scrobbler.scrobblerService.id}_${owner}_${mangaId}",
                ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The durable record is rescheduled after restart or a credential change.
            e.printStackTraceDebug()
        }
    }
}
