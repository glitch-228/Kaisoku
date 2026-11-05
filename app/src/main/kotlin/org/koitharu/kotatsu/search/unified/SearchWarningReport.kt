package org.koitharu.kotatsu.search.unified

import org.koitharu.kotatsu.core.util.ext.redactSecrets
import org.koitharu.kotatsu.remote.library.LibraryDiagnostics

internal data class SearchWarningReport(val summary: String, val details: String)

/** Existing search failures are diagnostics, not a new exception at the warning button. */
internal fun searchWarningReport(errors: List<Throwable>): SearchWarningReport {
    val recent = errors.takeLast(16)
    return SearchWarningReport(
        summary = formatSearchWarnings(recent, 4096) {
            "${it.javaClass.simpleName}: ${LibraryDiagnostics.message(it).redactSecrets()}"
        },
        details = formatSearchWarnings(recent, 64 * 1024) { LibraryDiagnostics.text(it).redactSecrets() },
    )
}

/** Allocate space to each failure so one long stack cannot hide every later failed source. */
private fun formatSearchWarnings(errors: List<Throwable>, limit: Int, render: (Throwable) -> String): String {
    if (errors.isEmpty()) return ""
    val perError = (limit - 2 * (errors.size - 1)) / errors.size
    return buildString {
        errors.forEachIndexed { index, error ->
            if (index > 0) append("\n\n")
            val prefix = "${index + 1}. "
            val remaining = perError - prefix.length
            val text = render(error)
            append(prefix)
            if (text.length <= remaining) {
                append(text)
            } else {
                append(text.take(remaining - TRUNCATED_WARNING.length))
                append(TRUNCATED_WARNING)
            }
        }
    }
}

private const val TRUNCATED_WARNING = "\n… details truncated …"
