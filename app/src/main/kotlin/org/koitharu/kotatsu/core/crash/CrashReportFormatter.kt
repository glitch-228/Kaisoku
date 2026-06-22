package org.koitharu.kotatsu.core.crash

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.activityManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CrashReportFormatter {

	private const val ISSUE_TEMPLATE = "report_bug.yml"
	private const val MAX_TITLE_LENGTH = 120
	private const val MAX_FIELD_LENGTH = 3_000
	private const val MAX_REPORT_LENGTH = 64 * 1024
	private const val MAX_ISSUE_BODY_LENGTH = 6_000

	private const val FALLBACK_ISSUE_URL = "https://github.com/glitch-228/Kaisoku/issues/new"

	/**
	 * Never throws and never returns null: the full report is built off the crashing process, which —
	 * especially for an OutOfMemoryError — may be unable to allocate, so any failure degrades to a
	 * minimal report (title + generic issue link) so the user is always offered a way to report.
	 */
	fun buildSafe(context: Context, throwable: Throwable, threadName: String?): CrashReportData {
		return runCatching {
			build(context, throwable, threadName)
		}.getOrElse {
			buildDegraded(context, throwable)
		}
	}

	private fun buildDegraded(context: Context, throwable: Throwable): CrashReportData {
		val summary = runCatching { buildSummary(throwable) }.getOrDefault("Unknown error")
		val title = "Crash: $summary".limit(MAX_TITLE_LENGTH)
		val issueUrl = runCatching { degradedIssueUrl(context, title) }.getOrDefault(FALLBACK_ISSUE_URL)
		return CrashReportData(
			title = title,
			body = "Kaisoku crashed but the full crash report could not be collected.",
			issueUrl = issueUrl,
			isDegraded = true,
		)
	}

	private fun degradedIssueUrl(context: Context, title: String): String {
		val baseUrl = context.getString(R.string.url_error_report)
			.trim()
			.removeSuffix("/choose")
			.ifEmpty { FALLBACK_ISSUE_URL }
		return Uri.parse(baseUrl).buildUpon()
			.appendQueryParameter("template", ISSUE_TEMPLATE)
			.appendQueryParameter("title", title)
			.build()
			.toString()
	}

	fun build(context: Context, throwable: Throwable, threadName: String?): CrashReportData {
		val summary = buildSummary(throwable)
		val title = "Crash: ${summary}".limit(MAX_TITLE_LENGTH)
		val stackTrace = throwable.stackTraceToString().limit(MAX_REPORT_LENGTH / 2)
		val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
		val androidVersion = buildAndroidVersion()
		val device = buildDevice()
		// Captured here, in the crashing process before it is killed, so the figures reflect the real
		// heap at crash time — the only useful signal for OutOfMemoryError reports.
		val memory = buildMemory(context)
		val steps = buildReproduceSteps(stackTrace, memory)
		val body = buildBody(
			summary = summary,
			steps = steps,
			appVersion = appVersion,
			androidVersion = androidVersion,
			device = device,
			threadName = threadName,
			processName = context.currentProcessName(),
			memory = memory,
			stackTrace = stackTrace,
		).limit(MAX_REPORT_LENGTH)
		val issueUrl = buildIssueUrl(
			context = context,
			title = title,
			summary = summary,
			steps = steps,
			appVersion = appVersion,
			androidVersion = androidVersion,
			device = device,
			body = body,
		)
		return CrashReportData(
			title = title,
			body = body,
			issueUrl = issueUrl,
		)
	}

	private fun buildIssueUrl(
		context: Context,
		title: String,
		summary: String,
		steps: String,
		appVersion: String,
		androidVersion: String,
		device: String,
		body: String,
	): String {
		val baseUrl = context.getString(R.string.url_error_report)
			.trim()
			.removeSuffix("/choose")
			.ifEmpty { "https://github.com/glitch-228/Kaisoku/issues/new" }
		return Uri.parse(baseUrl).buildUpon()
			.appendQueryParameter("template", ISSUE_TEMPLATE)
			.appendQueryParameter("title", title)
			.appendQueryParameter("summary", summary.limit(MAX_FIELD_LENGTH))
			.appendQueryParameter("reproduce-steps", steps.limit(MAX_FIELD_LENGTH))
			.appendQueryParameter("kaisoku-version", appVersion)
			.appendQueryParameter("android-version", androidVersion)
			.appendQueryParameter("device", device)
			.appendQueryParameter("body", body.limit(MAX_ISSUE_BODY_LENGTH))
			.build()
			.toString()
	}

	private fun buildSummary(throwable: Throwable): String {
		val type = throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name }
		val message = throwable.message?.oneLine()
		return if (message.isNullOrBlank()) {
			type
		} else {
			"$type: $message"
		}
	}

	// Built line-by-line on purpose: a raw `"""…""".trimIndent()` here would interpolate
	// the multi-line `$stackTrace` content, which contains its own zero-indent lines (e.g.
	// "Caused by:"). That dropped `trimIndent`'s computed minIndent to 0 and left the leading
	// tabs in place on the header lines, which is what made #2/#3 render with garbled tabs.
	private fun buildReproduceSteps(stackTrace: String, memory: String): String = buildString {
		appendLine("The app crashed unexpectedly.")
		appendLine()
		appendLine("Please describe what you were doing before the crash.")
		appendLine()
		appendLine("Stack trace:")
		appendLine()
		appendLine("```text")
		appendLine(stackTrace)
		appendLine("```")
		appendLine()
		appendLine("Memory at crash:")
		appendLine()
		appendLine("```text")
		appendLine(memory)
		append("```")
	}

	private fun buildBody(
		summary: String,
		steps: String,
		appVersion: String,
		androidVersion: String,
		device: String,
		threadName: String?,
		processName: String?,
		memory: String,
		stackTrace: String,
	): String = buildString {
		appendLine("### Brief summary")
		appendLine(summary)
		appendLine()
		appendLine("### Steps to reproduce")
		appendLine(steps)
		appendLine()
		appendLine("### Kaisoku version")
		appendLine(appVersion)
		appendLine()
		appendLine("### Android version")
		appendLine(androidVersion)
		appendLine()
		appendLine("### Device")
		appendLine(device)
		appendLine()
		appendLine("### Runtime")
		appendLine("Time: ${formatNow()}")
		appendLine("Process: ${processName ?: "unknown"}")
		appendLine("Thread: ${threadName ?: "unknown"}")
		appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
		appendLine()
		appendLine("### Memory")
		appendLine("```text")
		appendLine(memory)
		appendLine("```")
		appendLine()
		appendLine("### Stack trace")
		appendLine("```text")
		appendLine(stackTrace)
		append("```")
	}

	// This runs inside the crash handler, often right after an OutOfMemoryError, so it must never
	// throw: any failure (including a recursive OOM while allocating these strings) falls back to a
	// constant so the rest of the report — stack trace, device — is still produced as before. All
	// reads here are O(1) counter/binder calls, cheap even on old/low-RAM devices.
	private fun buildMemory(context: Context): String = try {
		val mb = 1024L * 1024L
		val runtime = Runtime.getRuntime()
		val javaUsed = (runtime.totalMemory() - runtime.freeMemory()) / mb
		val javaTotal = runtime.totalMemory() / mb
		val javaMax = runtime.maxMemory() / mb
		val nativeUsed = Debug.getNativeHeapAllocatedSize() / mb
		val nativeTotal = Debug.getNativeHeapSize() / mb
		val am = context.activityManager
		val memoryClass = am?.memoryClass ?: -1
		val largeMemoryClass = am?.largeMemoryClass ?: -1
		// Isolated: a flaky binder/service should drop only the system line, not the heap figures.
		val info = runCatching { ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) } }.getOrNull()
		buildString {
			appendLine("Java heap: $javaUsed/$javaTotal MB used, max $javaMax MB")
			appendLine("Native heap: $nativeUsed/$nativeTotal MB")
			append("Heap class: $memoryClass MB (large $largeMemoryClass MB)")
			if (info != null) {
				appendLine()
				append("System: ${info.availMem / mb} MB free of ${info.totalMem / mb} MB")
				if (info.lowMemory) append(" (LOW)")
			}
		}
	} catch (_: Throwable) {
		// No interpolation here — the failure path must not allocate (it may be handling an OOM).
		"unavailable"
	}

	private fun buildAndroidVersion(): String {
		return "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.VERSION.CODENAME})"
	}

	private fun buildDevice(): String {
		return listOf(
			Build.MANUFACTURER,
			Build.BRAND,
			Build.MODEL,
			"device=${Build.DEVICE}",
			"product=${Build.PRODUCT}",
		).joinToString(separator = " ") { it.ifBlank { "unknown" } }
	}

	private fun formatNow(): String {
		return SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
	}

	private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

	private fun String.limit(maxLength: Int): String {
		return if (length <= maxLength) this else take(maxLength - 16).trimEnd() + "\n...[truncated]"
	}
}

internal data class CrashReportData(
	val title: String,
	val body: String,
	val issueUrl: String,
	val isDegraded: Boolean = false,
)
