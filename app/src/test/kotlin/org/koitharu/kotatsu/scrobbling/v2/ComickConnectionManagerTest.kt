package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ComickConnectionManagerTest {
    @Test
    fun verifiedAccountAndGrantedScopeAreStoredAtomicallyInTheProtectedVault() = runTest {
        val vault = Vault()
        val wire = Wire()
        val oauth = ComickOAuth({ 10_000L })
        val manager = ComickConnectionManager(wire, vault, oauth) { 10_000L }
        val attempt = oauth.begin("public-client", true)
        val code = oauth.consume(attempt, "${ComickOAuth.CALLBACK}?code=one-use&state=${attempt.state}")
            as ComickOAuthCallback.Code
        val connection = manager.connect(attempt, code)
        assertEquals("12345678-1234-4123-8123-123456789abc", connection.owner.accountId)
        // The user declined writes even though the app requested them.
        assertEquals(setOf("library:read", "offline_access"), connection.scopes)
        assertEquals(1, vault.writes)
        val first = manager.current()
        assertFalse(first.toString().contains("access-one"))
        assertEquals(2, wire.requests.size)
        val restored = ComickConnectionManager(wire, vault, ComickOAuth({ 10_000L })) { 10_000L }
        assertEquals(connection.owner, restored.current().connection.owner)
        assertEquals(2, wire.requests.size)
    }

    @Test
    fun concurrentExpiryChecksRotateOnceAndReplaceRefreshTokenWithoutReusingIt() = runTest {
        var now = 10_000L
        val vault = Vault()
        val wire = Wire()
        val oauth = ComickOAuth({ now })
        val manager = ComickConnectionManager(wire, vault, oauth) { now }
        val attempt = oauth.begin("public-client", false)
        val code = oauth.consume(attempt, "${ComickOAuth.CALLBACK}?code=one-use&state=${attempt.state}")
            as ComickOAuthCallback.Code
        manager.connect(attempt, code)
        val stale = manager.current()
        now += 900_000
        val renewed = List(3) { async { manager.current() } }.awaitAll()
        assertEquals(1, wire.refreshes)
        assertEquals(setOf("access-two"), renewed.map { it.accessToken }.toSet())
        assertTrue(vault.value.orEmpty().contains("refresh-two"))
        assertFalse(vault.value.orEmpty().contains("refresh-one"))
        assertThrows(IllegalStateException::class.java) { manager.check(stale) }
    }

    @Test
    fun ambiguousRotatingRefreshStopsAndRequiresReconnectWithoutAutomaticReplay() = runTest {
        var now = 10_000L
        val vault = Vault()
        val wire = Wire()
        val oauth = ComickOAuth({ now })
        val manager = ComickConnectionManager(wire, vault, oauth) { now }
        val attempt = oauth.begin("public-client", false)
        val code = oauth.consume(attempt, "${ComickOAuth.CALLBACK}?code=one-use&state=${attempt.state}")
            as ComickOAuthCallback.Code
        manager.connect(attempt, code)
        now += 900_000
        wire.failRefresh = true
        repeat(2) {
            try { manager.current(); fail("An ambiguous rotating token must not be retried") }
            catch (_: TrackerReconnectRequired) { }
        }
        assertEquals(1, wire.refreshes)
        assertNull(vault.value)
    }

    @Test
    fun explicitLocalDisconnectClearsCredentialsAndDoesNotUnfollowTitles() = runTest {
        val vault = Vault()
        val wire = Wire()
        val oauth = ComickOAuth({ 10_000L })
        val manager = ComickConnectionManager(wire, vault, oauth) { 10_000L }
        val attempt = oauth.begin("public-client", false)
        val code = oauth.consume(attempt, "${ComickOAuth.CALLBACK}?code=one-use&state=${attempt.state}")
            as ComickOAuthCallback.Code
        manager.connect(attempt, code)
        manager.disconnect(revoke = false)
        assertNull(vault.value)
        assertEquals(2, wire.requests.size)
        assertTrue(wire.requests.none { it.method == "DELETE" || it.url.encodedPath.contains("/me/library/") })
        try { manager.current(); fail("Disconnected account accepted") } catch (_: TrackerReconnectRequired) { }
    }

    private class Vault : TrackerCredentialVault {
        var value: String? = null
        var writes = 0
        override suspend fun read(provider: String) = value
        override suspend fun write(provider: String, value: String) { this.value = value; writes++ }
        override suspend fun clear(provider: String) { value = null }
    }

    private class Wire : TrackerWire {
        val requests = mutableListOf<Request>()
        var refreshes = 0
        var failRefresh = false
        override suspend fun execute(request: Request): TrackerWireResponse {
            requests += request
            if (request.url.encodedPath.endsWith("/me")) return TrackerWireResponse(200,
                """{"id":"12345678-1234-4123-8123-123456789abc","username":"Reader"}""")
            val body = request.body?.let { okio.Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            val refresh = body.contains("grant_type=refresh_token")
            if (refresh) {
                refreshes++
                if (failRefresh) throw IOException("response lost")
            }
            val suffix = if (refresh) "two" else "one"
            return TrackerWireResponse(200, JSONObject().put("token_type", "Bearer")
                .put("access_token", "access-$suffix").put("refresh_token", "refresh-$suffix")
                .put("scope", "library:read offline_access").put("expires_in", 900).toString())
        }
    }
}
