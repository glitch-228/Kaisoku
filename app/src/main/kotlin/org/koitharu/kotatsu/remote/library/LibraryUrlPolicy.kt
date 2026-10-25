package org.koitharu.kotatsu.remote.library

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Exact origin plus path-segment boundary. A sibling `/books-evil` is never inside `/books/`. */
class LibraryUrlPolicy(
    val scope: HttpUrl,
    private val allowCleartextLan: Boolean = false,
) {
    init {
        checkTransport(scope)
        require(scope.query == null && scope.fragment == null && scope.encodedPath.endsWith('/')) {
            "The credential boundary must be a directory URL"
        }
        checkUnambiguousPath(scope)
    }

    fun contains(url: HttpUrl): Boolean = url.scheme == scope.scheme && url.host == scope.host &&
        url.port == scope.port && (url.encodedPath.startsWith(scope.encodedPath) ||
        url.encodedPath == scope.encodedPath.removeSuffix("/"))

    fun validate(url: HttpUrl, anonymousExternal: Boolean = false): HttpUrl {
        checkTransport(url)
        checkUnambiguousPath(url)
        if (url.fragment != null || (!contains(url) && !anonymousExternal)) throw LibraryScopeException()
        return url
    }

    fun resolve(base: String, href: String, anonymousExternal: Boolean = false): HttpUrl {
        require(href.isNotBlank() && href.length <= 8192 && '\\' !in href && href.none { it < ' ' }) {
            "Invalid library link"
        }
        val parent = base.toHttpUrlOrNull() ?: throw RemoteLibraryException("Invalid library response URL")
        val url = parent.resolve(href) ?: throw RemoteLibraryException("Unsupported library link")
        return validate(url, anonymousExternal)
    }

    private fun checkTransport(url: HttpUrl) {
        val privateHttp = allowCleartextLan && url.scheme == "http" && isPrivateLibraryAddress(url.host)
        require((url.isHttps || privateHttp) && url.username.isEmpty() && url.password.isEmpty()) {
            "Library connections require HTTPS, or explicit HTTP consent for a literal private/loopback address, without embedded credentials"
        }
    }

    private fun checkUnambiguousPath(url: HttpUrl) {
        // Reject double-decoding ambiguities used by reverse proxies and servlet containers.
        if (url.pathSegments.any { it == "." || it == ".." || '/' in it || '\\' in it || '%' in it ||
                it.any { c -> c == '\u0000' || c < ' ' } }) throw LibraryScopeException()
    }

    companion object {
        fun forConnection(connection: RemoteLibraryConnection): LibraryUrlPolicy =
            LibraryUrlPolicy(libraryConnectionUrl(connection.scopeUrl, connection.allowCleartextLan), connection.allowCleartextLan).also {
                it.validate(libraryConnectionUrl(connection.catalogUrl, connection.allowCleartextLan))
            }
        /** Internal socket-fixture helper; production HTTP additionally requires saved, explicit LAN consent. */
        internal fun loopback(scope: HttpUrl): LibraryUrlPolicy = LibraryUrlPolicy(scope, true)
    }
}

/** Encodes substitution values, never the template itself. Optional unsupported fields disappear. */
object LibraryTemplates {
    fun search(template: String, query: String): String {
        require(query.isNotBlank() && query.length <= 500) { "Enter a search query of at most 500 characters" }
        var used = false
        val result = Regex("\\{([^{}]+)}").replace(template) { match ->
            val expression = match.groupValues[1]
            if (expression.startsWith('?') || expression.startsWith('&')) {
                val values = expression.drop(1).split(',').mapNotNull { name ->
                    when (name) {
                        "query", "searchTerms" -> { used = true; "$name=${encode(query)}" }
                        "title", "author", "page", "count", "startIndex", "startPage", "language", "inputEncoding", "outputEncoding" -> null
                        else -> if (name.endsWith('?')) null else throw LibraryUnsupportedException("This catalog requires an unsupported search parameter")
                    }
                }
                if (values.isEmpty()) "" else expression.first() + values.joinToString("&")
            } else {
                when (expression) {
                    "query", "searchTerms" -> { used = true; encode(query) }
                    "startPage", "startIndex" -> "1"
                    "count" -> "50"
                    "inputEncoding", "outputEncoding" -> "UTF-8"
                    else -> if (expression.endsWith('?')) "" else throw LibraryUnsupportedException("This catalog requires an unsupported search parameter")
                }
            }
        }
        if (!used || '{' in result || '}' in result) throw LibraryUnsupportedException("The catalog has no supported keyword-search template")
        return result
    }

    fun page(link: LibraryLink, zeroBasedPage: Int): String {
        require(zeroBasedPage >= 0 && zeroBasedPage < (link.pageCount ?: 0))
        require(link.hasRel(LibraryLink.PSE_STREAM) && link.isImage && "{pageNumber}" in link.href)
        if ("{maxWidth}" in link.href) throw LibraryUnsupportedException(
            "This page stream requires an unspecified image width. Acquire the original EPUB/CBZ to preserve its geometry.")
        val result = link.href.replace("{pageNumber}", zeroBasedPage.toString())
        if ('{' in result || '}' in result) throw LibraryUnsupportedException("Unsupported page-stream template")
        return result
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
