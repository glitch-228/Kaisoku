package org.koitharu.kotatsu.core.crash

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

internal class GlobalCrashHandler private constructor(
	private val application: Application,
) : Thread.UncaughtExceptionHandler {

	override fun uncaughtException(thread: Thread, throwable: Throwable) {
		if (isHandling.compareAndSet(false, true)) {
			showCollectingToast()
			// buildSafe never throws/returns null, so the user is always offered a way to report —
			// even when an OutOfMemoryError leaves the crashing process unable to build a full report.
			val report = CrashReportFormatter.buildSafe(application, throwable, thread.name)
			runCatching {
				CrashService.start(application, report)
			}.onFailure {
				it.printStackTraceDebug()
				startCrashDialog(report)
			}
			runCatching {
				Thread.sleep(CRASH_SERVICE_START_DELAY_MS)
			}
		}
		Process.killProcess(Process.myPid())
		exitProcess(10)
	}

	/**
	 * Best-effort "we're working on it, don't force-close" hint while the report is built. Text toasts
	 * are system-rendered on Android 11+, so they show even though the crashing thread's looper is
	 * blocked. Fully guarded: it must never add a failure on top of the crash being handled.
	 */
	private fun showCollectingToast() {
		runCatching {
			val show = Runnable {
				runCatching {
					Toast.makeText(application, R.string.crash_collecting, Toast.LENGTH_LONG).show()
				}
			}
			if (Looper.myLooper() != null) {
				show.run()
			} else {
				Handler(Looper.getMainLooper()).post(show)
			}
		}
	}

	private fun startCrashDialog(report: CrashReportData) {
		runCatching {
			application.startActivity(
				CrashDialogActivity.newIntent(application, report)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	companion object {

		private const val CRASH_SERVICE_START_DELAY_MS = 350L
		private val isInstalled = AtomicBoolean(false)
		private val isHandling = AtomicBoolean(false)

		fun install(application: Application) {
			if (!isInstalled.compareAndSet(false, true)) {
				return
			}
			Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(application))
		}
	}
}
