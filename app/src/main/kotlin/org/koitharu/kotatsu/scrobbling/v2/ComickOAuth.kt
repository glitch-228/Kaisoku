package org.koitharu.kotatsu.scrobbling.v2

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class ComickOAuthAttempt internal constructor(
    val clientId: String,
    val writeRequested: Boolean,
    internal val state: String,
    internal val verifier: String,
    internal val expiresAt: Long,
) {
    internal var consumed: Boolean = false
    internal var exchangeStarted: Boolean = false
    override fun toString() = "ComickOAuthAttempt(writeRequested=$writeRequested, consumed=$consumed)"
}

sealed interface ComickOAuthCallback {
    data class Code(val value: String) : ComickOAuthCallback {
        override fun toString(): String = "ComickOAuthCallback.Code([REDACTED])"
    }
    data class Denied(val error: String) : ComickOAuthCallback
}

/** A one-use in-memory attempt. Callback routing must validate the exact URI before calling this. */
class ComickOAuth(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    fun begin(clientId: String, write: Boolean): ComickOAuthAttempt {
        require(clientId.isNotBlank() && clientId.length <= 512) { "Register a public Comick native client first" }
        return ComickOAuthAttempt(clientId, write, nonce(), nonce(), clock() + 600_000L)
    }

    fun authorizationUrl(attempt: ComickOAuthAttempt): HttpUrl = AUTHORIZE.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", attempt.clientId)
        .addQueryParameter("redirect_uri", CALLBACK)
        .addQueryParameter("scope", if (attempt.writeRequested) "library:read library:write offline_access"
            else "library:read offline_access")
        .addQueryParameter("resource", RESOURCE)
        .addQueryParameter("state", attempt.state)
        .addQueryParameter("code_challenge", challenge(attempt.verifier))
        .addQueryParameter("code_challenge_method", "S256")
        .build()

    @Synchronized
    fun consume(attempt: ComickOAuthAttempt, callback: String): ComickOAuthCallback {
        val uri = java.net.URI(callback)
        require(uri.scheme == "io.github.glitch228.kaisoku" && uri.rawAuthority == null &&
            uri.rawPath == "/oauth/comick" && uri.rawFragment == null) { "Unexpected Comick callback" }
        val query = "https://callback.invalid${uri.rawPath}?${uri.rawQuery.orEmpty()}".toHttpUrl()
        require(!attempt.consumed && clock() < attempt.expiresAt) { "Comick authorization expired or was used" }
        require(query.queryParameterValues("state").size == 1 &&
            MessageDigest.isEqual(query.queryParameter("state").orEmpty().toByteArray(), attempt.state.toByteArray())) {
            "Comick authorization state does not match"
        }
        attempt.consumed = true
        query.queryParameter("error")?.let {
            require(query.queryParameterValues("error").size == 1 && query.queryParameter("code") == null)
            return ComickOAuthCallback.Denied(it.take(128))
        }
        require(query.queryParameterValues("code").size == 1) { "Missing Comick authorization code" }
        return ComickOAuthCallback.Code(requireNotNull(query.queryParameter("code")).also { require(it.isNotBlank()) })
    }

    @Synchronized
    fun codeForm(attempt: ComickOAuthAttempt, code: ComickOAuthCallback.Code): FormBody {
        check(attempt.consumed && !attempt.exchangeStarted && clock() < attempt.expiresAt) {
            "Validate a fresh one-use OAuth callback before exchanging a code"
        }
        attempt.exchangeStarted = true
        return FormBody.Builder().add("grant_type", "authorization_code").add("client_id", attempt.clientId)
            .add("code", code.value).add("redirect_uri", CALLBACK).add("code_verifier", attempt.verifier)
            .add("resource", RESOURCE).build()
    }

    fun refreshForm(clientId: String, refreshToken: String): FormBody = FormBody.Builder()
        .add("grant_type", "refresh_token").add("client_id", clientId).add("refresh_token", refreshToken)
        .add("resource", RESOURCE).build()

    fun revokeForm(clientId: String, token: String, refresh: Boolean): FormBody = FormBody.Builder()
        .add("client_id", clientId).add("token", token)
        .add("token_type_hint", if (refresh) "refresh_token" else "access_token").build()

    private fun nonce(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    companion object {
        const val CALLBACK = "io.github.glitch228.kaisoku:/oauth/comick"
        const val RESOURCE = "https://api.comick.dev/integrations/v1"
        const val AUTHORIZE = "https://comick.dev/api/auth/oauth2/authorize"
        const val TOKEN = "https://comick.dev/api/auth/oauth2/token"
        const val REVOKE = "https://comick.dev/api/auth/oauth2/revoke"

        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
