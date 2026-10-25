package org.koitharu.kotatsu.core.util.ext

import org.koitharu.kotatsu.core.exceptions.CopyableErrorDetails

private const val MAX_COPYABLE_ERROR_LENGTH = 64 * 1024
private val secretQueryParameter = Regex("(?i)([?&](?:key|api[_-]?key|access[_-]?token|token)=)[^&#\\s]+")
private val secretHeader = Regex("(?i)(authorization\\s*[:=]\\s*(?:(?:bearer|basic)\\s+)?)[^\\s,;]+")
private val secretJsonField = Regex("(?i)([\"']?(?:api[_-]?key|access[_-]?token|authorization)[\"']?\\s*:\\s*[\"']?)[^\"'\\s,}]+")
private val quotedSecret = Regex("""(?i)(["'](?:api[_-]?key|access[_-]?token|authorization|x-api-key)["']\s*:\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")
private val googleApiKey = Regex("\\bAIza[0-9A-Za-z_-]{20,}\\b")

/** Full copyable diagnostics for error actions, with credentials removed and a clipboard-size bound. */
fun Throwable.getCopyableErrorDetails(): String {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    var details: CopyableErrorDetails? = null
    repeat(32) {
        val error = current ?: return@repeat
        if (!visited.add(error)) { current = null; return@repeat }
        if (error is CopyableErrorDetails && details == null) details = error
        current = error.cause
    }
    val raw = details?.copyableEnclosingErrorDetails(this) ?: stackTraceToString()
    val safe = raw.redactSecrets()
    return if (safe.length <= MAX_COPYABLE_ERROR_LENGTH) safe else
        safe.take(MAX_COPYABLE_ERROR_LENGTH) + "\n… error details truncated …"
}

fun String.redactSecrets(): String = replace(quotedSecret) { "${it.groupValues[1]}\"[REDACTED]\"" }
    .replace(secretQueryParameter) { "${it.groupValues[1]}[REDACTED]" }
    .replace(secretHeader) { "${it.groupValues[1]}[REDACTED]" }
    .replace(secretJsonField) { "${it.groupValues[1]}[REDACTED]" }
    .replace(googleApiKey, "[REDACTED_GOOGLE_API_KEY]")
