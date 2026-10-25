package org.koitharu.kotatsu.backups.webdav

import okhttp3.Credentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Task-owned loopback DAV server. It never binds a non-loopback address or touches a real account. */
internal class WebDavFixture : AutoCloseable {
    data class Object(val bytes: ByteArray, val etag: String)
    data class Call(val method: String, val path: String, val authorization: String?, val condition: String?)
    val objects: MutableMap<String, Object> = Collections.synchronizedMap(linkedMapOf())
    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    private val version = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "webdav-fixture").apply { isDaemon = true } }
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean()
    private val clients = Collections.synchronizedList(mutableListOf<Socket>())
    private val acceptor: Thread
    var handler: ((DavFixtureExchange) -> Boolean)? = null
    var moveMode = "normal"
    var beforeDelete: ((String) -> Unit)? = null
    val connection: WebDavConnection

    init {
        connection = WebDavConnection(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Fixture",
            WebDavEndpoint.parse("http://127.0.0.1:${server.localPort}/dav/", true), "alice", "secret<&>", true)
        acceptor = Thread({
            while (!closed.get()) {
                val client = try { server.accept() } catch (_: java.net.SocketException) { break }
                clients += client
                executor.execute {
                    client.use { socket ->
                        socket.soTimeout = 5_000
                        val exchange = DavFixtureExchange.read(socket) ?: return@use
                        handle(exchange)
                    }
                    clients.remove(client)
                }
            }
        }, "webdav-fixture-accept").apply { isDaemon = true; start() }
    }

    private fun handle(exchange: DavFixtureExchange) {
            try {
                val name = exchange.requestURI.path.removePrefix("/dav/")
                calls += Call(exchange.requestMethod, exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("If-Match"))
                if (exchange.requestHeaders.getFirst("Authorization") != Credentials.basic("alice", "secret<&>")) {
                    reply(exchange, 401, "unauthorized")
                } else if (handler?.invoke(exchange) != true) {
                    when (exchange.requestMethod) {
                        "PROPFIND" -> properties(exchange, name)
                        "GET" -> objects[name]?.let { obj ->
                            if (exchange.requestHeaders.getFirst("If-Match")?.let { it != obj.etag } == true) {
                                reply(exchange, 412, "changed")
                            } else {
                                exchange.responseHeaders.add("ETag", obj.etag)
                                reply(exchange, 200, obj.bytes)
                            }
                        } ?: reply(exchange, 404, "missing")
                        "PUT" -> {
                            if (objects.containsKey(name) && exchange.requestHeaders.getFirst("If-None-Match") == "*") {
                                reply(exchange, 412, "exists")
                            } else {
                                put(name, exchange.requestBody.readBytes())
                                reply(exchange, 201, "")
                            }
                        }
                        "MOVE" -> move(exchange, name)
                        "DELETE" -> {
                            beforeDelete?.invoke(name)
                            val obj = objects[name]
                            if (obj == null) reply(exchange, 404, "missing")
                            else if (exchange.requestHeaders.getFirst("If-Match") != obj.etag) reply(exchange, 412, "changed")
                            else {
                                objects.remove(name)
                                reply(exchange, 204, "")
                            }
                        }
                        else -> reply(exchange, 405, "unsupported")
                    }
                }
            } finally {
                exchange.close()
            }
    }

    fun put(name: String, bytes: ByteArray): WebDavResource {
        val obj = Object(bytes, "\"version-${version.incrementAndGet()}\"")
        objects[name] = obj
        return WebDavResource(name, bytes.size.toLong(), obj.etag)
    }

    fun properties(exchange: DavFixtureExchange, name: String) {
        if (name.isNotEmpty() && !objects.containsKey(name)) {
            reply(exchange, 404, "missing")
            return
        }
        val entries = if (name.isEmpty()) synchronized(objects) { objects.toMap() } else mapOf(name to checkNotNull(objects[name]))
        val text = buildString {
            append("<d:multistatus xmlns:d=\"DAV:\">")
            append("<d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            entries.forEach { (entryName, obj) ->
                append("<d:response><d:href>/dav/").append(xml(entryName)).append("</d:href><d:propstat><d:prop>")
                append("<d:getcontentlength>").append(obj.bytes.size).append("</d:getcontentlength>")
                append("<d:getetag>").append(xml(obj.etag)).append("</d:getetag><d:resourcetype/>")
                append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }
        reply(exchange, 207, text)
    }

    private fun move(exchange: DavFixtureExchange, name: String) {
        val obj = objects[name] ?: return reply(exchange, 404, "missing")
        val destination = java.net.URI(exchange.requestHeaders.getFirst("Destination")).path.removePrefix("/dav/")
        check(exchange.requestHeaders.getFirst("Overwrite") == "F")
        when (moveMode) {
            "unsupported" -> reply(exchange, 405, "MOVE unsupported")
            "unknown-unmoved" -> exchange.close()
            "error-unmoved" -> reply(exchange, 500, "unknown server transaction state")
            "collision-identical" -> {
                put(destination, obj.bytes.copyOf())
                reply(exchange, 412, "destination appeared")
            }
            else -> {
                if (objects.containsKey(destination)) reply(exchange, 412, "exists")
                else {
                    objects[destination] = obj
                    objects.remove(name)
                    if (moveMode == "moved-error") reply(exchange, 500, "lost response after commit")
                    else reply(exchange, 201, "")
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        server.close()
        synchronized(clients) { clients.forEach { runCatching { it.close() } }; clients.clear() }
        executor.shutdownNow()
        executor.awaitTermination(3, TimeUnit.SECONDS)
        acceptor.join(1_000)
    }

    companion object {
        fun reply(exchange: DavFixtureExchange, status: Int, value: String) = reply(exchange, status, value.toByteArray())
        fun reply(exchange: DavFixtureExchange, status: Int, value: ByteArray) = exchange.reply(status, value)
        private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
    }
}

/** Small test-owned HTTP exchange, available on the same Java API surface as Android. */
internal class DavFixtureHeaders {
    private val values = linkedMapOf<String, Pair<String, String>>()
    fun add(name: String, value: String) { values[name.lowercase(java.util.Locale.ROOT)] = name to value }
    fun getFirst(name: String): String? = values[name.lowercase(java.util.Locale.ROOT)]?.second
    fun entries(): Collection<Pair<String, String>> = values.values
}

internal class DavFixtureExchange private constructor(
    private val client: Socket,
    val requestMethod: String,
    val requestURI: URI,
    val requestHeaders: DavFixtureHeaders,
    val requestBody: ByteArrayInputStream,
) : Closeable {
    val responseHeaders = DavFixtureHeaders()
    private var headersSent = false
    private var chunked = false
    private var bodyClosed = false
    val responseBody: OutputStream = object : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(headersSent && !bodyClosed)
            if (length == 0) return
            val output = client.getOutputStream()
            if (chunked) output.write("${length.toString(16)}\r\n".toByteArray())
            output.write(bytes, offset, length)
            if (chunked) output.write("\r\n".toByteArray())
            output.flush()
        }
        override fun close() {
            if (bodyClosed) return
            if (chunked) client.getOutputStream().write("0\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            bodyClosed = true
        }
    }

    fun sendResponseHeaders(status: Int, length: Long) = sendHeaders(status, length.takeIf { it > 0 }, length == 0L)
    private fun sendHeaders(status: Int, length: Long?, streamChunks: Boolean) {
        check(!headersSent)
        headersSent = true
        chunked = streamChunks
        val head = buildString {
            append("HTTP/1.1 $status Fixture\r\nConnection: close\r\n")
            if (chunked) append("Transfer-Encoding: chunked\r\n") else append("Content-Length: ${length ?: 0}\r\n")
            responseHeaders.entries().forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        client.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
        client.getOutputStream().flush()
    }

    fun reply(status: Int, bytes: ByteArray) {
        sendHeaders(status, if (status == 204) 0 else bytes.size.toLong(), false)
        if (status != 204) responseBody.use { it.write(bytes) }
    }
    override fun close() { client.close() }

    companion object {
        fun read(client: Socket): DavFixtureExchange? {
            val input = client.getInputStream()
            val header = ByteArrayOutputStream()
            var match = 0
            val ending = byteArrayOf(13, 10, 13, 10)
            while (match < 4) {
                check(header.size() < 32 * 1024) { "Fixture request header exceeded its bound" }
                val next = input.read()
                if (next < 0) return null
                header.write(next)
                match = if (next == ending[match].toInt()) match + 1 else if (next == 13) 1 else 0
            }
            val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val first = lines.first().split(' ')
            check(first.size == 3)
            val headers = DavFixtureHeaders().apply {
                lines.drop(1).filter { ':' in it }.forEach { add(it.substringBefore(':'), it.substringAfter(':').trim()) }
            }
            val length = headers.getFirst("Content-Length")?.toIntOrNull() ?: 0
            require(length in 0..4 * 1024 * 1024)
            val bytes = ByteArray(length)
            var count = 0
            while (count < length) {
                val read = input.read(bytes, count, length - count)
                check(read >= 0) { "Incomplete fixture request body" }
                count += read
            }
            return DavFixtureExchange(client, first[0], URI(first[1]), headers, ByteArrayInputStream(bytes))
        }
    }
}

internal class MemoryWebDavJournal : WebDavOwnershipJournal {
    var records = emptyList<WebDavOwnedBackup>()
    override suspend fun read(connection: WebDavConnection) = records
    override suspend fun write(connection: WebDavConnection, records: List<WebDavOwnedBackup>) { this.records = records }
}
