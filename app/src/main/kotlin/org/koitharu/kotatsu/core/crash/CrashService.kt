package org.koitharu.kotatsu.core.crash

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug

class CrashService : Service() {

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val report = intent?.toCrashReportData()
		if (report == null) {
			stopSelf(startId)
			return START_NOT_STICKY
		}
		runCatching {
			startForeground(report)
			startActivity(
				CrashDialogActivity.newIntent(this, report)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			)
		}.onFailure {
			it.printStackTraceDebug()
		}
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf(startId)
		return START_NOT_STICKY
	}

	private fun startForeground(report: CrashReportData) {
		createNotificationChannel()
		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			createNotification(report),
			foregroundServiceType(),
		)
	}

	private fun createNotification(report: CrashReportData) = NotificationCompat.Builder(this, CHANNEL_ID)
		.setSmallIcon(android.R.drawable.stat_notify_error)
		.setContentTitle(getString(R.string.error_occurred))
		.setContentText(getString(R.string.crash_notification_text))
		.setContentIntent(createCrashDialogIntent(report))
		.setCategory(NotificationCompat.CATEGORY_ERROR)
		.setLocalOnly(true)
		.setOngoing(true)
		.build()

	private fun createCrashDialogIntent(report: CrashReportData): PendingIntent? {
		return PendingIntentCompat.getActivity(
			this,
			0,
			CrashDialogActivity.newIntent(this, report),
			PendingIntent.FLAG_UPDATE_CURRENT,
			false,
		)
	}

	private fun createNotificationChannel() {
		val channel = NotificationChannelCompat.Builder(
			CHANNEL_ID,
			NotificationManagerCompat.IMPORTANCE_LOW,
		)
			.setName(getString(R.string.crash_report_channel))
			.build()
		NotificationManagerCompat.from(this).createNotificationChannel(channel)
	}

	private fun foregroundServiceType(): Int {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
		} else {
			0
		}
	}

	companion object {

		private const val CHANNEL_ID = "crash_report"
		private const val NOTIFICATION_ID = 0x43525348

		internal const val EXTRA_TITLE = "crash.title"
		internal const val EXTRA_BODY = "crash.body"
		internal const val EXTRA_ISSUE_URL = "crash.issue_url"
		internal const val EXTRA_DEGRADED = "crash.degraded"

		internal fun start(context: Context, report: CrashReportData) {
			val intent = newIntent(context, report)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				ContextCompat.startForegroundService(context, intent)
			} else {
				context.startService(intent)
			}
		}

		internal fun newIntent(context: Context, report: CrashReportData): Intent {
			return Intent(context, CrashService::class.java)
				.putCrashReportData(report)
		}
	}
}

internal fun Intent.putCrashReportData(report: CrashReportData): Intent {
	return putExtra(CrashService.EXTRA_TITLE, report.title)
		.putExtra(CrashService.EXTRA_BODY, report.body)
		.putExtra(CrashService.EXTRA_ISSUE_URL, report.issueUrl)
		.putExtra(CrashService.EXTRA_DEGRADED, report.isDegraded)
}

internal fun Intent.toCrashReportData(): CrashReportData? {
	val title = getStringExtra(CrashService.EXTRA_TITLE) ?: return null
	val body = getStringExtra(CrashService.EXTRA_BODY) ?: return null
	val issueUrl = getStringExtra(CrashService.EXTRA_ISSUE_URL) ?: return null
	val isDegraded = getBooleanExtra(CrashService.EXTRA_DEGRADED, false)
	return CrashReportData(title, body, issueUrl, isDegraded)
}
