package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import org.koitharu.kotatsu.scrobbling.v2.TrackerTitle
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TrackerRawLibraryTest {
    private val revision = 24L
    private fun owner(service: ScrobblerService) = TrackerOwner(service.name,
        if (service == ScrobblerService.MANGABAKA) "opaque-user:fixture" else "42")
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun decode(service: ScrobblerService, value: String, cursor: String? = null) =
        TrackerRawLibrary.decode(service, owner(service), revision, cursor, json(value))
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    private fun anilist(id: String = "9007199254740993", list: String = "9223372036854775808", next: Boolean = false,
        user: String = "42", progress: String = "12.500000000000000001") = """
        {"data":{"Page":{"pageInfo":{"hasNextPage":$next},"mediaList":[{
        "id":"$list","userId":$user,"mediaId":"$id","status":"CURRENT","progress":$progress,
        "progressVolumes":"02.500","score":91.2500,"notes":"kept","updatedAt":1234567890,
        "media":{"id":"$id","type":"MANGA","chapters":123.50,"format":"NOVEL",
        "title":{"userPreferred":"漢字","native":"漢字","english":"Fixture"},"synonyms":["اسم"],
        "coverImage":{"large":"https://images.fixture.invalid/cover.jpg"}}}]}}}
    """.trimIndent()

    @Test fun anilistKeepsRawIdentityProgressVolumesRatingAliasesAndTotal() {
        val entry = decode(ScrobblerService.ANILIST, anilist()).entries.single()
        assertEquals("9007199254740993", entry.reference.mediaId)
        assertEquals("9223372036854775808", entry.reference.listEntryId)
        assertEquals("12.500000000000000001", entry.progress!!.number)
        assertEquals("02.500", entry.progress!!.volume)
        assertEquals("91.2500", entry.rating)
        assertEquals("123.50", entry.total)
        assertTrue(entry.title.aliases.contains("اسم"))
        assertEquals("NOVEL", entry.title.format)
    }

    @Test fun requestsUseBoundedReadOnlyProviderContractsAndNeverFollowNextUrls() {
        ScrobblerService.entries.forEach { service ->
            val request = TrackerRawLibrary.request(service, owner(service), revision, null)
            assertEquals(if (service == ScrobblerService.ANILIST) "POST" else "GET", request.method)
            assertEquals("https", request.url.scheme)
            assertNull(request.header("Authorization"))
            assertFalse(request.url.toString().contains("fixture-token"))
            if (service == ScrobblerService.ANILIST) {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val query = (json(buffer.readUtf8()) as JsonObject).raw("query")!!
                assertTrue(query.startsWith("query")); assertTrue(query.contains("mediaList(userId: 42"))
                assertTrue(query.contains("sort: MEDIA_ID")); assertFalse(query.contains("mutation"))
                assertFalse(query.contains("MediaListCollection"))
            }
        }
        val next = decode(ScrobblerService.MAL, mal(true)).nextCursor!!
        val second = TrackerRawLibrary.request(ScrobblerService.MAL, owner(ScrobblerService.MAL), revision, next)
        assertEquals("api.myanimelist.net", second.url.host)
        assertEquals("100", second.url.queryParameter("offset"))
        assertEquals("100", second.url.queryParameter("limit"))
    }

    private fun mal(next: Boolean = false, id: String = "9007199254740993") = """
        {"data":[{"node":{"id":"$id","title":"Fixture","num_chapters":100.00,
        "alternative_titles":{"en":"Other","synonyms":["Alias"]},"media_type":"novel"},
        "list_status":{"status":"reading","num_chapters_read":0,"num_volumes_read":2.500,
        "score":8,"comments":"note","is_rereading":true}}],"paging":{"next":${if (next) "\"https://evil.invalid/steal?token=bad\"" else "null"}}}
    """.trimIndent()

    @Test fun malRetainsZeroVolumeAndRereadWithoutNarrowingMediaIdentity() {
        val entry = decode(ScrobblerService.MAL, mal()).entries.single()
        assertEquals("9007199254740993", entry.reference.mediaId)
        assertEquals(entry.reference.mediaId, entry.reference.listEntryId)
        assertEquals("0", entry.progress!!.number); assertEquals("2.500", entry.progress!!.volume)
        assertEquals("rereading", entry.status); assertEquals("100.00", entry.total)
        assertNull(decode(ScrobblerService.MAL, mal()).nextCursor)
    }

    private fun kitsu(account: String = "42", includedId: String = "9007199254740993") = """
        {"data":[{"id":"opaque-list:uuid","type":"libraryEntries","attributes":{"status":"current",
        "progress":5e1,"volumesOwned":99,"ratingTwenty":"17.5000","reconsuming":true},
        "relationships":{"user":{"data":{"id":"$account","type":"users"}},
        "manga":{"data":{"id":"9007199254740993","type":"manga"}}}}],
        "included":[{"id":"$includedId","type":"manga","attributes":{"canonicalTitle":"Fixture",
        "titles":{"en":"Fixture","ar":"رواية"},"chapterCount":100.25,"subtype":"novel"}}],"links":{"next":null}}
    """.trimIndent()

    @Test fun kitsuPreservesOpaqueEntriesAndDoesNotConfuseOwnedVolumesWithReading() {
        val entry = decode(ScrobblerService.KITSU, kitsu()).entries.single()
        assertEquals("opaque-list:uuid", entry.reference.listEntryId)
        assertEquals("5e1", entry.progress!!.number); assertNull(entry.progress!!.volume)
        assertEquals("17.5000", entry.rating); assertEquals("reconsuming", entry.status)
        assertEquals("100.25", entry.total)
        rejected { decode(ScrobblerService.KITSU, kitsu("43")) }
        rejected { decode(ScrobblerService.KITSU, kitsu(includedId = "12")) }
    }

    private fun shikimori(first: Int, count: Int): String = JsonArray((first until first + count).map { i ->
        json("""{"id":"list-$i","user_id":42,"target_type":"Manga","target_id":$i,
            "status":"watching","chapters":12.50,"volumes":2.75,"score":8.00,
            "manga":{"id":$i,"name":"Fixture $i","kind":"novel","chapters":50}}""")
    }).toString()

    @Test fun shikimoriLookaheadAppearsOnTheNextPageExactlyOnce() = runBlocking {
        val first = decode(ScrobblerService.SHIKIMORI, shikimori(1, 101))
        assertEquals(100, first.entries.size); assertEquals("100", first.entries.last().reference.mediaId)
        val request = TrackerRawLibrary.request(ScrobblerService.SHIKIMORI, owner(ScrobblerService.SHIKIMORI), revision, first.nextCursor)
        assertEquals("2", request.url.queryParameter("page")); assertEquals("100", request.url.queryParameter("limit"))
        val second = decode(ScrobblerService.SHIKIMORI, shikimori(101, 2), first.nextCursor)
        assertNull(second.nextCursor)
        var calls = 0
        val all = collectRawTrackerLibraryPages(owner(ScrobblerService.SHIKIMORI)) { cursor ->
            calls++; if (cursor == null) first else second
        }
        assertEquals(2, calls); assertEquals(102, all.size)
        assertEquals("101", all[100].reference.mediaId)
        assertEquals("12.50", all[100].progress!!.number)
        assertEquals("2.75", all[100].progress!!.volume)
    }

    private fun baka(user: String = "opaque-user:fixture", page: Int = 1, next: Boolean = false) = """
        {"pagination":{"page":$page,"limit":100,"next":${if (next) "\"https://evil.invalid/next\"" else "null"}},
        "data":[{"entry":{"id":9223372036854775808,"series_id":9007199254740993,
        "user_id":"$user","state":"reading","progress_chapter":12.500000000000000001,
        "progress_volume":"02.50","rating":81.2500,"note":"note"},"lists":[],"series":{
        "id":9007199254740993,"titles":[{"title":"Fixture","is_primary":true},{"title":"別名"}],
        "type":"novel","total_chapters":500.000,"cover":{"x250":"https://covers.fixture.invalid/a"}}}]}
    """.trimIndent()

    @Test fun mangaBakaUsesVerifiedOpaqueOwnerActualListIdentityAndFractionalLexemes() {
        val entry = decode(ScrobblerService.MANGABAKA, baka()).entries.single()
        assertEquals("opaque-user:fixture", entry.reference.owner.accountId)
        assertEquals("9223372036854775808", entry.reference.listEntryId)
        assertEquals("9007199254740993", entry.reference.mediaId)
        assertEquals("12.500000000000000001", entry.progress!!.number)
        assertEquals("02.50", entry.progress!!.volume); assertEquals("81.2500", entry.rating)
        assertEquals("500.000", entry.total)
        rejected { decode(ScrobblerService.MANGABAKA, baka(user = "another-user")) }
        rejected { decode(ScrobblerService.MANGABAKA, baka(page = 2)) }
    }

    @Test fun cursorCannotCrossAccountProviderRevisionOrCanonicalBounds() {
        val service = ScrobblerService.ANILIST
        val cursor = decode(service, anilist(next = true)).nextCursor!!
        rejected { TrackerRawLibrary.request(service, TrackerOwner(service.name, "43"), revision, cursor) }
        rejected { TrackerRawLibrary.request(service, owner(service), revision + 1, cursor) }
        rejected { TrackerRawLibrary.request(ScrobblerService.MAL, owner(ScrobblerService.MAL), revision, cursor) }
        rejected { TrackerRawLibrary.request(service, owner(service), revision, cursor.replace(":2:", ":02:")) }
        rejected { TrackerRawLibrary.request(service, owner(service), revision, "a".repeat(257)) }
        rejected { TrackerRawLibrary.request(service, owner(service), revision, cursor.replace(":2:", ":2147483648:")) }
        rejected { decode(service, anilist(next = true), cursor) }
    }

    @Test fun providerErrorsMismatchedOwnerMediaAndEmptyNextPagesAreRejected() {
        rejected { decode(ScrobblerService.ANILIST, anilist(user = "43")) }
        rejected { decode(ScrobblerService.ANILIST, anilist().replace("\"type\":\"MANGA\"", "\"type\":\"ANIME\"")) }
        rejected { decode(ScrobblerService.ANILIST, """{"errors":[{"message":"denied"}],"data":null}""") }
        rejected { decode(ScrobblerService.MAL, """{"data":[],"paging":{"next":"https://unused.invalid"}}""") }
        rejected { decode(ScrobblerService.KITSU, """{"errors":[{"detail":"denied"}]}""") }
    }

    @Test fun oversizedDuplicateAndUnboundedPagesAreRejectedWithoutSilentlyTruncating() {
        rejected { decode(ScrobblerService.SHIKIMORI, shikimori(1, 102)) }
        rejected { decode(ScrobblerService.MAL, mal().replace("\"data\":[", "\"data\":[null,")) }
        val first = decode(ScrobblerService.ANILIST, anilist(next = true))
        val lastAllowedCursor = first.nextCursor!!.replace(":2:", ":1000:")
        rejected { decode(ScrobblerService.ANILIST, anilist(id = "2", list = "3", next = true), lastAllowedCursor) }
        val validLast = decode(ScrobblerService.ANILIST, anilist(id = "2", list = "3"), lastAllowedCursor)
        assertNull(validLast.nextCursor)
        val duplicated = json(anilist()) as JsonObject
        val data = duplicated.obj("data")
        val page = data.obj("Page")
        val row = page.array("mediaList").single()
        val copy = JsonObject(duplicated + ("data" to JsonObject(data + ("Page" to
            JsonObject(page + ("mediaList" to JsonArray(listOf(row, row))))))))
        rejected { TrackerRawLibrary.decode(ScrobblerService.ANILIST, owner(ScrobblerService.ANILIST), revision, null, copy) }
    }

    private fun entry(id: String, list: String = id) = TrackerRemoteEntry(
        TrackerReference(owner(ScrobblerService.MAL), id, list), TrackerTitle(id, "Fixture $id", url = "https://fixture.invalid/$id"),
        "reading", null)

    @Test fun collectorRejectsCyclesConflictingDuplicatesWrongOwnersAndPartialFailures() = runBlocking {
        var calls = 0
        var error = runCatching { collectRawTrackerLibraryPages(owner(ScrobblerService.MAL)) {
            calls++; TrackerLibraryPage(listOf(entry("1")), "cursor-$calls")
        } }.exceptionOrNull()
        assertNotNull(error); assertEquals(2, calls)
        calls = 0
        error = runCatching { collectRawTrackerLibraryPages(owner(ScrobblerService.MAL)) {
            calls++; if (calls == 1) TrackerLibraryPage(listOf(entry("1")), "next")
            else TrackerLibraryPage(listOf(entry("1").copy(status = "completed"), entry("2")), null)
        } }.exceptionOrNull()
        assertNotNull(error)
        error = runCatching { collectRawTrackerLibraryPages(owner(ScrobblerService.MAL)) {
            TrackerLibraryPage(listOf(entry("1").copy(reference = TrackerReference(TrackerOwner("MAL", "43"), "1"))), null)
        } }.exceptionOrNull()
        assertNotNull(error)
        calls = 0
        error = runCatching { collectRawTrackerLibraryPages(owner(ScrobblerService.MAL)) {
            calls++; if (calls == 1) TrackerLibraryPage(listOf(entry("1")), "next") else throw java.io.IOException("fixture failure")
        } }.exceptionOrNull()
        assertTrue(error is java.io.IOException); assertEquals(2, calls)
    }

    @Test fun collectorRetainsDistinctEntriesAndStopsAtTheExplicitBound() = runBlocking {
        val sameWork = collectRawTrackerLibraryPages(owner(ScrobblerService.MAL)) {
            TrackerLibraryPage(listOf(entry("1", "edition-a"), entry("1", "edition-b")), null)
        }
        assertEquals(2, sameWork.size)
        var calls = 0
        val error = runCatching { collectRawTrackerLibraryPages(owner(ScrobblerService.MAL)) {
            val start = calls++ * 100
            TrackerLibraryPage((start until start + 100).map { entry(it.toString()) }, "cursor-$calls")
        } }.exceptionOrNull()
        assertNotNull(error); assertEquals(201, calls)
    }

    @Test fun cancellationStopsBeforeAnotherPageAndReadRejectsCancellationAfterFetch() = runBlocking {
        var calls = 0
        val job = launch {
            collectRawTrackerLibraryPages(owner(ScrobblerService.MAL)) {
                calls++; currentCoroutineContext().cancel()
                TrackerLibraryPage(listOf(entry("1")), "next")
            }
        }
        job.join(); assertTrue(job.isCancelled); assertEquals(1, calls)
        var decoded = false
        val reader = launch {
            TrackerRawLibrary.read(ScrobblerService.MAL, owner(ScrobblerService.MAL), revision, null) {
                currentCoroutineContext().cancel(); json(mal())
            }
            decoded = true
        }
        reader.join(); assertTrue(reader.isCancelled); assertFalse(decoded)
    }

    @Test fun actualLoopbackBodyRunsThroughTheBoundedLosslessReadPipeline() = runBlocking {
        val bytes = mal().toByteArray()
        val failure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = thread(isDaemon = true, name = "raw-library-fixture") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        socket.getOutputStream().write(bytes); socket.getOutputStream().flush()
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            val page = TrackerRawLibrary.read(ScrobblerService.MAL, owner(ScrobblerService.MAL), revision, null) { request ->
                val loopback = request.newBuilder().url("http://127.0.0.1:${server.localPort}/fixture").build()
                OkHttpClient().newCall(loopback).execute().rawTrackerElement()
            }
            responder.join(5_000); assertFalse(responder.isAlive); assertNull(failure.get())
            assertEquals("9007199254740993", page.entries.single().reference.mediaId)
            assertEquals("2.500", page.entries.single().progress!!.volume)
        }
    }
}
