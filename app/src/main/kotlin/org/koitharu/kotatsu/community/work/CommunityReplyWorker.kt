package org.koitharu.kotatsu.community.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.community.data.CommunityRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CommunityReplyWorker(
    context: Context,
    params: WorkerParameters,
    private val community: CommunityRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!community.isReplyChecksAuthorized || !notificationsAllowed(applicationContext)) return Result.success()
        return try {
            val account = community.notificationScope
            community.pollNotifications { replies ->
                if (!community.isReplyChecksAuthorized || community.notificationScope != account ||
                    !notificationsAllowed(applicationContext)) false else {
                    if (replies.isNotEmpty()) {
                        val manager = NotificationManagerCompat.from(applicationContext)
                        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                            .setName(applicationContext.getString(R.string.community_notifications)).setShowBadge(true).build())
                        val intent = Intent(applicationContext, SettingsActivity::class.java).setAction(ACTION_INBOX)
                        val pending = PendingIntent.getActivity(applicationContext, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        // No title, author, source, reply text or identity on the lock screen.
                        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(applicationContext.getString(R.string.community_notifications))
                            .setContentText(applicationContext.getString(R.string.community_new_replies))
                            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                            .setVisibility(NotificationCompat.VISIBILITY_SECRET).build()
                        try {
                            manager.notify(NOTIFICATION_ID, notification)
                        } catch (_: SecurityException) {
                            // Permission may be revoked after the pre-request and delivery checks. Do not acknowledge.
                            return@pollNotifications false
                        }
                    }
                    true
                }
            }
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Result.retry() }
    }

    class Scheduler @Inject constructor(
        private val manager: WorkManager,
        private val community: CommunityRepository,
        private val settings: AppSettings,
    ) : PeriodicWorkScheduler {
        override suspend fun schedule() {
            if (!settings.isCommunityReplyChecksEnabled || !community.isReplyChecksAuthorized) { unschedule(); return }
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CommunityReplyWorker>(6, TimeUnit.HOURS)
                    .setInitialDelay(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
        }
        override suspend fun unschedule() { manager.cancelUniqueWork(NAME) }
        override suspend fun isScheduled(): Boolean = manager.getWorkInfosForUniqueWorkFlow(NAME).first().any { !it.state.isFinished }
    }

    companion object {
        const val ACTION_INBOX = "org.koitharu.kotatsu.action.COMMUNITY_INBOX"
        private const val NAME = "community_reply_checks"
        private const val CHANNEL = "community_replies"
        private const val NOTIFICATION_ID = 98721
        fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
}
