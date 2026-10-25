package org.koitharu.kotatsu.sync.extended.appserver

import android.content.ContentResolver
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.sync.drive.SyncBackend
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Factory registration is an explicit root-owned integration step. No automatic PUT retry is returned. */
class ServerExtendedWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: ServerExtendedSyncRepository,
    private val backend: SyncBackendSettings,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = ServerExtendedBackgroundPolicy.run {
        if (backend.backend != SyncBackend.KAISOKU_SERVER || !ContentResolver.getMasterSyncAutomatically()) {
            return@run Result.success()
        }
        val current = withContext(Dispatchers.IO) { repository.storage.store.configuration() }
        if (!current.enabled || !current.automatic || inputData.getString(GENERATION) != current.generation) {
            return@run Result.success()
        }
        when (repository.sync(ServerExtendedRunMode.SCHEDULED).status) {
            ServerExtendedStatus.SUCCESS, ServerExtendedStatus.DISABLED -> Result.success()
            else -> Result.failure()
        }
    } ?: Result.failure()

    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
        private val storage: ServerExtendedStorage,
        private val backend: SyncBackendSettings,
    ) : PeriodicWorkScheduler {
        override suspend fun schedule() {
            ServerExtendedBackgroundPolicy.run<Unit>(onFailure = { unschedule() }) {
                val settings = withContext(Dispatchers.IO) { storage.store.configuration() }
                if (backend.backend != SyncBackend.KAISOKU_SERVER || !settings.enabled || !settings.automatic) {
                    unschedule()
                    return@run
                }
                val request = PeriodicWorkRequestBuilder<ServerExtendedWorker>(settings.intervalMinutes.toLong(),
                    TimeUnit.MINUTES).setInitialDelay(settings.intervalMinutes.toLong(), TimeUnit.MINUTES)
                    .setConstraints(constraints(settings))
                    .setInputData(androidx.work.workDataOf(GENERATION to settings.generation)).build()
                workManager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request).await()
            }
        }

        suspend fun startOnLaunch() {
            ServerExtendedBackgroundPolicy.run<Unit>(onFailure = { unschedule() }) {
                val settings = withContext(Dispatchers.IO) { storage.store.configuration() }
                if (!settings.enabled || !settings.automatic || !settings.syncOnStart ||
                    backend.backend != SyncBackend.KAISOKU_SERVER) return@run
                val request = OneTimeWorkRequestBuilder<ServerExtendedWorker>().setConstraints(constraints(settings))
                    .setInputData(androidx.work.workDataOf(GENERATION to settings.generation)).build()
                workManager.enqueueUniqueWork(WORK_START, ExistingWorkPolicy.KEEP, request).await()
            }
        }

        override suspend fun unschedule() {
            // Attempt both cancellations even when one WorkManager operation fails ordinarily.
            for (name in listOf(WORK_PERIODIC, WORK_START)) {
                ServerExtendedBackgroundPolicy.run<Unit> { workManager.cancelUniqueWork(name).await() }
            }
        }

        override suspend fun isScheduled(): Boolean = ServerExtendedBackgroundPolicy.run(onFailure = { unschedule() }) {
            val settings = withContext(Dispatchers.IO) { storage.store.configuration() }
            if (backend.backend != SyncBackend.KAISOKU_SERVER || !settings.enabled || !settings.automatic) {
                unschedule()
                false
            } else workManager.awaitUniqueWorkInfoByName(WORK_PERIODIC).any { !it.state.isFinished }
        } ?: false

        private fun constraints(settings: ServerExtendedConfiguration) = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build()
    }

    companion object {
        private const val GENERATION = "extended_configuration_generation"
        private const val WORK_PERIODIC = "server-extended-periodic"
        private const val WORK_START = "server-extended-start"
    }
}
