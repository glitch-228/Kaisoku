package org.koitharu.kotatsu.scrobbling.v2

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koitharu.kotatsu.core.exceptions.CopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.redactSecrets
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.scrobbling.common.data.TrackerRequestGate
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TrackerWireResponse(val code: Int, val body: String, val retryAfterMillis: Long = 0) {
    val successful: Boolean
        get() = code in 200..299
}

fun interface TrackerWire {
    suspend fun execute(request: Request): TrackerWireResponse
}

/** Strict TLS, no shared HTTP cache/cookies/CAPTCHA/logging interceptors and no implicit write retries. */
class PrivateTrackerWire(base: OkHttpClient, spacingMillis: Long = 1_100L) : TrackerWire {
    private val gate = TrackerRequestGate(spacingMillis)
    private val client = privateTrackerClient(base)

    override suspend fun execute(request: Request): TrackerWireResponse = gate.execute {
        require(request.url.isHttps && request.url.username.isEmpty() && request.url.password.isEmpty()) {
            "Tracker requests require an authenticated HTTPS origin"
        }
        org.koitharu.kotatsu.scrobbling.common.data.checkTrackerRequestGuard()
        client.newCall(request.newBuilder().header("Cache-Control", "no-store")
            .header("Pragma", "no-cache").build()).await().use { response ->
            gate.update(response)
            val body = response.body
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Tracker response is too large")
            val source = body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1L)) throw IOException("Tracker response is too large")
            val content = source.readUtf8()
            TrackerWireResponse(response.code, content,
                gate.retryDelay(response.header("Retry-After"), System.currentTimeMillis()) ?: 0)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    }
}

internal fun privateTrackerClient(base: OkHttpClient): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(base.dispatcher)
        .connectionPool(base.connectionPool)
        .dns(base.dns)
        .proxySelector(base.proxySelector)
        .proxyAuthenticator(base.proxyAuthenticator)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

class TrackerProviderError(
    val provider: String,
    val status: Int,
    val cooldownMillis: Long,
    rawDetails: String,
    secrets: List<String> = emptyList(),
) : IOException("$provider: HTTP $status"), CopyableErrorDetails {
    override val copyableErrorDetails: String = buildString {
        appendLine("Provider: $provider")
        appendLine("HTTP status: $status")
        if (cooldownMillis > 0) appendLine("Retry after: ${(cooldownMillis + 999) / 1000} seconds")
        var safe = rawDetails.redactSecrets()
        secrets.filter(String::isNotBlank).sortedByDescending(String::length).forEach {
            safe = safe.replace(it, "[REDACTED]")
        }
        append(safe.take(32 * 1024))
    }
}

internal fun TrackerWireResponse.requireSuccess(provider: String, secrets: List<String> = emptyList()): JSONObject {
    if (!successful) throw TrackerProviderError(provider, code,
        if (code == 429 && retryAfterMillis <= 0) 60_000 else retryAfterMillis, body, secrets)
    return JSONObject(body)
}
