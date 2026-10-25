package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** One connection; browsing/searching/pagination are explicit and independent, with no background crawl. */
class RemoteLibrarySession(
    val connection: RemoteLibraryConnection,
    val transport: RemoteLibraryTransport,
    private val cache: LibraryCache,
    private val allowsContent: (RemoteLibraryContent) -> Boolean = { true },
    private val allowsConnection: () -> Boolean = { allowsContent(connection.content) },
    private val isCurrent: () -> Boolean = { true },
) {
    private val requests = HashMap<String, Mutex>()
    private val revisions = HashMap<String, Long>()
    private val failures = HashMap<String, IOException>()
    private val manifests = LinkedHashMap<String, LibraryPublication>()

    suspend fun feed(url: String = connection.catalogUrl, refresh: Boolean = false): LibraryFeedResult {
        ensureConnection()
        val target = transport.policy.validate(url.toHttpUrlOrNull() ?: throw RemoteLibraryException("Invalid catalog URL")).toString()
        val startingRevision = synchronized(requests) { revisions[target] ?: 0L }
        val lock = synchronized(requests) { requests.getOrPut(target) { Mutex() } }
        try {
            return lock.withLock {
                val previous = cache.document(connection.cacheScope, target)?.let { cached ->
                    try {
                        transport.policy.resolve(connection.catalogUrl, cached.url)
                        OpdsParser.feed(cached)
                    } catch (_: IOException) { null }
                }
                val changed = synchronized(requests) { (revisions[target] ?: 0L) != startingRevision }
                if (previous != null && (!refresh || changed)) return@withLock LibraryFeedResult(filter(previous), true,
                    if (changed) synchronized(requests) { failures[target] } else null)
                try {
                    val document = transport.document(target)
                    val parsed = OpdsParser.feed(document)
                    val cacheError = try { cache.save(connection.cacheScope, document, parsed, cacheUrl = target); null }
                        catch (error: IOException) { error }
                    synchronized(requests) { revisions[target] = startingRevision + 1; failures.remove(target) }
                    ensureConnection()
                    LibraryFeedResult(filter(parsed), false, cacheError)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IOException) {
                    synchronized(requests) { revisions[target] = startingRevision + 1; failures[target] = error }
                    ensureConnection()
                    previous?.let { LibraryFeedResult(filter(it), true, error) } ?: throw error
                }
            }
        } finally {
            // Retain active/coalesced request mutexes, but avoid a URL-key memory leak across a server crawl.
            synchronized(requests) {
                if (requests.size > 64) {
                    val expired = requests.filter { !it.value.isLocked && it.key != target }.keys
                    expired.forEach { requests.remove(it); revisions.remove(it); failures.remove(it) }
                }
            }
        }
    }

    suspend fun search(feed: LibraryFeed, query: String, refresh: Boolean = false): LibraryFeedResult {
        ensureConnection()
        val advertised = feed.search ?: throw LibraryUnsupportedException("This catalog does not advertise search")
        val link = if (advertised.isOpenSearch) {
            val target = transport.policy.resolve(feed.documentUrl, advertised.href).toString()
            val descriptor = cache.document(connection.cacheScope, target) ?: transport.document(target).also {
                cache.save(connection.cacheScope, it, cacheUrl = target)
            }
            OpdsParser.openSearch(descriptor).firstOrNull()
                ?: throw LibraryUnsupportedException("This catalog has no supported OpenSearch template")
        } else advertised
        if (!link.templated) throw LibraryUnsupportedException("This catalog has no supported keyword-search template")
        return feed(transport.policy.resolve(feed.documentUrl, LibraryTemplates.search(link.href, query)).toString(), refresh)
    }

    suspend fun next(feed: LibraryFeed, visited: Set<String>, refresh: Boolean = false): LibraryFeedResult? {
        val link = feed.next ?: return null
        val target = transport.policy.resolve(feed.documentUrl, link.href).toString()
        if (target == feed.documentUrl || target in visited) throw RemoteLibraryException("The catalog pagination returned an already visited page")
        return feed(target, refresh)
    }

    suspend fun publication(identifier: String, refresh: Boolean = false, fallbackUrl: String? = null): LibraryPublication {
        ensureConnection()
        val cached = cache.publication(connection.cacheScope, identifier)
        if (cached != null && !refresh) return permitted(cached)
        val url = cached?.selfLink?.href ?: fallbackUrl ?: cached?.documentUrl
            ?: throw RemoteLibraryException("This publication is not cached. Open it from its catalog first.")
        val document = transport.document(transport.policy.resolve(connection.catalogUrl, url).toString())
        val actual = try { OpdsParser.publication(document) } catch (error: IOException) {
            val feed = OpdsParser.feed(document)
            cache.save(connection.cacheScope, document, feed)
            (feed.publications + feed.groups.flatMap { it.publications }).singleOrNull { it.identifier == identifier }
                ?: throw RemoteLibraryException("The catalog no longer exposes this exact publication identity", error)
        }
        if (actual.identifier != identifier) throw RemoteLibraryException("The server returned a different publication identity")
        cache.save(connection.cacheScope, document, publication = actual)
        return permitted(actual)
    }

    suspend fun pages(publication: LibraryPublication): List<LibraryReadPage> {
        permitted(publication)
        publication.pageStream?.let { stream ->
            return (0 until stream.pageCount!!).map { index ->
                LibraryReadPage(transport.policy.resolve(publication.documentUrl, LibraryTemplates.page(stream, index)).toString())
            }
        }
        val actual = readingPublication(publication)
        return actual.readingOrder.map { link -> LibraryReadPage(
            transport.policy.resolve(actual.documentUrl, link.href).toString(), link.width, link.height) }
    }

    suspend fun page(publication: LibraryPublication, index: Int): LibraryReadPage {
        permitted(publication)
        publication.pageStream?.let { stream ->
            if (index !in 0 until stream.pageCount!!) throw RemoteLibraryException("The image page no longer exists")
            return LibraryReadPage(transport.policy.resolve(publication.documentUrl, LibraryTemplates.page(stream, index)).toString())
        }
        val actual = readingPublication(publication)
        val link = actual.readingOrder.getOrNull(index) ?: throw RemoteLibraryException("The image page no longer exists")
        ensureConnection()
        return LibraryReadPage(transport.policy.resolve(actual.documentUrl, link.href).toString(), link.width, link.height)
    }

    private suspend fun readingPublication(publication: LibraryPublication): LibraryPublication {
        val actual = if (publication.readingOrder.isNotEmpty()) publication else publication.manifestLink?.let manifest@{ manifest ->
            val key = publication.identifier + "\u0000" + manifest.href
            synchronized(manifests) { manifests[key] }?.let { return@manifest it }
            val url = transport.policy.resolve(publication.documentUrl, manifest.href).toString()
            val document = cache.document(connection.cacheScope, url) ?: transport.document(url).also {
                cache.save(connection.cacheScope, it, cacheUrl = url)
            }
            OpdsParser.publication(document).also {
                if (it.identifier != publication.identifier) throw RemoteLibraryException("The manifest describes a different publication")
                synchronized(manifests) {
                    manifests[key] = it
                    while (manifests.size > 2) manifests.remove(manifests.keys.first())
                }
            }
        } ?: throw LibraryUnsupportedException("This book has no supported image stream. Acquire its EPUB/CBZ to read it offline.")
        permitted(actual)
        if (actual.readingOrder.isEmpty() || actual.readingOrder.size > MAX_LIBRARY_PAGES ||
            actual.readingOrder.any { !it.isImage || it.templated }) {
            throw LibraryUnsupportedException("This manifest is not an image-only publication. Acquire its EPUB/CBZ instead.")
        }
        return actual
    }

    fun permitted(publication: LibraryPublication): LibraryPublication {
        ensureConnection()
        val effective = publication.content.takeUnless { it == RemoteLibraryContent.UNKNOWN } ?: connection.content
        if (!allowsContent(effective)) throw LibraryUnsupportedException("This publication is hidden by the current content restrictions")
        return publication
    }

    private fun filter(feed: LibraryFeed): LibraryFeed = feed.copy(
        publications = feed.publications.filter(::isAllowed),
        groups = feed.groups.map { it.copy(publications = it.publications.filter(::isAllowed)) },
    )

    private fun isAllowed(publication: LibraryPublication): Boolean = allowsContent(
        publication.content.takeUnless { it == RemoteLibraryContent.UNKNOWN } ?: connection.content)

    private fun ensureConnection() {
        if (!isCurrent()) throw LibraryUnsupportedException("This library connection changed or was disabled; reopen it before continuing")
        if (!connection.enabled) throw LibraryUnsupportedException("This library connection is disabled")
        if (!allowsConnection()) throw LibraryUnsupportedException("This library is hidden by the current content restrictions; review its declared content type in connection settings")
    }
}
