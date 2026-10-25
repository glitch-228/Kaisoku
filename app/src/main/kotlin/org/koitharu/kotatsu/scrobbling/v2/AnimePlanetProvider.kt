package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicLong

interface AnimePlanetTitleIndex {
    fun slug(account: String, mediaId: String): String?
    fun remember(account: String, mediaId: String, slug: String)
    fun isWriteValidated(account: String): Boolean
    fun setWriteValidated(account: String, value: Boolean)
}

/** Website login is explicit. CSRF and exact cookie snapshots stay in memory and out of provider DTOs. */
class AnimePlanetProvider(
    private val wire: TrackerWire,
    private val cookies: () -> String?,
    private val index: AnimePlanetTitleIndex,
) : TrackerProvider {
    override val key = AnimePlanetCodec.PROVIDER
    override val capabilities = setOf(TrackerCapability.SEARCH, TrackerCapability.METADATA, TrackerCapability.LIBRARY,
        TrackerCapability.PROGRESS_READ, TrackerCapability.PROGRESS_WRITE, TrackerCapability.STATUS_WRITE,
        TrackerCapability.RATING_WRITE)
    private val revisions = AtomicLong()
    private val mutations = Mutex()
    @Volatile private var session: Session? = null

    fun knownConnection(): TrackerConnection? = session?.connection
    internal fun canWrite(connection: TrackerConnection): Boolean {
        checkConnection(connection)
        return index.isWriteValidated(connection.owner.accountId)
    }

    suspend fun verifyAccount(): TrackerConnection {
        val cookie = cookies()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Sign in to Anime-Planet in the app browser first")
        val html = execute(Request.Builder().url("${AnimePlanetCodec.ORIGIN}/contact").get(), cookie).body
        val profile = AnimePlanetCodec.profile(html)
        check(cookies() == cookie) { "Anime-Planet login changed during verification" }
        val previous = session
        val connection = TrackerConnection(TrackerOwner(key, profile.username),
            if (previous?.connection?.owner?.accountId == profile.username && previous.cookie == cookie)
                previous.connection.revision else revisions.incrementAndGet(), nickname = profile.username)
        session = Session(connection, cookie, profile.csrfToken)
        return connection
    }

    fun disconnect() { session = null; revisions.incrementAndGet() }

    override suspend fun connection(): TrackerConnection = verifyAccount()

    override fun checkConnection(connection: TrackerConnection) {
        val current = session ?: throw TrackerReconnectRequired()
        check(connection == current.connection && cookies() == current.cookie) {
            "Anime-Planet login changed; verify your account again"
        }
    }

    override suspend fun search(connection: TrackerConnection, query: String): List<TrackerTitle> {
        checkConnection(connection)
        require(query.length in 2..100)
        val url = "${AnimePlanetCodec.ORIGIN}/autocomplete".toHttpUrl().newBuilder()
            .addQueryParameter("type", "manga").addQueryParameter("show_slug", "false")
            .addQueryParameter("q", query).build()
        val response = call(connection, Request.Builder().url(url).get())
        val rows = JSONObject(response.body).getJSONArray("data")
        val titles = mutableListOf<TrackerTitle>()
        for (row in rows.objects().take(5)) {
            val link = AnimePlanetCodec.ORIGIN.toHttpUrl().resolve(row.getString("url"))?.toString() ?: continue
            val slug = AnimePlanetCodec.slugFromUrl(link)
            titles += page(connection, slug).entry.title
        }
        return titles.distinctBy { it.mediaId }
    }

    override suspend fun metadata(connection: TrackerConnection, mediaId: String): TrackerTitle =
        pageForMedia(connection, mediaId).entry.title

    override suspend fun entry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry? =
        pageForMedia(connection, mediaId).entry.takeUnless { it.status == "NONE" }

    override suspend fun libraryPage(connection: TrackerConnection, cursor: String?): TrackerLibraryPage {
        checkConnection(connection)
        val number = cursor?.toIntOrNull() ?: 1
        require(number in 1..100 && (cursor == null || cursor == number.toString()))
        val url = AnimePlanetCodec.ORIGIN.toHttpUrl().newBuilder().addPathSegment("users")
            .addPathSegment(connection.owner.accountId).addPathSegment("manga")
            .addQueryParameter("sort", "title").addQueryParameter("page", number.toString()).build()
        val html = call(connection, Request.Builder().url(url).get()).body
        val document = Jsoup.parse(html, AnimePlanetCodec.ORIGIN)
        val profile = AnimePlanetCodec.profile(html)
        check(profile.username == connection.owner.accountId) { "Anime-Planet returned another account" }
        val entries = document.select("table.personalList tbody tr").mapNotNull { row ->
            val link = row.selectFirst("a.tooltip[href^=/manga/]") ?: return@mapNotNull null
            val form = row.selectFirst("form[data-id]") ?: return@mapNotNull null
            val slug = AnimePlanetCodec.slugFromUrl(link.absUrl("href"))
            val id = form.attr("data-id").takeIf { Regex("[0-9]{1,20}").matches(it) } ?: return@mapNotNull null
            index.remember(connection.owner.accountId, id, slug)
            fun selected(selector: String): String? = row.selectFirst("$selector option[selected]")?.attr("value")
                ?: row.selectFirst("$selector option")?.attr("value")
            val chapter = selected("select[name=chapters]")
            val volume = selected("select[name=volumes]")
            TrackerRemoteEntry(TrackerReference(connection.owner, id, id),
                TrackerTitle(id, link.text(), url = AnimePlanetCodec.titleUrl(slug).toString(), format = "MANGA"),
                selected("select.changeStatus")?.let(AnimePlanetCodec::status),
                if (chapter == null && volume == null) null else TrackerRawProgress(chapter, volume),
                rating = row.selectFirst("div.starrating > div[name]")?.attr("name"))
        }
        check(entries.isNotEmpty() || document.selectFirst("table.personalList") != null) {
            "Anime-Planet list view is unsupported; no website preferences were changed"
        }
        val next = document.select(".pagination a[href]").mapNotNull { link ->
            runCatching { link.absUrl("href").toHttpUrl().queryParameter("page")?.toIntOrNull() }.getOrNull()
        }.any { it == number + 1 }
        return TrackerLibraryPage(entries, (number + 1).toString().takeIf { next && number < 100 })
    }

    /** One explicit unchanged-status probe validates authorized writes and readback for this account. */
    suspend fun validateWrites(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry = mutations.withLock {
        val before = pageForMedia(connection, mediaId).entry
        require(before.status != null && before.status != "NONE") { "Choose an existing list entry to verify" }
        writeAndVerify(connection, mediaId, TrackerPatch.statusOnly(before.status)).also {
            index.setWriteValidated(connection.owner.accountId, true)
        }
    }

    override suspend fun patch(connection: TrackerConnection, mediaId: String, patch: TrackerPatch,
        allowCreate: Boolean): TrackerRemoteEntry = mutations.withLock {
        require(index.isWriteValidated(connection.owner.accountId)) {
            "Validate Anime-Planet write access with an existing entry first"
        }
        val existing = entry(connection, mediaId)
        require(existing != null || allowCreate) { "Confirm adding this title to Anime-Planet first" }
        writeAndVerify(connection, mediaId, patch)
    }

    private suspend fun writeAndVerify(connection: TrackerConnection, mediaId: String, patch: TrackerPatch): TrackerRemoteEntry {
        val before = pageForMedia(connection, mediaId)
        if (patch.progress is TrackerField.Set) checkTrackerProgressWrite(before.entry)
        val effectivePatch = if (patch.progress is TrackerField.Set && patch.progress.value != null) {
            patch.copy(progress = TrackerField.Set(patch.progress.value.copy(
                volume = patch.progress.value.volume ?: before.entry.progress?.volume
                    ?: throw IllegalArgumentException("Confirm the volume before changing Anime-Planet progress"),
            )))
        } else patch
        val current = checkNotNull(session)
        val token = before.csrfToken ?: current.token
        AnimePlanetCodec.mutationUrls(mediaId, token, effectivePatch).forEach { url ->
            checkConnection(connection)
            call(connection, Request.Builder().url(url).header("Referer", before.entry.title.url).get(), listOf(token))
        }
        val after = pageForMedia(connection, mediaId).entry
        fun <T> expected(field: TrackerField<T>, old: T?): T? = when (field) {
            TrackerField.Unchanged -> old
            is TrackerField.Set -> field.value
        }
        check(after.status == expected(effectivePatch.status, before.entry.status)) { "Anime-Planet status could not be verified" }
        check(after.progress == expected(effectivePatch.progress, before.entry.progress)) { "Anime-Planet progress could not be verified" }
        val expectedRating = if (effectivePatch.rating is TrackerField.Set && effectivePatch.rating.value == null) "0"
            else expected(effectivePatch.rating, before.entry.rating)
        val expectedNumericRating = expectedRating?.toBigDecimalOrNull()
        check(expectedNumericRating != null && after.rating?.toBigDecimalOrNull()?.compareTo(expectedNumericRating) == 0 ||
            after.rating == expectedRating) {
            "Anime-Planet rating could not be verified"
        }
        return after
    }

    private suspend fun pageForMedia(connection: TrackerConnection, mediaId: String): AnimePlanetCodec.Page {
        val slug = index.slug(connection.owner.accountId, mediaId)
            ?: throw IllegalStateException("Refresh the list or search for this Anime-Planet title first")
        return page(connection, slug).also { check(it.entry.reference.mediaId == mediaId) { "Anime-Planet title identity changed" } }
    }

    private suspend fun page(connection: TrackerConnection, slug: String): AnimePlanetCodec.Page {
        val html = call(connection, Request.Builder().url(AnimePlanetCodec.titleUrl(slug)).get()).body
        val verified = AnimePlanetCodec.profile(html)
        check(verified.username == connection.owner.accountId) { "Anime-Planet login changed" }
        return AnimePlanetCodec.titlePage(html, slug, connection.owner).also {
            index.remember(connection.owner.accountId, it.entry.reference.mediaId, it.slug)
        }
    }

    private suspend fun call(connection: TrackerConnection, request: Request.Builder,
        secrets: List<String> = emptyList()): TrackerWireResponse {
        checkConnection(connection)
        val current = checkNotNull(session)
        return execute(request, current.cookie, secrets + current.token).also { checkConnection(connection) }
    }

    private suspend fun execute(request: Request.Builder, cookie: String,
        secrets: List<String> = emptyList()): TrackerWireResponse {
        val built = request.header("Cookie", cookie).header("Accept", "text/html,application/json").build()
        require(built.url.host == "www.anime-planet.com" && built.url.isHttps && built.url.port == 443)
        return wire.execute(built).also { response ->
            if (!response.successful) throw TrackerProviderError(key, response.code,
                if (response.code == 429 && response.retryAfterMillis <= 0) 60_000 else response.retryAfterMillis,
                response.body, secrets + cookie)
        }
    }

    private class Session(val connection: TrackerConnection, val cookie: String, val token: String)
}
