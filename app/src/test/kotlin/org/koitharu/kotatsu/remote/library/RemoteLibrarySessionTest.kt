package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RemoteLibrarySessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cachedCatalogIsImmediateWithoutRefetchAndSurvivesRefreshFailure() = runBlocking {
        val calls = AtomicInteger()
        LibraryHttpFixture { _, socket ->
            if (calls.incrementAndGet() == 1) socket.respond(feed("book-A")) else socket.respond("offline", 503)
        }.use { server ->
            val session = session(server)
            assertFalse(session.feed().fromCache)
            assertTrue(session.feed().fromCache)
            assertEquals(1, calls.get())
            val failed = session.feed(refresh = true)
            assertTrue(failed.fromCache)
            assertNotNull(failed.refreshError)
            assertEquals("book-A", failed.feed.publications.single().identifier)
            assertEquals(2, calls.get())
            assertEquals("book-A", session(server).feed().feed.publications.single().identifier)
            assertEquals(2, calls.get())
        }
    }

    @Test fun actualRedirectBaseIsRetainedInSavedDocuments() = runBlocking {
        LibraryHttpFixture { request, socket ->
            if (request.path == "/library/catalog") socket.respond("", 302, headers = mapOf("Location" to "/library/nested/catalog"))
            else socket.respond("""{"metadata":{"title":"Books"},"navigation":[{"title":"Shelf","href":"shelf","type":"application/opds+json"}]}""")
        }.use { server ->
            val current = session(server)
            val fresh = current.feed()
            val cached = current.feed()
            assertEquals(server.base + "nested/shelf", fresh.feed.navigation.single().href)
            assertEquals(fresh.feed.navigation, cached.feed.navigation)
            assertEquals(2, server.requests.size)
        }
    }

    @Test fun pageStreamsAndImageManifestsPreserveOriginalOrderingAndGeometry() = runBlocking {
        LibraryHttpFixture { _, socket -> socket.respond("""{"metadata":{"identifier":"book","title":"Book"},
            "readingOrder":[{"href":"page0.png","type":"image/png","width":4000,"height":6000},
                {"href":"page1.png","type":"image/png","width":8000,"height":3000}]}""", contentType = "application/divina+json") }.use { server ->
            val current = session(server)
            val stream = LibraryPublication("book", "Book", server.base + "catalog", links = listOf(
                LibraryLink(server.base + "page/{pageNumber}", "image/jpeg", setOf(LibraryLink.PSE_STREAM), templated = true, pageCount = 2)))
            assertEquals(listOf(server.base + "page/0", server.base + "page/1"), current.pages(stream).map { it.url })
            assertTrue(server.requests.isEmpty())
            val manifest = stream.copy(links = listOf(LibraryLink(server.base + "manifest", "application/divina+json", setOf("manifest"))))
            val pages = current.pages(manifest)
            assertEquals(listOf(4000, 8000), pages.map { it.width })
            assertEquals(listOf(6000, 3000), pages.map { it.height })
            assertEquals(listOf(server.base + "page0.png", server.base + "page1.png"), pages.map { it.url })
            assertEquals(pages, current.pages(manifest))
            assertEquals(1, server.requests.size)
            val text = stream.copy(links = emptyList(), readingOrder = listOf(LibraryLink(server.base + "chapter.html", "text/html")))
            assertTrue(runCatching { current.pages(text) }.exceptionOrNull() is LibraryUnsupportedException)
        }
    }

    @Test fun unknownAndAdultRestrictionsApplyToCachedAndDirectPublicationPaths() = runBlocking {
        LibraryHttpFixture { _, socket -> socket.respond("""{"metadata":{"title":"Books"},"publications":[
            {"metadata":{"identifier":"safe","title":"Safe","contentRating":"safe"}},
            {"metadata":{"identifier":"adult","title":"Adult","contentRating":"adult"}},
            {"metadata":{"identifier":"unknown","title":"Unknown"}}]}""") }.use { server ->
            val current = session(server, allows = { it == RemoteLibraryContent.SAFE })
            assertEquals(listOf("safe", "unknown"), current.feed().feed.publications.map { it.identifier })
            assertTrue(runCatching { current.publication("adult") }.exceptionOrNull() is LibraryUnsupportedException)
            val blocked = session(server, connection = fixtureConnection(server, RemoteLibraryContent.UNKNOWN), allows = { it == RemoteLibraryContent.SAFE })
            assertTrue(runCatching { blocked.feed() }.exceptionOrNull() is LibraryUnsupportedException)
            assertEquals(1, server.requests.size)
        }
    }

    @Test fun independentSearchDoesNotConsumeBrowsePaginationOrMergeEqualTitles() = runBlocking {
        LibraryHttpFixture { request, socket ->
            val id = when { "query=" in request.path -> "search-book"; "page=2" in request.path -> "book-B"; else -> "book-A" }
            socket.respond(feed(id, next = if (id == "book-A") "?page=2" else null))
        }.use { server ->
            val current = session(server)
            val source = RemoteLibrarySource(current.connection.id)
            val repository = RemoteLibraryMangaRepository(source, current)
            val first = repository.getList(0, null, null).single()
            current.search(current.feed().feed, "日本語 & title")
            val second = repository.getList(1, null, null).single()
            assertNotEquals(first.id, second.id)
            assertEquals(first.title, second.title)
            assertEquals("book-A", repository.identifier(first.url))
            assertEquals("book-B", repository.identifier(second.url))
            assertEquals(3, server.requests.size)
            assertEquals(first.id, repository.toManga(current.publication("book-A").copy(documentUrl = server.base + "new-location")).id)
            assertThrows(RemoteLibraryException::class.java) { repository.identifier(first.url.replace(source.connectionId, "00000000-0000-0000-0000-000000000099")) }
            val seen = setOf(current.feed().feed.next!!.href)
            assertTrue(runCatching { current.next(current.feed().feed, seen) }.exceptionOrNull() is RemoteLibraryException)
        }
    }

    @Test fun concurrentRefreshesShareTheCompletedRequestInsteadOfRefetching() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        LibraryHttpFixture { _, socket ->
            val number = calls.incrementAndGet()
            if (number > 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            socket.respond(feed("book-$number"))
        }.use { server ->
            val current = session(server)
            current.feed()
            val first = async(Dispatchers.IO) { current.feed(refresh = true) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { current.feed(refresh = true) }
            release.countDown()
            val values = withTimeout(5000) { listOf(first.await(), second.await()) }
            assertEquals(2, calls.get())
            assertEquals(listOf("book-2", "book-2"), values.map { it.feed.publications.single().identifier })
        }
    }

    @Test fun supersededOrDisabledSessionRejectsCachedCatalogPublicationAndTransport() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        LibraryHttpFixture { _, socket -> socket.respond(feed("book")) }.use { server ->
            val connection = fixtureConnection(server)
            val guard = { if (!active.get()) throw LibraryUnsupportedException("Connection changed") }
            val transport = RemoteLibraryTransport(server.policy, { null }, currentConnection = guard)
            val current = RemoteLibrarySession(connection, transport, LibraryCache(File(temporary.root, "cache")), isCurrent = active::get)
            current.feed()
            assertEquals("book", current.publication("book").identifier)
            active.set(false)
            assertTrue(runCatching { current.feed() }.exceptionOrNull() is LibraryUnsupportedException)
            assertTrue(runCatching { current.publication("book") }.exceptionOrNull() is LibraryUnsupportedException)
            assertTrue(runCatching { transport.document(server.base + "catalog") }.exceptionOrNull() is LibraryUnsupportedException)
            assertEquals(1, server.requests.size)
        }
    }

    private fun session(server: LibraryHttpFixture, connection: RemoteLibraryConnection = fixtureConnection(server),
                        allows: (RemoteLibraryContent) -> Boolean = { true }) = RemoteLibrarySession(connection,
        RemoteLibraryTransport(server.policy, { null }), LibraryCache(File(temporary.root, "cache")), allows)

    private fun feed(id: String, next: String? = null): String = """{"metadata":{"title":"Books"},
        "links":[{"rel":"search","href":"search{?query}","type":"application/opds+json","templated":true}
            ${next?.let { ",{\"rel\":\"next\",\"href\":\"$it\",\"type\":\"application/opds+json\"}" }.orEmpty()}],
        "publications":[{"metadata":{"identifier":"$id","title":"Same title"},
            "links":[{"rel":"download","href":"$id.cbz","type":"application/vnd.comicbook+zip"}]}]}"""
}
