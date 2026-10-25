package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.koitharu.kotatsu.parsers.util.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class LibraryDocument(val url: String, val contentType: String?, val bytes: ByteArray)

/** No website interceptors, browser solvers, cookie jar, shared HTTP cache, or progress-write endpoints. */
class RemoteLibraryTransport(
    val policy: LibraryUrlPolicy,
    credentials: suspend () -> RemoteLibraryCredentials?,
    private val maximumAcquisitionBytes: Long = 512L * 1024 * 1024,
    private val currentConnection: () -> Unit = {},
) {
    private val operations = Semaphore(2)
    private val scopeInterceptor = ScopedLibraryInterceptor(policy, credentials, currentConnection)
    val imageClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(scopeInterceptor)
        .build()

    suspend fun document(url: String): LibraryDocument = request(url) { response ->
        val body = response.body ?: throw RemoteLibraryException("The library returned no document")
        if (body.contentLength() > MAX_LIBRARY_BODY) throw LibraryLimitException("The library document is too large")
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() > MAX_LIBRARY_BODY - count) throw LibraryLimitException("The library document is too large")
                output.write(buffer, 0, count)
            }
        }
        LibraryDocument(response.request.url.toString(), body.contentType()?.toString(), output.toByteArray())
    }

    suspend fun download(link: LibraryLink, target: File, anonymousExternal: Boolean = false,
                         progress: (Long, Long?) -> Unit = { _, _ -> }): String {
        require(!target.exists()) { "Acquisition staging file already exists" }
        if (!link.isDirectAcquisition || link.supportedFormat == null || link.templated) {
            throw LibraryUnsupportedException("This is not a supported direct EPUB/CBZ acquisition")
        }
        val advertised = link.size
        if (advertised != null && (advertised < 0 || advertised > maximumAcquisitionBytes)) {
            throw LibraryLimitException("The advertised book exceeds the acquisition size limit")
        }
        val expectedDigest = link.hash?.let(::sha256Hash)
        var ownsTarget = false
        try {
            return request(link.href, anonymousExternal) { response ->
                val body = response.body ?: throw RemoteLibraryException("The library returned no book")
                val length = body.contentLength().takeIf { it >= 0 }
                if ((length ?: 0) > maximumAcquisitionBytes) throw LibraryLimitException("The book exceeds the acquisition size limit")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                // CREATE_NEW prevents accidental overwrite even if another task claims our stage.
                java.nio.file.Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE).use { output ->
                    ownsTarget = true
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (total > maximumAcquisitionBytes - count) throw LibraryLimitException("The book exceeds the acquisition size limit")
                            total += count
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            progress(total, length ?: advertised)
                        }
                    }
                }
                if ((advertised != null && total != advertised) || (length != null && total != length)) {
                    throw RemoteLibraryException("The acquired book length does not match its advertised size")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedDigest != null && actual != expectedDigest) throw RemoteLibraryException("The acquired book checksum is incorrect")
                actual
            }
        } catch (error: Throwable) {
            // The only file this operation owns. An existing target was rejected before the try.
            if (ownsTarget) target.delete()
            throw error
        }
    }

    suspend fun image(url: String, target: File) {
        require(!target.exists()) { "Image staging file already exists" }
        var owned = false
        try {
            request(url) { response ->
                val body = response.body ?: throw RemoteLibraryException("The library returned no image")
                if (body.contentLength() > MAX_IMAGE_BYTES) throw LibraryLimitException("The library image is too large")
                java.nio.file.Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE).use { output ->
                    owned = true
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (total > MAX_IMAGE_BYTES - count) throw LibraryLimitException("The library image is too large")
                            output.write(buffer, 0, count)
                            total += count
                        }
                    }
                }
            }
        } catch (error: Throwable) { if (owned) target.delete(); throw error }
    }

    fun pageRequest(url: String): Request = Request.Builder().url(policy.validate(url.toHttpUrl())).get()
        .cacheControl(CacheControl.FORCE_NETWORK).tag(LibraryImageRequest::class.java, LibraryImageRequest).build()

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> request(url: String, anonymousExternal: Boolean = false,
                                   consume: suspend (Response) -> T): T = operations.withPermit {
        currentConnection()
        val parsed = policy.validate(url.toHttpUrl(), anonymousExternal)
        val request = Request.Builder().url(parsed).get().cacheControl(CacheControl.FORCE_NETWORK)
            .apply { if (anonymousExternal) tag(AnonymousAcquisition::class.java, AnonymousAcquisition) }.build()
        val call = imageClient.newCall(request)
        val context = currentCoroutineContext()
        val cancellation = context[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        try {
            withContext(Dispatchers.IO) {
                context.ensureActive()
                call.await().use { response ->
                    if (response.code == 401 || response.code == 403) throw LibraryAuthenticationException()
                    if (!response.isSuccessful) throw RemoteLibraryException("Library request failed (HTTP ${response.code})")
                    consume(response).also { currentConnection() }
                }
            }
        } catch (error: IOException) {
            context.ensureActive()
            throw error.asLibraryFailure()
        } finally { cancellation?.dispose() }
    }

    private fun String.toHttpUrl() = toHttpUrlOrNull() ?: throw RemoteLibraryException("Invalid library URL")

    private fun sha256Hash(hash: String): String {
        val plain = hash.removePrefix("sha256:").removePrefix("sha-256:").lowercase(java.util.Locale.ROOT)
        if (!Regex("[a-f0-9]{64}").matches(plain)) throw LibraryUnsupportedException("Unsupported acquisition checksum; SHA-256 is required")
        return plain
    }

    private companion object { const val MAX_IMAGE_BYTES = 64L * 1024 * 1024 }
}

private object AnonymousAcquisition
private object LibraryImageRequest
private object LibraryNetworkSlots { val slots = java.util.concurrent.Semaphore(2, true) }

private class ScopedLibraryInterceptor(
    private val policy: LibraryUrlPolicy,
    private val credentials: suspend () -> RemoteLibraryCredentials?,
    private val currentConnection: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            while (!LibraryNetworkSlots.slots.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                if (chain.call().isCanceled()) throw IOException("Library request was cancelled")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Library request was interrupted").apply { initCause(interrupted) }
        }
        var bodyOwnsSlot = false
        try {
            if (chain.call().isCanceled()) throw IOException("Library request was cancelled")
            currentConnection()
            val response = interceptScoped(chain)
            val body = response.body ?: return response
            val maximum = if (chain.request().tag(LibraryImageRequest::class.java) != null) 64L * 1024 * 1024 else 512L * 1024 * 1024
            if (body.contentLength() > maximum) {
                response.close()
                throw LibraryLimitException("The library response exceeds its size limit")
            }
            val wrapped = SlotResponseBody(body, maximum, currentConnection)
            bodyOwnsSlot = true
            return response.newBuilder().body(wrapped).build()
        } catch (error: IOException) {
            throw error.asLibraryFailure()
        } finally { if (!bodyOwnsSlot) LibraryNetworkSlots.slots.release() }
    }

    private fun interceptScoped(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.method != "GET") throw RemoteLibraryException("Remote libraries are read-only")
        val anonymous = request.tag(AnonymousAcquisition::class.java) != null
        val visited = HashSet<String>()
        // Credentials are materialized only on this OkHttp I/O worker, never in Manga URLs/metadata.
        val account = if (anonymous) null else runBlocking(Dispatchers.IO) { credentials() }
        repeat(6) { hop ->
            val url = policy.validate(request.url, anonymous)
            currentConnection()
            if (!visited.add(url.toString())) throw RemoteLibraryException("The library redirected in a loop")
            request = request.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
                .removeHeader("Proxy-Authorization").header("Cache-Control", "no-store")
                .apply {
                    if (account != null && policy.contains(url)) header("Authorization",
                        Credentials.basic(account.username, account.password, Charsets.UTF_8))
                }.build()
            val response = chain.proceed(request)
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            response.close()
            if (hop == 5 || location == null) throw RemoteLibraryException("The library returned an invalid redirect")
            request = request.newBuilder().url(policy.resolve(url.toString(), location, anonymous)).build()
        }
        throw RemoteLibraryException("The library redirected too many times")
    }
}

/** Holds one of the two global library slots until the actual body is consumed/closed, not headers only. */
private class SlotResponseBody(private val delegate: ResponseBody, private val maximum: Long,
                               private val currentConnection: () -> Unit) : ResponseBody() {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    private val source by lazy {
        object : ForwardingSource(delegate.source()) {
            private var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                return try {
                    currentConnection()
                    val count = super.read(sink, byteCount)
                    currentConnection()
                    if (count == -1L) release() else {
                        if (total > maximum - count) throw LibraryLimitException("The library response exceeds its size limit")
                        total += count
                    }
                    count
                } catch (error: Throwable) {
                    release()
                    throw if (error is IOException) error.asLibraryFailure() else error
                }
            }
            override fun close() { try { super.close() } finally { release() } }
        }.buffer()
    }
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source() = source
    private fun release() { if (released.compareAndSet(false, true)) LibraryNetworkSlots.slots.release() }
}
