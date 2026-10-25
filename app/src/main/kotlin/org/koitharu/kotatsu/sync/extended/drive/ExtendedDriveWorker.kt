package org.koitharu.kotatsu.sync.extended.drive

import android.content.ContentResolver
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import org.koitharu.kotatsu.sync.drive.SyncBackend
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Separate opt-in periodic work; the legacy worker never owns extended bytes, receipts or retries. */
class ExtendedDriveWorker(context: Context, params: WorkerParameters, private val repository: ExtendedDriveRepository) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val configuration = withContext(Dispatchers.IO) { repository.storage.configuration() }
        if (!ContentResolver.getMasterSyncAutomatically() || !configuration.enabled || !configuration.automatic ||
            inputData.getString(GENERATION) != configuration.generation) Result.success()
        else when (repository.syncAutomatically().status) {
            ExtendedDriveStatus.SUCCESS, ExtendedDriveStatus.DISABLED -> Result.success()
            else -> Result.failure()
        }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Result.failure() }

    class Scheduler @Inject constructor(private val workManager: WorkManager, private val storage: ExtendedDriveStorage,
        private val backend: SyncBackendSettings) : PeriodicWorkScheduler {
        override suspend fun schedule() {
            try {
                val configuration = withContext(Dispatchers.IO) { storage.configuration() }
                if (!configuration.enabled || !configuration.automatic || backend.backend != SyncBackend.GOOGLE_DRIVE) {
                    unschedule()
                    return
                }
                val interval = backend.intervalMinutes.coerceAtLeast(15).toLong()
                val request = PeriodicWorkRequestBuilder<ExtendedDriveWorker>(interval, TimeUnit.MINUTES)
                    .setInitialDelay(interval, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(
                        if (backend.isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true).build())
                    .setInputData(androidx.work.workDataOf(GENERATION to configuration.generation)).build()
                workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { unschedule() }
        }
        override suspend fun unschedule() {
            for (name in listOf(NAME, START)) {
                try { workManager.cancelUniqueWork(name).await() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { Unit }
            }
        }
        suspend fun startOnLaunch() {
            try {
                val configuration = withContext(Dispatchers.IO) { storage.configuration() }
                if (!configuration.enabled || !configuration.automatic || !backend.isSyncOnStart ||
                    backend.backend != SyncBackend.GOOGLE_DRIVE) return
                val request = OneTimeWorkRequestBuilder<ExtendedDriveWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(
                        if (backend.isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true).build())
                    .setInputData(androidx.work.workDataOf(GENERATION to configuration.generation)).build()
                workManager.enqueueUniqueWork(START, ExistingWorkPolicy.KEEP, request).await()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { unschedule() }
        }
        override suspend fun isScheduled(): Boolean = try {
            val configuration = withContext(Dispatchers.IO) { storage.configuration() }
            if (backend.backend != SyncBackend.GOOGLE_DRIVE || !configuration.enabled || !configuration.automatic) {
                unschedule()
                false
            } else workManager.awaitUniqueWorkInfoByName(NAME).any { !it.state.isFinished }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { unschedule(); false }
    }
    companion object {
        private const val NAME = "extended-drive-sync"
        private const val START = "extended-drive-startup"
        private const val GENERATION = "extended_drive_configuration_generation"
    }
}
