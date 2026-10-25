package org.koitharu.kotatsu.remote.library

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

enum class RemoteLibraryKind { OPDS, KOMGA }

/** A connection is an identity, not a URL alias. Changing its origin/account requires a new connection. */
data class RemoteLibraryConnection(
    val id: String,
    val name: String,
    val catalogUrl: String,
    val scopeUrl: String,
    val kind: RemoteLibraryKind,
    val account: String,
    val content: RemoteLibraryContent = RemoteLibraryContent.UNKNOWN,
    val enabled: Boolean = true,
    val allowCleartextLan: Boolean = false,
) {
    init {
        require(UUID.fromString(id).toString() == id) { "Invalid library connection identity" }
        require(name.isNotBlank() && name.length <= 200)
        require(account.length <= 500 && account.none { it == '\r' || it == '\n' || it == '\u0000' })
    }

    val sourceName: String get() = RemoteLibrarySource.PREFIX + id

    /** Does not contain the password; prevents account/path changes from reusing another user's cache. */
    val cacheScope: String get() = libraryDigest("v1\u0000$id\u0000$catalogUrl\u0000$scopeUrl\u0000$account\u0000$allowCleartextLan")

    companion object {
        fun create(name: String, url: String, kind: RemoteLibraryKind, account: String = "",
                   content: RemoteLibraryContent = RemoteLibraryContent.UNKNOWN,
                   allowCleartextLan: Boolean = false): RemoteLibraryConnection {
            val base = libraryConnectionUrl(url, allowCleartextLan)
            require(base.query == null && base.fragment == null) { "Use a library URL without query or fragment" }
            val catalog = if (kind == RemoteLibraryKind.KOMGA) komgaCatalogUrl(base, 2) else base
            val scope = if (kind == RemoteLibraryKind.KOMGA) komgaBaseUrl(base) else {
                // Generic OPDS servers often publish images/downloads beside the feed. The user
                // chooses the boundary explicitly in setup; default is its containing directory.
                base.newBuilder().encodedPath(base.encodedPath.substringBeforeLast('/') + "/").build()
            }
            return RemoteLibraryConnection(UUID.randomUUID().toString(), name.trim(), catalog.toString(),
                scope.toString(), kind, account, content, allowCleartextLan = allowCleartextLan)
        }
    }
}

/** UNKNOWN remains unknown; connecting a personal server never labels its entire catalog safe. */
enum class RemoteLibraryContent { UNKNOWN, SAFE, MIXED, ADULT }

data class RemoteLibraryCredentials(val username: String, val password: String) {
    init {
        require(username.length <= 500 && ':' !in username && username.none { it == '\r' || it == '\n' })
        require(password.length <= 16_384 && password.none { it == '\r' || it == '\n' })
    }
    override fun toString(): String = "RemoteLibraryCredentials([redacted])"
}

data class LibraryLink(
    val href: String,
    val type: String? = null,
    val rels: Set<String> = emptySet(),
    val title: String? = null,
    val templated: Boolean = false,
    val pageCount: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val hash: String? = null,
    val facetGroup: String? = null,
    val active: Boolean = false,
) {
    val mediaType: String? get() = type?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
    fun hasRel(rel: String): Boolean = rel in rels
    val isCatalog: Boolean get() = mediaType in CATALOG_TYPES
    val isImage: Boolean get() = mediaType in IMAGE_TYPES
    val isManifest: Boolean get() = mediaType in MANIFEST_TYPES || hasRel("manifest")
    val isOpenSearch: Boolean get() = mediaType == "application/opensearchdescription+xml"
    val isDirectAcquisition: Boolean get() = rels.any { it in DIRECT_ACQUISITION_RELS }
    val supportedFormat: LibraryFormat? get() = when (mediaType) {
        "application/epub+zip" -> LibraryFormat.EPUB
        "application/vnd.comicbook+zip", "application/x-cbz", "application/cbz", "application/zip" -> LibraryFormat.CBZ
        else -> null
    }

    companion object {
        const val PSE_STREAM = "http://vaemendis.net/opds-pse/stream"
        val CATALOG_TYPES = setOf("application/atom+xml", "application/opds+json")
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif")
        val MANIFEST_TYPES = setOf("application/webpub+json", "application/divina+json", "application/opds-publication+json")
        private val DIRECT_ACQUISITION_RELS = setOf("acquisition", "download", "open-access",
            "http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access")
    }
}

enum class LibraryFormat(val extension: String, val mimeType: String) {
    CBZ("cbz", "application/vnd.comicbook+zip"), EPUB("epub", "application/epub+zip"),
}

data class LibraryPublication(
    /** Exact provider identifier, or an explicitly marked canonical-href fallback. Never a title hash. */
    val identifier: String,
    val title: String,
    val documentUrl: String,
    val authors: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val subjects: Set<String> = emptySet(),
    val description: String? = null,
    val links: List<LibraryLink> = emptyList(),
    val images: List<LibraryLink> = emptyList(),
    val readingOrder: List<LibraryLink> = emptyList(),
    val content: RemoteLibraryContent = RemoteLibraryContent.UNKNOWN,
    val modified: String? = null,
) {
    val acquisitionLinks: List<LibraryLink> get() = links.filter { it.isDirectAcquisition && it.supportedFormat != null && !it.templated }
    val manifestLink: LibraryLink? get() = links.firstOrNull { it.isManifest && !it.templated }
    val selfLink: LibraryLink? get() = links.firstOrNull { it.hasRel("self") ||
        (it.hasRel("alternate") && it.mediaType in LibraryLink.CATALOG_TYPES + LibraryLink.MANIFEST_TYPES) }
    val pageStream: LibraryLink? get() = links.firstOrNull { it.hasRel(LibraryLink.PSE_STREAM) && it.isImage &&
        it.pageCount != null && it.pageCount in 1..MAX_LIBRARY_PAGES && it.href.contains("{pageNumber}") }
    val cover: LibraryLink? get() = images.firstOrNull { it.isImage } ?: links.firstOrNull {
        it.isImage && it.rels.any { rel -> rel in setOf("cover", "http://opds-spec.org/image", "http://opds-spec.org/image/thumbnail") }
    }
}

data class LibraryFacet(val title: String, val links: List<LibraryLink>)
data class LibraryGroup(val title: String, val navigation: List<LibraryLink>, val publications: List<LibraryPublication>,
                        val links: List<LibraryLink>)
data class LibraryFeed(
    val title: String,
    val documentUrl: String,
    val links: List<LibraryLink>,
    val navigation: List<LibraryLink>,
    val publications: List<LibraryPublication>,
    val facets: List<LibraryFacet> = emptyList(),
    val groups: List<LibraryGroup> = emptyList(),
) {
    val next: LibraryLink? get() = links.firstOrNull { it.hasRel("next") && it.isCatalog && !it.templated }
    val search: LibraryLink? get() = links.firstOrNull { it.hasRel("search") && (it.isOpenSearch || it.isCatalog) }
}

data class LibraryFeedResult(val feed: LibraryFeed, val fromCache: Boolean, val refreshError: Throwable? = null)
data class LibraryReadPage(val url: String, val width: Int? = null, val height: Int? = null)
data class LibraryAcquisitionRecord(
    val connectionId: String,
    val publicationId: String,
    val localMangaId: Long,
    val localUrl: String,
    val sha256: String,
    val format: LibraryFormat,
    val metadata: LibraryAcquiredMetadata? = null,
)

/** A saved source snapshot, never a reader position or a user-owned override. */
data class LibraryAcquiredMetadata(val title: String, val authors: Set<String>, val subjects: Set<String>,
                                  val description: String?, val content: RemoteLibraryContent)

open class RemoteLibraryException(message: String, cause: Throwable? = null) : IOException(message, cause),
    org.koitharu.kotatsu.core.exceptions.CopyableErrorDetails {
    override val copyableErrorDetails: String get() = LibraryDiagnostics.text(this)
    override fun copyableEnclosingErrorDetails(error: Throwable): String = LibraryDiagnostics.text(error)
}
class LibraryScopeException : RemoteLibraryException("This link is outside the library connection's credential boundary. Add a separate connection or explicitly acquire it anonymously.")
class LibraryAuthenticationException : RemoteLibraryException("The library rejected this account. Check its connection credentials.")
class LibraryLimitException(message: String) : RemoteLibraryException(message)
class LibraryUnsupportedException(message: String) : RemoteLibraryException(message)

internal const val MAX_LIBRARY_PAGES = 20_000
internal const val MAX_LIBRARY_BODY = 4 * 1024 * 1024
internal const val MAX_LIBRARY_ITEMS = 2_000

internal fun libraryDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun libraryLongId(value: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))).long.let { if (it == 0L) 1L else it }

internal fun libraryHttpsUrl(value: String): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter an HTTPS library URL")
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
        "Use HTTPS and enter credentials in the account fields"
    }
    return url
}

internal fun libraryConnectionUrl(value: String, allowCleartextLan: Boolean): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a library URL")
    require((url.isHttps || (allowCleartextLan && url.scheme == "http" && isPrivateLibraryAddress(url.host))) &&
        url.username.isEmpty() && url.password.isEmpty()) {
        "Use HTTPS, or explicitly allow HTTP to a literal private/loopback address; enter credentials separately"
    }
    return url
}

/** Literal addresses only: a hostname cannot rebind a cleartext consent to a public server. */
internal fun isPrivateLibraryAddress(host: String): Boolean {
    val fields = host.split('.')
    if (fields.size == 4 && fields.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
        val numbers = fields.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return numbers[0] == 10 || numbers[0] == 127 || (numbers[0] == 192 && numbers[1] == 168) ||
            (numbers[0] == 172 && numbers[1] in 16..31) || (numbers[0] == 169 && numbers[1] == 254)
    }
    if (':' !in host || !Regex("[a-fA-F0-9:]+").matches(host)) return false
    val bytes = runCatching { java.net.InetAddress.getByName(host).address }.getOrNull() ?: return false
    return bytes.size == 16 && (bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte() ||
        (bytes[0].toInt() and 0xfe) == 0xfc || ((bytes[0].toInt() and 255) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80))
}

fun komgaBaseUrl(url: HttpUrl): HttpUrl {
    val path = url.encodedPath
    val suffix = Regex("/opds/(?:v2|v1\\.2)/catalog/?$")
    val base = suffix.find(path)?.let { path.substring(0, it.range.first) } ?: path.trimEnd('/')
    return url.newBuilder().encodedPath("$base/").query(null).fragment(null).build()
}

fun komgaCatalogUrl(url: HttpUrl, version: Int): HttpUrl {
    require(version == 1 || version == 2)
    return komgaBaseUrl(url).resolve(if (version == 2) "opds/v2/catalog" else "opds/v1.2/catalog")!!
}
