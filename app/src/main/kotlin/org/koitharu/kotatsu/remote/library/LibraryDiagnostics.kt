package org.koitharu.kotatsu.remote.library

import java.io.IOException

/** Preserves exception/cause/stack details while removing URL credentials and capability queries. */
object LibraryDiagnostics {
    fun text(error: Throwable): String = redact(error.stackTraceToString()).let {
        if (it.length <= 64 * 1024) it else it.take(64 * 1024) + "\n… error details truncated …"
    }
    fun message(error: Throwable): String = redact(error.message ?: error.javaClass.simpleName).take(2048)

    internal fun redact(value: String): String {
        val urls = Regex("(?i)https?://[^\\s\\\"'<>]+")
        var safe = urls.replace(value.replace("\\/", "/")) { match ->
            val raw = match.value
            val head = raw.substringBefore('?').substringBefore('#')
            val schemeEnd = head.indexOf("://") + 3
            val authorityEnd = head.indexOf('/', schemeEnd).takeIf { it >= 0 } ?: head.length
            val authority = head.substring(schemeEnd, authorityEnd).substringAfterLast('@')
            head.take(schemeEnd) + authority + head.substring(authorityEnd) +
                if ('?' in raw || '#' in raw) "?[redacted]" else ""
        }
        val quoted = Regex("""(?i)(["'](?:password|access[_-]?token|refresh[_-]?token|api[_-]?key|token|secret|authorization|proxy-authorization|cookie|set-cookie)["']\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")
        safe = quoted.replace(safe) { it.groupValues[1] + "\"[redacted]\"" }
        safe = Regex("(?im)(authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*[^\\r\\n]+")
            .replace(safe, "$1: [redacted]")
        safe = Regex("(?i)(password|access_token|refresh_token|api_key|token|secret)\\s*[:=]\\s*[^\\s,;]+")
            .replace(safe, "$1=[redacted]")
        return safe
    }
}

/** Keep causes for useful reports while ensuring generic readers inherit the private diagnostic policy. */
internal fun IOException.asLibraryFailure(): RemoteLibraryException = this as? RemoteLibraryException
    ?: RemoteLibraryException("The library request could not be completed", this)
