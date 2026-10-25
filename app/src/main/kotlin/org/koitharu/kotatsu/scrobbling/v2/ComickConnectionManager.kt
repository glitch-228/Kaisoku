package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class TrackerReconnectRequired : IllegalStateException("Reconnect the tracker to continue")

/** The native connection state is independent from legacy numeric accounts and plain token preferences. */
class ComickConnectionManager(
    private val wire: TrackerWire,
    private val vault: TrackerCredentialVault,
    private val oauth: ComickOAuth = ComickOAuth(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ComickAccessStore {
    private val mutex = Mutex()
    @Volatile private var active: StoredConnection? = null
    private var loaded = false
    private var revision = 0L

    /** Local-only status for opening settings; an expired connection is not refreshed automatically. */
    suspend fun knownConnection(): TrackerConnection? = mutex.withLock { load(); active?.public() }

    suspend fun connect(attempt: ComickOAuthAttempt, code: ComickOAuthCallback.Code): TrackerConnection = mutex.withLock {
        val response = wire.execute(Request.Builder().url(ComickOAuth.TOKEN).post(oauth.codeForm(attempt, code)).build())
        val tokens = parseTokens(response.requireSuccess("COMICK"), attempt.clientId)
        val user = wire.execute(Request.Builder().url("${ComickOAuth.RESOURCE}/me")
            .header("Authorization", "Bearer ${tokens.access}").get().build())
            .requireSuccess("COMICK", listOf(tokens.access, tokens.refresh.orEmpty()))
        val owner = TrackerOwner("COMICK", user.getString("id"))
        check(runCatching { java.util.UUID.fromString(owner.accountId) }.isSuccess) { "Invalid Comick account identity" }
        val connection = StoredConnection(owner, user.stringOrNull("username"), tokens, ++revision)
        persist(connection)
        connection.public()
    }

    override suspend fun current(): ComickAccess = mutex.withLock {
        load()
        var connection = active ?: throw TrackerReconnectRequired()
        if (connection.tokens.expiresAt <= clock() + 30_000L) {
            connection = refresh(connection)
        }
        connection.access()
    }

    override fun check(access: ComickAccess) {
        val current = active ?: throw TrackerReconnectRequired()
        check(current.owner == access.connection.owner && current.revision == access.connection.revision &&
            current.tokens.access == access.accessToken) { "Comick connection changed; review this action again" }
    }

    override fun checkConnection(connection: TrackerConnection) {
        val current = active ?: throw TrackerReconnectRequired()
        check(current.owner == connection.owner && current.revision == connection.revision) {
            "Comick connection changed; review this action again"
        }
    }

    /** User-initiated disconnect erases local credentials even when revocation is temporarily unavailable. */
    suspend fun disconnect(revoke: Boolean) = mutex.withLock {
        load()
        val previous = active
        active = null
        revision++
        withContext(NonCancellable) { vault.clear("COMICK") }
        if (revoke && previous != null) {
            for ((token, refresh) in listOf(previous.tokens.refresh to true, previous.tokens.access to false)) {
                if (token == null) continue
                val response = wire.execute(Request.Builder().url(ComickOAuth.REVOKE)
                    .post(oauth.revokeForm(previous.tokens.clientId, token, refresh)).build())
                if (!response.successful) response.requireSuccess("COMICK", listOf(token))
            }
        }
    }

    private suspend fun refresh(previous: StoredConnection): StoredConnection {
        val token = previous.tokens.refresh ?: run {
            forget()
            throw TrackerReconnectRequired()
        }
        val response = try {
            wire.execute(Request.Builder().url(ComickOAuth.TOKEN)
                .post(oauth.refreshForm(previous.tokens.clientId, token)).build())
        } catch (e: CancellationException) {
            // A consumed rotating credential cannot be safely replayed after an unknown outcome.
            forget()
            throw e
        } catch (_: IOException) {
            forget()
            throw TrackerReconnectRequired()
        }
        if (!response.successful) {
            forget()
            throw TrackerProviderError("COMICK", response.code, response.retryAfterMillis, response.body,
                listOf(token, previous.tokens.access))
        }
        val tokens = try { parseTokens(JSONObject(response.body), previous.tokens.clientId) }
        catch (_: Exception) { forget(); throw TrackerReconnectRequired() }
        // Rotation is mandatory; retaining the old refresh token would allow an unsafe retry.
        if (tokens.refresh == token) {
            forget()
            throw TrackerReconnectRequired()
        }
        return previous.copy(tokens = tokens, revision = ++revision).also { persist(it) }
    }

    private suspend fun load() {
        if (loaded) return
        loaded = true
        val raw = try { vault.read("COMICK") }
        catch (e: CancellationException) { loaded = false; throw e }
        catch (_: Exception) { null } ?: return
        active = try {
            val json = JSONObject(raw)
            val tokens = Tokens(json.getString("client_id"), json.getString("access_token"),
                json.stringOrNull("refresh_token"), json.getLong("expires_at"),
                json.getString("scope").split(' ').filter(String::isNotBlank).toSet())
            require("library:read" in tokens.scopes)
            StoredConnection(TrackerOwner("COMICK", json.getString("account_id")), json.stringOrNull("username"),
                tokens, ++revision)
        } catch (_: Exception) {
            vault.clear("COMICK")
            null
        }
    }

    private suspend fun persist(connection: StoredConnection) = withContext(NonCancellable) {
        try {
            vault.write("COMICK", JSONObject().apply {
                put("account_id", connection.owner.accountId)
                put("username", connection.nickname)
                put("client_id", connection.tokens.clientId)
                put("access_token", connection.tokens.access)
                put("refresh_token", connection.tokens.refresh)
                put("expires_at", connection.tokens.expiresAt)
                put("scope", connection.tokens.scopes.sorted().joinToString(" "))
            }.toString())
            active = connection
            loaded = true
        } catch (_: Exception) {
            active = null
            vault.clear("COMICK")
            throw TrackerReconnectRequired()
        }
    }

    private suspend fun forget() = withContext(NonCancellable) {
        active = null
        revision++
        vault.clear("COMICK")
    }

    private fun parseTokens(json: JSONObject, clientId: String): Tokens {
        require(json.getString("token_type").equals("Bearer", true))
        val scopes = json.getString("scope").split(' ').filter(String::isNotBlank).toSet()
        require("library:read" in scopes) { "Comick library read permission was not granted" }
        val lifetime = json.getLong("expires_in").also { require(it in 1..86_400) }
        val access = json.getString("access_token").also { require(it.isNotBlank()) }
        return Tokens(clientId, access, json.stringOrNull("refresh_token"), clock() + lifetime * 1000L, scopes)
    }

    private data class Tokens(
        val clientId: String,
        val access: String,
        val refresh: String?,
        val expiresAt: Long,
        val scopes: Set<String>,
    ) {
        override fun toString(): String = "ComickTokens(expiresAt=$expiresAt, scopes=$scopes)"
    }

    private data class StoredConnection(
        val owner: TrackerOwner,
        val nickname: String?,
        val tokens: Tokens,
        val revision: Long,
    ) {
        fun public() = TrackerConnection(owner, revision, tokens.scopes, nickname)
        fun access() = ComickAccess(public(), tokens.access)
    }
}
