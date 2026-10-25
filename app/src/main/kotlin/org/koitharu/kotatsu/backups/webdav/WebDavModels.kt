package org.koitharu.kotatsu.backups.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.core.exceptions.CopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.redactSecrets
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** URLs are validated before OkHttp removes dot segments or interprets encoded separators. */
class WebDavEndpoint private constructor(val url: HttpUrl) {
    fun child(name: String): HttpUrl {
        require(validName(name)) { "Invalid WebDAV backup name" }
        return url.newBuilder().addPathSegment(name).build()
    }

    fun childName(href: String): String? {
        if (href.length > 4096 || unsafePath(href.substringBefore('?').substringBefore('#'))) return null
        val resolved = url.resolve(href) ?: return null
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty() || resolved.query != null ||
            resolved.fragment != null || resolved.scheme != url.scheme || resolved.host != url.host ||
            resolved.port != url.port) return null
        val base = url.encodedPath
        if (!resolved.encodedPath.startsWith(base)) return null
        val remainder = resolved.encodedPath.removePrefix(base)
        if (remainder.isEmpty() || '/' in remainder) return null
        return resolved.pathSegments.lastOrNull()?.takeIf(::validName)
    }

    fun isCollection(href: String): Boolean = url.resolve(href)?.let {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
            it.scheme == url.scheme && it.host == url.host && it.port == url.port &&
            it.encodedPath.trimEnd('/') == url.encodedPath.trimEnd('/')
    } == true

    override fun toString(): String = url.toString()

    companion object {
        fun parse(value: String, allowCleartextLan: Boolean = false): WebDavEndpoint {
            val trimmed = value.trim()
            require(trimmed.length in 8..4096 && !unsafePath(trimmed)) { "Invalid WebDAV collection URL" }
            val parsed = trimmed.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null &&
                parsed.fragment == null) { "Use a collection URL without credentials, query or fragment" }
            require(parsed.isHttps || (allowCleartextLan && isLanHost(parsed.host))) {
                "WebDAV requires HTTPS; cleartext is available only for an explicitly allowed LAN address"
            }
            return WebDavEndpoint(parsed.newBuilder().apply {
                if (!parsed.encodedPath.endsWith('/')) addPathSegment("")
            }.build())
        }

        internal fun validName(name: String): Boolean = name.length in 1..240 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '%' || it.isISOControl() } &&
            !name.startsWith(' ') && !name.endsWith(' ')

        fun isNativeBackup(name: String): Boolean = validName(name) &&
            name.lowercase(Locale.ROOT).endsWith(".bk.zip") && !name.startsWith('.')

        private fun unsafePath(value: String): Boolean {
            if (value.any { it == '\\' || it.isISOControl() }) return true
            val lower = value.lowercase(Locale.ROOT)
            if (listOf("%2f", "%5c", "%25", "%00", "%2e").any(lower::contains)) return true
            return value.split('/').any { it == "." || it == ".." }
        }

        private fun isLanHost(value: String): Boolean {
            val host = value.lowercase(Locale.ROOT)
            if (host == "localhost" || host.endsWith(".local") || host == "::1" ||
                host.startsWith("fc") && ':' in host || host.startsWith("fd") && ':' in host ||
                host.startsWith("fe80:") || host.startsWith("fe90:") || host.startsWith("fea0:") ||
                host.startsWith("feb0:")) return true
            val parts = host.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 ||
                parts[0] == 172 && parts[1] in 16..31 || parts[0] == 169 && parts[1] == 254
        }
    }
}

/** Passwords deliberately have no generated data-class toString/component/copy methods. */
class WebDavConnection(
    val id: String,
    val revision: String,
    val displayName: String,
    val endpoint: WebDavEndpoint,
    val username: String,
    val password: String,
    val allowCleartextLan: Boolean = false,
    val scheduleHours: Int = 0,
    val wifiOnly: Boolean = true,
    val keepBackups: Int = 0,
) {
    init {
        require(UUID.fromString(id).toString() == id && UUID.fromString(revision).toString() == revision)
        require(displayName.length in 1..120 && displayName.none(Char::isISOControl))
        require(username.length <= 512 && ':' !in username && username.none(Char::isISOControl))
        require(password.length <= 4096 && password.none { it == '\r' || it == '\n' })
        require(scheduleHours == 0 || scheduleHours in 24..168)
        require(keepBackups == 0 || keepBackups in 2..100)
    }

    override fun toString(): String = "WebDavConnection(id=$id, revision=$revision, credentials=[REDACTED])"
}

data class WebDavResource(val name: String, val size: Long, val etag: String?, val modifiedAt: Long = 0L) {
    init {
        require(WebDavEndpoint.validName(name) && size >= 0)
        require(etag == null || etag.length <= 1024 && etag.none(Char::isISOControl))
    }

    val strongEtag: String?
        get() = etag?.takeIf { value -> value.length >= 2 && value.startsWith('"') && value.endsWith('"') &&
            value.substring(1, value.lastIndex).none { it == '"' || it.isISOControl() || it == ' ' } }
}

class WebDavFailure(
    message: String,
    val status: Int = 0,
    val retryAfterMillis: Long = 0L,
    details: String = "",
    secrets: List<String> = emptyList(),
    cause: Throwable? = null,
) : IOException(message, cause), CopyableErrorDetails {
    override val copyableErrorDetails: String = buildString {
        appendLine("WebDAV · Beta")
        appendLine(message)
        if (status != 0) appendLine("HTTP status: $status")
        if (retryAfterMillis > 0) appendLine("Retry after: ${(retryAfterMillis + 999) / 1000} seconds")
        var safe = details
        val encodedSecrets = secrets.filter(String::isNotEmpty).flatMap { secret ->
            listOf(secret, java.net.URLEncoder.encode(secret, "UTF-8"),
                secret.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;"))
        }
        encodedSecrets.distinct().sortedByDescending(String::length).forEach { safe = safe.replace(it, "[REDACTED]") }
        safe = safe.redactSecrets()
        append(safe.take(16 * 1024))
    }

    val transient: Boolean
        get() = status == 0 || status == 408 || status == 429 || status in 500..599
}

class WebDavPublicationUncertain : IOException(
    "The server may have published this backup. Refresh the list before uploading again.",
), CopyableErrorDetails {
    override val copyableErrorDetails: String = "WebDAV · Beta\n" + message +
        "\nThe app retained the pending publication record and did not retry MOVE or overwrite an existing backup."
}
