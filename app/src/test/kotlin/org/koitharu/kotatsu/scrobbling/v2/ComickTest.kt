package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComickTest {
    private val owner = TrackerOwner("COMICK", "12345678-1234-4123-8123-123456789abc")

    @Test
    fun mapsOpaqueMediaIdentityAndFractionalLabelOrMissingProgressWithoutZeroFallback() {
        val json = row()
        val entry = ComickCodec.entry(json, owner)
        assertEquals("aB7kP2xQ", entry.reference.mediaId)
        assertEquals("12.5", entry.progress?.number)
        assertEquals("2", entry.progress?.volume)
        assertEquals("8.5", entry.rating)
        assertNull(entry.total)
        json.getJSONObject("progress").put("number", "Extra A").put("hid", JSONObject.NULL)
        assertEquals("Extra A", ComickCodec.entry(json, owner).progress?.number)
        json.put("progress", JSONObject.NULL)
        assertNull(ComickCodec.entry(json, owner).progress)
        assertEquals("aB7kP2xQ", ComickCodec.entry(json, owner).reference.mediaId)
    }

    @Test
    fun statusOnlyPatchPreservesEveryUnselectedFieldAndUnsupportedValuesAreRejected() {
        val status = ComickCodec.patch(TrackerPatch.statusOnly("PAUSED"), false)
        assertEquals(setOf("status"), status.keys().asSequence().toSet())
        assertEquals(3, status.getInt("status"))
        val progress = ComickCodec.patch(TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("12.5", "2"))), false)
        assertEquals("12.5", progress.getJSONObject("progress").getString("number"))
        assertFalse(progress.has("status"))
        assertThrows(IllegalArgumentException::class.java) {
            ComickCodec.patch(TrackerPatch.statusOnly("REPEATING"), false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComickCodec.patch(TrackerPatch(rating = TrackerField.Set("8.5")), false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComickCodec.patch(TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("0"))), true)
        }
        val clear = ComickCodec.patch(TrackerPatch(progress = TrackerField.Set(null)), false)
        assertTrue(clear.isNull("progress"))
    }

    @Test
    fun nativePkceUsesPublishedS256AndNoSecretAndConsumesCallbackAndCodeOnce() {
        var now = 10_000L
        val oauth = ComickOAuth({ now })
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ComickOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val attempt = oauth.begin("registered-public-client", write = false)
        val url = oauth.authorizationUrl(attempt)
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(ComickOAuth.CALLBACK, url.queryParameter("redirect_uri"))
        assertFalse(url.queryParameter("scope").orEmpty().contains("library:write"))
        assertNull(url.queryParameter("client_secret"))
        val callback = "${ComickOAuth.CALLBACK}?code=one-use-code&state=${url.queryParameter("state")}"
        val result = oauth.consume(attempt, callback) as ComickOAuthCallback.Code
        val form = oauth.codeForm(attempt, result)
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("authorization_code", fields["grant_type"])
        assertFalse(fields.containsKey("client_secret"))
        assertThrows(IllegalArgumentException::class.java) { oauth.consume(attempt, callback) }
        assertThrows(IllegalStateException::class.java) { oauth.codeForm(attempt, result) }
        assertFalse(attempt.toString().contains(attempt.verifier))
        assertFalse(result.toString().contains("one-use-code"))
        val expired = oauth.begin("client", false)
        now += 600_001
        assertThrows(IllegalArgumentException::class.java) {
            oauth.consume(expired, "${ComickOAuth.CALLBACK}?code=x&state=${expired.state}")
        }
    }

    @Test
    fun callbacksRejectWrongAuthorityPathStateDuplicatesAndValidateDenial() {
        val oauth = ComickOAuth()
        val attempt = oauth.begin("client", true)
        val state = attempt.state
        for (uri in listOf(
            "io.github.glitch228.kaisoku://evil/oauth/comick?state=$state&code=x",
            "io.github.glitch228.kaisoku:/oauth/other?state=$state&code=x",
            "${ComickOAuth.CALLBACK}?state=wrong&code=x",
            "${ComickOAuth.CALLBACK}?state=$state&state=$state&code=x",
        )) assertThrows(IllegalArgumentException::class.java) { oauth.consume(attempt, uri) }
        val denial = oauth.consume(attempt, "${ComickOAuth.CALLBACK}?state=$state&error=access_denied")
        assertEquals(ComickOAuthCallback.Denied("access_denied"), denial)
    }

    @Test
    fun providerRequiresWriteGrantAndPatchesOnlyStatusWithVerifiedReadback() = runTest {
        val wire = FakeWire()
        val store = Store()
        val provider = ComickProvider(wire, store)
        val result = provider.patch(store.access.connection, "aB7kP2xQ", TrackerPatch.statusOnly("PAUSED"))
        assertEquals("PAUSED", result.status)
        assertEquals(3, wire.requests.size)
        val write = wire.requests.single { it.method == "PATCH" }
        val buffer = okio.Buffer()
        write.body!!.writeTo(buffer)
        assertEquals("{\"status\":3}", buffer.readUtf8())
        assertEquals("12.5", result.progress?.number)
        assertEquals("8.5", result.rating)
        store.access = ComickAccess(store.access.connection.copy(scopes = setOf("library:read")), "access-secret")
        val previous = wire.requests.size
        try {
            provider.patch(store.access.connection, "aB7kP2xQ", TrackerPatch.statusOnly("CURRENT"))
            fail("Read-only grant permitted a write")
        } catch (_: IllegalArgumentException) {
            assertEquals(previous, wire.requests.size)
        }
    }

    @Test
    fun providerKeepsOpaqueCursorAndSkipsAnimeRows() = runTest {
        var request: Request? = null
        val opaque = "encrypted+/cursor=="
        val wire = TrackerWire { req ->
            request = req
            TrackerWireResponse(200, JSONObject().put("data", org.json.JSONArray()
                .put(row()).put(row().put("media_type", "anime")))
                .put("next_cursor", opaque).toString())
        }
        val store = Store()
        val page = ComickProvider(wire, store).libraryPage(store.access.connection, opaque)
        assertEquals(1, page.entries.size)
        assertEquals(opaque, page.nextCursor)
        assertEquals(opaque, request!!.url.queryParameter("cursor"))
        assertEquals("manga", request!!.url.queryParameter("media_type"))
    }

    private inner class FakeWire : TrackerWire {
        val requests = mutableListOf<Request>()
        private var status = 1
        override suspend fun execute(request: Request): TrackerWireResponse {
            requests += request
            if (request.method == "PATCH") status = 3
            return TrackerWireResponse(200, JSONObject().put("data", row().put("status", status)).toString())
        }
    }

    private inner class Store : ComickAccessStore {
        var access = ComickAccess(TrackerConnection(owner, 1, setOf("library:read", "library:write")), "access-secret")
        override suspend fun current() = access
        override fun check(access: ComickAccess) { check(this.access === access) }
        override fun checkConnection(connection: TrackerConnection) { check(access.connection == connection) }
    }

    private fun row() = JSONObject("""{
        "hid":"aB7kP2xQ","title":"Example 漫画","slug":"example-manga","media_type":"manga",
        "status":1,"rating":8.5,"last_released_number":"45","cover_url":null,
        "progress":{"unit":"chapter","hid":null,"number":"12.5","volume":"2"},
        "titles":[{"title":"Alias","lang":"en"}],"external_ids":{"anilist":"30011","mangaupdates":"abc1234"},
        "url":"https://comick.dev/comic/example-manga","updated_at":"2026-10-01T00:00:00Z"
    }""")
}
