package org.koitharu.kotatsu.backups.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.parsers.util.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** No parser interceptors, cookies, HTTP cache, external redirects, automatic write retry or browser handling. */
class WebDavTransport(
    private val connection: WebDavConnection,
    private val ensureCurrent: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = privateClient(),
) {
    private val gate = Mutex()
    private var nextAllowedAt = 0L
    private val authorization = Credentials.basic(connection.username, connection.password, Charsets.UTF_8)

    suspend fun list(): List<WebDavResource> {
        val request = request().header("Depth", "1").method("PROPFIND", PROPERTIES.toRequestBody(XML)).build()
        return read(request) { response ->
            requireStatus(response, 207)
            WebDavListing.parse(readSmall(response, WebDavListing.MAX_BYTES), connection.endpoint)
                .sortedWith(compareByDescending<WebDavResource> { it.modifiedAt }.thenBy { it.name })
        }
    }

    suspend fun stat(name: String): WebDavResource? {
        val request = request(name).header("Depth", "0").method("PROPFIND", PROPERTIES.toRequestBody(XML)).build()
        return read(request) { response ->
            if (response.code == 404) null else {
                requireStatus(response, 207)
                WebDavListing.parse(readSmall(response, WebDavListing.MAX_BYTES), connection.endpoint, name)
                    .singleOrNull() ?: throw IOException("WebDAV did not return the requested file properties")
            }
        }
    }

    suspend fun put(name: String, file: File) {
        require(file.isFile && file.length() in 1..MAX_BACKUP_BYTES) { "Backup is missing or too large" }
        val request = request(name).header("If-None-Match", "*")
            .put(file.asRequestBody(ZIP)).build()
        execute(request) { requireStatus(it, 200, 201, 204) }
    }

    suspend fun move(stagingName: String, finalName: String) {
        val request = request(stagingName).header("Destination", connection.endpoint.child(finalName).toString())
            .header("Overwrite", "F").method("MOVE", null).build()
        execute(request) { requireStatus(it, 201, 204) }
    }

    /** A conditional DELETE never retries automatically and cannot remove an object replaced after stat. */
    suspend fun deleteVerified(resource: WebDavResource): Boolean {
        val etag = resource.strongEtag ?: return false
        val request = request(resource.name).header("If-Match", etag).delete().build()
        return execute(request) {
            when (it.code) {
                404 -> true
                412, 202 -> false
                else -> {
                    requireStatus(it, 200, 204)
                    true
                }
            }
        }
    }

    suspend fun download(resource: WebDavResource, destination: File): String {
        require(resource.size in 1..MAX_BACKUP_BYTES) { "Backup is empty or too large" }
        val request = request(resource.name).apply { resource.strongEtag?.let { header("If-Match", it) } }.get().build()
        return read(request) { response ->
            requireStatus(response, 200)
            withContext(Dispatchers.IO) {
                destination.outputStream().use { output -> copyAndHash(response, output, resource.size) }
            }
        }
    }

    /** Verifies uploaded bytes without buffering an archive or trusting an unrecognized server checksum header. */
    suspend fun hash(resource: WebDavResource): String {
        require(resource.size in 1..MAX_BACKUP_BYTES) { "Backup is empty or too large" }
        val request = request(resource.name).apply { resource.strongEtag?.let { header("If-Match", it) } }.get().build()
        return read(request) { response ->
            requireStatus(response, 200)
            withContext(Dispatchers.IO) { copyAndHash(response, DiscardOutput, resource.size) }
        }
    }

    private fun request(name: String? = null): Request.Builder = Request.Builder()
        .url(if (name == null) connection.endpoint.url else connection.endpoint.child(name))
        .header("Authorization", authorization)
        .header("Cache-Control", "no-store")
        .header("Pragma", "no-cache")
        .header("Accept-Encoding", "identity")

    private suspend fun <T> read(request: Request, block: suspend (Response) -> T): T {
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                return execute(request, block)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is WebDavFailure && (!e.transient || e.retryAfterMillis > MAX_INLINE_WAIT)) throw e
                last = e
                if (attempt < 2) delay(1000L shl attempt)
            }
        }
        throw checkNotNull(last)
    }

    private suspend fun <T> execute(request: Request, block: suspend (Response) -> T): T = gate.withLock {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val wait = (nextAllowedAt - clock()).coerceAtLeast(0L)
        if (wait > MAX_INLINE_WAIT) throw WebDavFailure("WebDAV is temporarily rate limited", 429, wait)
        if (wait > 0) delay(wait)
        ensureCurrent()
        client.newCall(request).await().use { response ->
            ensureCurrent()
            if (response.code == 429 || response.code == 503) {
                val pause = retryAfter(response.header("Retry-After"), clock()) ?: 60_000L
                nextAllowedAt = clock().coerceAtMost(Long.MAX_VALUE - pause) + pause
            }
            block(response).also { ensureCurrent() }
        }
    }

    private suspend fun requireStatus(response: Response, vararg expected: Int) {
        if (response.code in expected) return
        val raw = runCatching { readSmall(response, MAX_ERROR_BYTES).toString(Charsets.UTF_8) }.getOrDefault("")
        currentCoroutineContext().ensureActive()
        throw WebDavFailure(
            message = when (response.code) {
                401, 403 -> "WebDAV login or permission was rejected"
                301, 302, 303, 307, 308 -> "WebDAV redirects are disabled; use the final collection URL"
                405, 501 -> "The WebDAV server does not support the required operation"
                412 -> "The remote backup changed or the filename already exists"
                else -> "WebDAV request failed (HTTP ${response.code})"
            },
            status = response.code,
            retryAfterMillis = (nextAllowedAt - clock()).coerceAtLeast(0L),
            details = "Method: ${response.request.method}\n$raw",
            secrets = listOf(connection.username, connection.password, authorization, authorization.substringAfter(' ')),
        )
    }

    private suspend fun readSmall(response: Response, maximum: Int): ByteArray = withContext(Dispatchers.IO) {
        val length = response.body.contentLength()
        if (length > maximum) throw IOException("WebDAV response is too large")
        val output = ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - total))
                if (count < 0) break
                total += count
                if (total > maximum) throw IOException("WebDAV response is too large")
                output.write(buffer, 0, count)
            }
        }
        output.toByteArray()
    }

    private suspend fun copyAndHash(response: Response, output: OutputStream, expected: Long): String {
        val announced = response.body.contentLength()
        if (announced > MAX_BACKUP_BYTES || announced >= 0 && announced != expected) {
            throw IOException("WebDAV backup size changed")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        response.body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > expected || total > MAX_BACKUP_BYTES) throw IOException("WebDAV backup exceeds its declared size")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
        }
        if (total != expected) throw IOException("WebDAV backup download was interrupted")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_BACKUP_BYTES = 128L * 1024 * 1024
        private const val MAX_ERROR_BYTES = 16 * 1024
        private const val MAX_INLINE_WAIT = 30_000L
        private val ZIP = "application/zip".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val PROPERTIES = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"""

        internal fun privateClient(): OkHttpClient = OkHttpClient.Builder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        internal fun retryAfter(value: String?, now: Long): Long? {
            if (value == null) return null
            val seconds = value.trim().toLongOrNull()
            if (seconds != null) return seconds.coerceIn(0, 7 * 24 * 3600).times(1000)
            return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli().minus(now).coerceIn(0, 7L * 24 * 3600 * 1000) }.getOrNull()
        }
    }

    private object DiscardOutput : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
