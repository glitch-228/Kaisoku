package org.koitharu.kotatsu.backups.webdav

import android.content.Context
import androidx.annotation.Keep
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** WorkManager's existing reflective fallback constructs this worker; no password enters WorkData. */
@Keep
class WebDavBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, WebDavWorkerEntryPoint::class.java)
        val connection = dependencies.configuration().connection() ?: return Result.success()
        val expected = inputData.getString(REVISION_KEY)
        if (connection.scheduleHours <= 0 || expected != connection.revision) return Result.success()
        return try {
            val result = dependencies.repository().upload(expected)
            Result.success(workDataOf("uploaded" to result.backup.name,
                "retentionWarning" to result.retainedWarning.orEmpty()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: WebDavPublicationUncertain) {
            // Preserve the pending journal; do not blindly republish a backup after an unknown write result.
            Result.failure(workDataOf("error" to "Publication needs readback; open WebDAV backups before retrying"))
        } catch (e: WebDavFailure) {
            if (e.transient && runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to e.message.orEmpty().take(512)))
        } catch (_: IOException) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to "WebDAV backup could not finish; open its settings to retry"))
        }
    }

    companion object {
        internal const val REVISION_KEY = "connectionRevision"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WebDavWorkerEntryPoint {
    fun configuration(): WebDavConfigurationStore
    fun repository(): WebDavBackupRepository
}

@Singleton
class WebDavScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configuration: WebDavConfigurationStore,
) {
    suspend fun schedule() {
        val manager = WorkManager.getInstance(context)
        // A revision edit cancels the old worker as well as invalidating each repository request boundary.
        manager.cancelUniqueWork(WORK_NAME).await()
        val connection = configuration.connection() ?: return
        if (connection.scheduleHours <= 0) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (connection.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(connection.scheduleHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(connection.scheduleHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInputData(workDataOf(WebDavBackupWorker.REVISION_KEY to connection.revision))
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
    }

    companion object {
        private const val WORK_NAME = "webdav-native-backups-beta"
    }
}
