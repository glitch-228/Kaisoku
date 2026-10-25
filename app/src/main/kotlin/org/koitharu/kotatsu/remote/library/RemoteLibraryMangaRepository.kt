package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.await
import java.util.Base64

/** Native server identity reaches the existing reader; no extension ABI or progress semantics change. */
class RemoteLibraryMangaRepository(
    override val source: RemoteLibrarySource,
    val session: RemoteLibrarySession,
) : MangaRepository {
    private val browsing = Mutex()
    private var browseQuery: String? = null
    private var browseFeed: LibraryFeed? = null
    private val seenBrowseUrls = LinkedHashSet<String>()
    private var emittedCount = 0

    override val sortOrders: Set<SortOrder> = setOf(SortOrder.NEWEST)
    override var defaultSortOrder: SortOrder = SortOrder.NEWEST
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = browsing.withLock {
        val query = filter?.query?.trim()?.takeIf(String::isNotEmpty)
        val feed = if (offset == 0 || browseQuery != query || browseFeed == null) {
            browseQuery = query
            emittedCount = 0
            seenBrowseUrls.clear()
            val root = session.feed().feed
            if (query == null) root else session.search(root, query).feed
        } else {
            if (offset != emittedCount) throw RemoteLibraryException("Catalog paging changed; refresh this list")
            session.next(browseFeed!!, seenBrowseUrls)?.feed ?: return@withLock emptyList()
        }
        if (!seenBrowseUrls.add(feed.documentUrl)) throw RemoteLibraryException("The catalog returned repeated pagination")
        browseFeed = feed
        val items = (feed.publications + feed.groups.flatMap { it.publications }).distinctBy { it.identifier }.map { toManga(it, false) }
        emittedCount += items.size
        items
    }

    override suspend fun getDetails(manga: Manga): Manga = toManga(session.publication(identifier(manga.url),
        fallbackUrl = manga.publicUrl), true)

    override suspend fun refreshDetails(manga: Manga): Manga = toManga(session.publication(identifier(manga.url),
        refresh = true, fallbackUrl = manga.publicUrl), true)

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val identifier = identifier(chapter.url)
        val publication = session.publication(identifier)
        return session.pages(publication).mapIndexed { index, page -> MangaPage(
            libraryLongId("${source.name}\u0000$identifier\u0000page:$index"),
            LibraryImageReference(source.connectionId, session.connection.cacheScope, identifier, index).toString(), null, source) }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
    override fun getImageClient(): OkHttpClient = session.transport.imageClient
    override fun createPageRequest(pageUrl: String, page: MangaPage): Request = session.transport.pageRequest(pageUrl)
    override suspend fun getPageRequest(page: MangaPage): Request {
        val reference = LibraryImageReference.parse(page.url) ?: throw RemoteLibraryException("Invalid library page reference")
        if (reference.connectionId != source.connectionId || reference.scope != session.connection.cacheScope || reference.pageIndex == null) {
            throw LibraryAuthenticationException()
        }
        return session.transport.pageRequest(session.page(session.publication(reference.publicationId), reference.pageIndex).url)
    }
    override suspend fun getPageResponse(page: MangaPage, okHttp: OkHttpClient,
                                        imageProxyInterceptor: org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor): okhttp3.Response =
        session.transport.imageClient.newCall(getPageRequest(page)).await()
    override suspend fun getFilterOptions() = MangaListFilterOptions()
    override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()
    override val supportsLightweightMetadata: Boolean get() = true
    override suspend fun getLightweightMetadata(manga: Manga): Manga = toManga(session.publication(identifier(manga.url)), false)
    // No recommendation/background discovery silently crawls a private user's server.

    fun toManga(publication: LibraryPublication, withChapters: Boolean = true): Manga {
        session.permitted(publication)
        val identity = identityUrl(publication.identifier)
        val type = publication.content.takeUnless { it == RemoteLibraryContent.UNKNOWN } ?: session.connection.content
        val link = publication.selfLink?.href ?: publication.documentUrl
        val publicUrl = runCatching { session.transport.policy.resolve(publication.documentUrl, link).toString() }
            .getOrElse { session.connection.catalogUrl }
        val cover = publication.cover?.href?.let { raw ->
            runCatching { session.transport.policy.resolve(publication.documentUrl, raw) }.getOrNull()?.let {
                LibraryImageReference(source.connectionId, session.connection.cacheScope, publication.identifier).toString()
            }
        }
        return Manga(
            id = libraryLongId("${source.name}\u0000${publication.identifier}"), title = publication.title,
            altTitles = emptySet(), url = identity, publicUrl = publicUrl, rating = -1f,
            contentRating = when (type) { RemoteLibraryContent.SAFE -> ContentRating.SAFE; RemoteLibraryContent.ADULT -> ContentRating.ADULT; else -> null },
            coverUrl = cover, tags = publication.subjects.mapTo(LinkedHashSet()) { MangaTag(it, it, source) },
            state = null, authors = publication.authors, description = publication.description,
            chapters = if (withChapters) listOf(MangaChapter(
                id = libraryLongId("${source.name}\u0000${publication.identifier}\u0000read"), title = publication.title,
                number = 1f, volume = 0, url = identity, scanlator = null, uploadDate = 0, branch = null, source = source)) else null,
            source = source,
        )
    }

    fun identifier(url: String): String {
        val prefix = "kaisoku-library://${source.connectionId}/publication/"
        if (!url.startsWith(prefix)) throw RemoteLibraryException("The publication does not belong to this library connection")
        return try {
            val value = Base64.getUrlDecoder().decode(url.removePrefix(prefix)).toString(Charsets.UTF_8)
            if (value.isBlank() || value.length > 4096 || identityUrl(value) != url) throw IllegalArgumentException("Invalid publication identity")
            value
        } catch (error: IllegalArgumentException) { throw RemoteLibraryException("Invalid library publication identity", error) }
    }

    private fun identityUrl(identifier: String): String = "kaisoku-library://${source.connectionId}/publication/" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(identifier.toByteArray(Charsets.UTF_8))
}
