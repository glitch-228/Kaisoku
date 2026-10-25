package org.koitharu.kotatsu.sync.extended.drive

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.koitharu.kotatsu.core.util.BoundedJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** New strict transport uses the configured public routing, with platform TLS and no inherited source handlers. */
internal class ExtendedDriveHttp(
    publicClient: OkHttpClient,
    private val api: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl(),
    private val upload: HttpUrl = "https://www.googleapis.com/upload/drive/v3/".toHttpUrl(),
) : ExtendedDriveTransport {
    private val client = OkHttpClient.Builder().dns(publicClient.dns).proxy(publicClient.proxy)
        .proxySelector(publicClient.proxySelector).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            checkNotNull(chain.request().tag(Guard::class.java)).check()
            chain.proceed(chain.request())
        }.build()

    override suspend fun user(token: String, guard: () -> Unit): ExtendedDriveUser = with(ExtendedDriveProtocol) {
        val response = execute(token, guard, Request.Builder().url(api.resolve("about")!!.newBuilder()
            .addQueryParameter("fields", "user(permissionId,displayName,emailAddress,me)").build()).build(), SMALL)
        val root = objectValue(response.requireSuccess().bytes)
        val user = root["user"] as? JsonObject ?: error("Drive user is unavailable")
        val permission = user.string("permissionId").also(::permission)
        require((user["me"] as? JsonPrimitive)?.content == "true") { "Drive user is not current" }
        ExtendedDriveUser(permission, (user["displayName"] as? JsonPrimitive)?.content?.take(512),
            (user["emailAddress"] as? JsonPrimitive)?.content?.take(512))
    }

    override suspend fun list(session: ExtendedDriveSession): List<ExtendedDriveFile> = with(ExtendedDriveProtocol) {
        val files = arrayListOf<ExtendedDriveFile>()
        val pages = hashSetOf<String>()
        var page: String? = null
        do {
            val url = api.resolve("files")!!.newBuilder().addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("corpora", "user").addQueryParameter("q", "name contains '$PREFIX' and trashed=false")
                .addQueryParameter("pageSize", "100").addQueryParameter("fields", "nextPageToken,incompleteSearch,files($FIELDS)")
                .apply { if (page != null) addQueryParameter("pageToken", page) }.build()
            val root = objectValue(execute(session, Request.Builder().url(url).build(), SMALL).requireSuccess().bytes)
            require((root["incompleteSearch"] as? JsonPrimitive)?.content == "false") { "Incomplete Drive listing" }
            val items = root["files"] as? JsonArray ?: error("Missing Drive file list")
            require(items.size <= 100)
            for (item in items) {
                val value = item as? JsonObject ?: error("Invalid Drive file metadata")
                if (value.string("name").startsWith(PREFIX)) files += ExtendedDriveFile.decode(value, session.permissionId)
                require(files.size <= MAX_FILES) { "Too many extended Drive files" }
            }
            page = (root["nextPageToken"] as? JsonPrimitive)?.let { require(it.isString); it.content }
            require(page == null || page!!.length in 1..4096 && pages.size < 16 && pages.add(page!!)) {
                "Invalid Drive listing page"
            }
        } while (page != null)
        require(files.map { it.id }.distinct().size == files.size) { "Duplicate Drive file ID" }
        val lanes = files.groupBy { it.descriptor.deviceId to it.descriptor.namespace }
        require(lanes.size <= 64 && lanes.values.all { it.size <= 2 }) { "Too many Drive replica lanes" }
        require(lanes.values.sumOf { it.maxBy { file -> file.descriptor.generation }.descriptor.bytes.toLong() } <=
            MAX_ACCOUNT_BYTES) { "Extended Drive replica size limit exceeded" }
        session.checkCurrent()
        files
    }

    override suspend fun generateId(session: ExtendedDriveSession): String = with(ExtendedDriveProtocol) {
        val url = api.resolve("files/generateIds")!!.newBuilder().addQueryParameter("space", "appDataFolder")
            .addQueryParameter("count", "1").addQueryParameter("type", "files").build()
        val root = objectValue(execute(session, Request.Builder().url(url).build(), SMALL).requireSuccess().bytes)
        require(root.string("space") == "appDataFolder")
        val ids = root["ids"] as? JsonArray ?: error("Missing Drive file ID")
        require(ids.size == 1)
        ids.single().stringValue().also(::id)
    }

    override suspend fun metadata(session: ExtendedDriveSession, id: String): ExtendedDriveFile? {
        ExtendedDriveProtocol.id(id)
        val response = execute(session, Request.Builder().url(api.resolve("files/$id")!!.newBuilder()
            .addQueryParameter("fields", FIELDS).build()).build(), SMALL)
        if (response.status == 404) return null
        return ExtendedDriveFile.decode(ExtendedDriveProtocol.objectValue(response.requireSuccess().bytes),
            session.permissionId)
    }

    override suspend fun download(session: ExtendedDriveSession, file: ExtendedDriveFile): ByteArray {
        val before = metadata(session, file.id)
        check(before == file) { "Drive replica changed before download" }
        val response = execute(session, Request.Builder().url(api.resolve("files/${file.id}")!!.newBuilder()
            .addQueryParameter("alt", "media").build()).build(), ExtendedDriveProtocol.MAX_BYTES).requireSuccess()
        val bytes = response.bytes
        require(bytes.size == file.descriptor.bytes && ExtendedDriveProtocol.hash(bytes) == file.descriptor.sha256 &&
            ExtendedDriveProtocol.md5(bytes) == file.md5) { "Drive replica checksum mismatch" }
        BoundedJson.validateObject(bytes, ExtendedDriveProtocol.MAX_BYTES)
        check(metadata(session, file.id) == file) { "Drive replica changed during download" }
        session.checkCurrent()
        return bytes
    }

    override suspend fun create(session: ExtendedDriveSession, id: String, descriptor: ExtendedDriveDescriptor,
        bytes: ByteArray) {
        ExtendedDriveProtocol.id(id)
        require(descriptor.permissionId == session.permissionId && bytes.size == descriptor.bytes &&
            ExtendedDriveProtocol.hash(bytes) == descriptor.sha256)
        BoundedJson.validateObject(bytes, ExtendedDriveProtocol.MAX_BYTES)
        val metadata = JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(descriptor.name),
            "description" to JsonPrimitive(descriptor.description()), "mimeType" to JsonPrimitive("application/json"),
            "parents" to JsonArray(listOf(JsonPrimitive("appDataFolder")))))
        val url = upload.resolve("files")!!.newBuilder().addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FIELDS).build()
        val initial = execute(session, Request.Builder().url(url)
            .header("X-Upload-Content-Type", "application/json").header("X-Upload-Content-Length", bytes.size.toString())
            .post(body(metadata.toString().toByteArray(Charsets.UTF_8), session.checkCurrent)).build(), SMALL)
            .requireSuccess()
        val location = initial.location?.toHttpUrl() ?: error("Missing Drive upload session")
        require(location.scheme == upload.scheme && location.host == upload.host && location.port == upload.port &&
            location.encodedPath.startsWith(upload.encodedPath) && location.username.isEmpty() && location.password.isEmpty()) {
            "Untrusted Drive upload session URL"
        }
        // Session URL stays in memory only. A later explicit attempt creates a fresh session for this exact generated ID.
        execute(session, Request.Builder().url(location).header("Content-Range", "bytes 0-${bytes.lastIndex}/${bytes.size}")
            .put(body(bytes, session.checkCurrent)).build(), SMALL).requireSuccess()
    }

    override suspend fun delete(session: ExtendedDriveSession, file: ExtendedDriveFile) {
        val current = metadata(session, file.id) ?: return
        check(current == file) { "Changed Drive file cannot be compacted" }
        val response = execute(session, Request.Builder().url(api.resolve("files/${file.id}")!!).delete().build(), SMALL)
        if (response.status != 404) response.requireSuccess()
    }

    private suspend fun body(bytes: ByteArray, guard: () -> Unit): RequestBody {
        val context = currentCoroutineContext()
        return object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { Guard(context, guard).check(); sink.write(bytes) }
        }
    }

    private suspend fun execute(session: ExtendedDriveSession, request: Request, maximum: Int): Reply =
        execute(session.token, session.checkCurrent, request, maximum)

    private suspend fun execute(token: String, checkCurrent: () -> Unit, request: Request, maximum: Int): Reply =
        transfers.withPermit {
            val guard = Guard(currentCoroutineContext(), checkCurrent)
            guard.check()
            val guarded = request.newBuilder().header("Authorization", "Bearer $token")
                .header("Accept-Encoding", "identity").header("Cache-Control", "no-store")
                .tag(Guard::class.java, guard).build()
            val result = suspendCancellableCoroutine { continuation ->
                val call = client.newCall(guarded)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val value = response.use {
                                guard.check()
                                require(it.body.contentLength() < 0 || it.body.contentLength() <= maximum)
                                require(it.header("Content-Encoding") in setOf(null, "identity"))
                                val output = ByteArrayOutputStream(minOf(maximum, 65536))
                                val buffer = ByteArray(65536)
                                it.body.byteStream().use { input ->
                                    while (true) {
                                        guard.check()
                                        val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
                                        if (count < 0) break
                                        require(output.size() + count <= maximum) { "Drive response exceeds byte limit" }
                                        output.write(buffer, 0, count)
                                    }
                                }
                                guard.check()
                                Reply(it.code, output.toByteArray(), it.header("Location"))
                            }
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                })
            }
            guard.check()
            result
        }

    private class Guard(val context: CoroutineContext, val current: () -> Unit) {
        fun check() {
            try { context.ensureActive(); current(); context.ensureActive() }
            catch (error: IOException) { throw error }
            catch (error: Exception) { throw IOException("Drive session changed", error) }
        }
    }
    private data class Reply(val status: Int, val bytes: ByteArray, val location: String?) {
        fun requireSuccess(): Reply = also { if (status !in 200..299) throw ExtendedDriveHttpException(status) }
    }
    companion object {
        private val transfers = Semaphore(2)
        private const val SMALL = 2 * 1024 * 1024
        private const val FIELDS = "id,name,description,mimeType,version,size,md5Checksum,spaces,trashed,ownedByMe"
    }
}
