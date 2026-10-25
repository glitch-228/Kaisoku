package org.koitharu.kotatsu.scrobbling.v2

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Independently implemented website mapping; no login/password/cookie data is returned by metadata. */
object AnimePlanetCodec {
    const val PROVIDER = "ANIME_PLANET"
    const val ORIGIN = "https://www.anime-planet.com"
    private val tokenPattern = Regex("""\bTOKEN\s*=\s*['"]([A-Za-z0-9_-]{20,256})['"]""")
    private val idPattern = Regex("[0-9]{1,20}")

    data class VerifiedProfile(val username: String, internal val csrfToken: String) {
        override fun toString(): String = "VerifiedAnimePlanetProfile(username=$username)"
    }

    data class Page(val entry: TrackerRemoteEntry, val slug: String, internal val csrfToken: String?)

    fun profile(html: String): VerifiedProfile {
        val document = Jsoup.parse(html, ORIGIN)
        val link = document.selectFirst(".loggedIn a[href^=/users/]")
            ?: throw IllegalStateException("Sign in to Anime-Planet, then verify your account")
        val url = link.absUrl("href").toHttpUrl()
        require(url.host == "www.anime-planet.com" && url.pathSegments.size in 2..3)
        val username = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Anime-Planet account is unavailable")
        return VerifiedProfile(username, tokenPattern.find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Anime-Planet verification token is unavailable"))
    }

    fun titlePage(html: String, slug: String, owner: TrackerOwner): Page {
        require(owner.provider == PROVIDER)
        val document = Jsoup.parse(html, ORIGIN)
        val form = document.selectFirst("form[id^=manga][data-id]")
            ?: throw IllegalStateException("Anime-Planet title form is unavailable; the website may have changed")
        val mediaId = form.attr("data-id").also { require(idPattern.matches(it)) }
        val title = document.selectFirst("h1[itemprop=name], h1")?.text()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Anime-Planet title is unavailable")
        val progressNumber = selected(form, "select.chapters, select[name=chapters]")
        val progressVolume = selected(form, "select.volumes, select[name=volumes]")
        val status = selected(form, "select.changeStatus")?.let(::status)
        val score = form.selectFirst("div.starrating > div[name]")?.attr("name")?.takeIf(String::isNotBlank)
        val description = document.selectFirst("[itemprop=description], .entrySynopsis")?.html()
        val cover = document.selectFirst("[itemprop=image], .mainEntry img")?.absUrl("src")?.takeIf(String::isNotBlank)
        val canonical = titleUrl(slug).toString()
        return Page(
            TrackerRemoteEntry(
                reference = TrackerReference(owner, mediaId, mediaId),
                title = TrackerTitle(mediaId, title, coverUrl = cover, description = description,
                    url = canonical, format = "MANGA"),
                status = status,
                progress = if (progressNumber == null && progressVolume == null) null
                    else TrackerRawProgress(progressNumber, progressVolume),
                rating = score,
            ),
            slug = canonical.substringAfter("/manga/"),
            csrfToken = tokenPattern.find(html)?.groupValues?.get(1),
        )
    }

    fun titleUrl(slug: String): HttpUrl {
        require(slug.isNotBlank() && slug.length <= 256 && !slug.contains('/') && !slug.contains('\\'))
        return ORIGIN.toHttpUrl().newBuilder().addPathSegment("manga").addPathSegment(slug).build()
    }

    fun slugFromUrl(value: String): String {
        val url = value.toHttpUrl()
        require(url.isHttps && url.host == "www.anime-planet.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.pathSegments.size == 2 && url.pathSegments[0] == "manga") { "Invalid Anime-Planet manga URL" }
        return url.pathSegments[1].also { titleUrl(it) }
    }

    /** One mutating GET per explicitly selected field. Callers must not cache or replay these. */
    fun mutationUrls(mediaId: String, csrfToken: String, patch: TrackerPatch): List<HttpUrl> {
        require(idPattern.matches(mediaId))
        require(csrfToken.length in 20..256 && csrfToken.all { it.isLetterOrDigit() || it in "_-" })
        require(patch.notes == TrackerField.Unchanged) { "Anime-Planet notes are unsupported" }
        require(!patch.isEmpty)
        return buildList {
            (patch.status as? TrackerField.Set)?.let { field ->
                add(mutation("status", mediaId, listOf(statusCode(requireNotNull(field.value))), csrfToken))
            }
            (patch.progress as? TrackerField.Set)?.let { field ->
                val progress = requireNotNull(field.value) { "Explicit progress clearing is unsupported" }
                require(progress.unit == "chapter" && progress.entryId == null)
                val chapter = requireNotNull(progress.number).toBigDecimalOrNull()
                require(chapter != null && chapter.signum() >= 0 && runCatching { chapter.toBigIntegerExact() }.isSuccess) {
                    "Anime-Planet requires a whole chapter; fractional progress is preserved without writing"
                }
                val volume = progress.volume ?: "0"
                require(volume.toBigDecimalOrNull()?.let { it.signum() >= 0 && runCatching { it.toBigIntegerExact() }.isSuccess } == true)
                add(mutation("update", mediaId, listOf(chapter.toBigIntegerExact().toString(), volume), csrfToken))
            }
            (patch.rating as? TrackerField.Set)?.let { field ->
                val rating = if (field.value == null) java.math.BigDecimal.ZERO else
                    requireNotNull(field.value.toBigDecimalOrNull()) { "Invalid Anime-Planet rating" }
                require(rating >= java.math.BigDecimal.ZERO && rating <= java.math.BigDecimal("5") &&
                    rating.multiply(java.math.BigDecimal("2")).stripTrailingZeros().scale() <= 0) {
                    "Anime-Planet ratings use half-star steps from 0 to 5"
                }
                add(mutation("rate", mediaId, listOf(rating.stripTrailingZeros().toPlainString()), csrfToken))
            }
        }
    }

    fun status(value: String): String = when (value) {
        "0" -> "NONE"
        "1" -> "COMPLETED"
        "2" -> "CURRENT"
        "3" -> "DROPPED"
        "4" -> "PLANNING"
        "5" -> "PAUSED"
        "6" -> "WONT_READ"
        else -> "ANIME_PLANET:$value"
    }

    private fun statusCode(value: String): String = when (value) {
        "COMPLETED" -> "1"
        "CURRENT" -> "2"
        "DROPPED" -> "3"
        "PLANNING" -> "4"
        "PAUSED" -> "5"
        "WONT_READ" -> "6"
        else -> throw IllegalArgumentException("Anime-Planet does not support this status")
    }

    private fun mutation(operation: String, id: String, values: List<String>, token: String): HttpUrl =
        ORIGIN.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("list")
            .addPathSegment(operation).addPathSegment("manga").addPathSegment(id).apply {
                values.forEach(::addPathSegment)
            }.addPathSegment(token).build()

    private fun selected(root: Element, selector: String): String? {
        val select = root.selectFirst(selector) ?: return null
        return (select.selectFirst("option[selected]") ?: select.selectFirst("option"))?.attr("value")
    }
}
