package org.koitharu.kotatsu.remote.library

import java.net.URI
import java.util.Base64
import java.util.UUID

/** Account-scoped cache key, with no password, authorization header, or provider capability URL. */
data class LibraryImageReference(val connectionId: String, val scope: String, val publicationId: String,
                                 val pageIndex: Int? = null) {
    init {
        require(UUID.fromString(connectionId).toString() == connectionId)
        require(Regex("[a-f0-9]{64}").matches(scope))
        require(publicationId.isNotBlank() && publicationId.length <= 4096)
        require(pageIndex == null || pageIndex in 0 until MAX_LIBRARY_PAGES)
    }

    override fun toString(): String = "$SCHEME://$connectionId/$scope/${if (pageIndex == null) "cover" else "page"}/" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(publicationId.toByteArray(Charsets.UTF_8)) +
        (pageIndex?.let { "/$it" } ?: "")

    companion object {
        const val SCHEME = "library-image"
        fun parse(value: String): LibraryImageReference? { return try {
            val uri = URI(value)
            if (uri.scheme != SCHEME || uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) null else {
                val parts = uri.rawPath.removePrefix("/").split('/')
                if (parts.size !in 3..4 || parts[1] !in setOf("cover", "page") || (parts[1] == "cover") != (parts.size == 3)) null else {
                    val id = Base64.getUrlDecoder().decode(parts[2]).toString(Charsets.UTF_8)
                    val page = if (parts.size == 4) parts[3].toIntOrNull() ?: return null else null
                    LibraryImageReference(uri.host ?: return null, parts[0], id, page).takeIf { it.toString() == value }
                }
            }
        } catch (_: IllegalArgumentException) { null } catch (_: java.net.URISyntaxException) { null } }
    }
}
