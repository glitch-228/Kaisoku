package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.TrackerTitle

class TrackerRawReadTest {
    private val title = TrackerTitle("9223372036854775807", "Fixture", url = "https://fixture.invalid/title")

    @Test fun rawResponseRetainsLargeIdentitiesAndDecimalLexemes() {
        val response = Response.Builder().request(Request.Builder().url(title.url).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"id":9223372036854775806,"progress_chapter":12.500000000000000001,"progress_volume":"2.25","state":"reading"}"""
                .toResponseBody("application/json".toMediaType())).build()
        val entry = TrackerRawRead.entry(ScrobblerService.MANGABAKA, "9007199254740993", title.mediaId,
            title, response.rawTrackerJson())
        assertEquals(title.mediaId, entry.reference.mediaId)
        assertEquals("9007199254740993", entry.reference.owner.accountId)
        assertEquals("12.500000000000000001", entry.progress!!.number)
        assertEquals("2.25", entry.progress!!.volume)
        assertNull(entry.progress!!.wholeChapterOrNull())
    }

    @Test fun anilistListEntryNeverNarrowsToAnInt() {
        val row = Json.parseToJsonElement("""{"id":"9223372036854775806","progress":42,"progressVolumes":3,"status":"CURRENT"}""") as JsonObject
        val entry = TrackerRawRead.entry(ScrobblerService.ANILIST, "42", title.mediaId, title, row)
        assertEquals("9223372036854775806", entry.reference.listEntryId)
        assertEquals("42", entry.progress!!.number)
        assertEquals("3", entry.progress!!.volume)
    }

    @Test fun unknownIsDifferentFromExplicitZeroAndOwnedVolumesAreNotReadProgress() {
        fun kitsu(text: String) = TrackerRawRead.entry(ScrobblerService.KITSU, "42", title.mediaId, title,
            Json.parseToJsonElement(text) as JsonObject)
        assertNull(kitsu("""{"id":"opaque-list-entry","attributes":{"status":"planned","volumesOwned":99}}""").progress)
        val zero = kitsu("""{"id":"opaque-list-entry","attributes":{"status":"current","progress":0,"reconsuming":true}}""")
        assertEquals("0", zero.progress!!.number)
        assertNull(zero.progress!!.volume)
        assertEquals("reconsuming", zero.status)
        assertEquals("opaque-list-entry", zero.reference.listEntryId)
    }

    @Test fun invalidUtf8DuplicateKeysAndExcessiveDepthAreRejectedAndBodiesClosed() {
        fun response(bytes: ByteArray): Pair<Response, () -> Boolean> {
            var closed = false
            val body = object : okhttp3.ResponseBody() {
                private val buffer = Buffer().write(bytes)
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun source(): okio.BufferedSource = buffer
                override fun close() { closed = true; super.close() }
            }
            return Response.Builder().request(Request.Builder().url(title.url).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build() to { closed }
        }
        val inputs = listOf(
            byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
                '"'.code.toByte(), ':'.code.toByte(), '"'.code.toByte(), 0xc3.toByte(), 0x28,
                '"'.code.toByte(), '}'.code.toByte()),
            """{"id":"first","\u0069d":"second"}""".toByteArray(),
            ("[".repeat(65) + "0" + "]".repeat(65)).toByteArray(),
            """{"id":"\uD800"}""".toByteArray(),
        )
        inputs.forEach { bytes ->
            val (response, closed) = response(bytes)
            val error = runCatching { response.rawTrackerElement() }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            assertTrue(closed())
        }
    }

    @Test fun strictValidationRetainsRawArrayRootNumbersAndValidUnicode() {
        val response = Response.Builder().request(Request.Builder().url(title.url).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""[{"id":"\uD83D\uDE00","progress":1.500000000000000001,"volume":1e2}]"""
                .toResponseBody("application/json".toMediaType())).build()
        val row = (response.rawTrackerElement() as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals("😀", row.raw("id"))
        assertEquals("1.500000000000000001", row.raw("progress"))
        assertEquals("1e2", row.raw("volume"))
    }

    @Test fun oversizedDeclaredOrStreamingResponsesAreRejected() {
        fun reject(declared: Boolean) {
            var closed = false
            val body = object : okhttp3.ResponseBody() {
                private val buffer = Buffer().write(ByteArray(if (declared) 1 else 8 * 1024 * 1024 + 1))
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = if (declared) 8 * 1024 * 1024L + 1 else -1L
                override fun source(): okio.BufferedSource = buffer
                override fun close() { closed = true; super.close() }
            }
            val response = Response.Builder().request(Request.Builder().url(title.url).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            assertTrue(runCatching { response.rawTrackerElement() }.exceptionOrNull() is java.io.IOException)
            assertTrue(closed)
        }
        reject(true)
        reject(false)
    }
}
