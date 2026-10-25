package org.koitharu.kotatsu.remote.library

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class LibraryFixtureRequest(val path: String, val method: String, val headers: Map<String, String>)

/** Local sockets exercise the actual OkHttp path; the product connection UI still requires HTTPS. */
internal class LibraryHttpFixture(private val respond: (LibraryFixtureRequest, Socket) -> Unit) : Closeable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val closed = AtomicBoolean(false)
    private val clients = Collections.synchronizedList(ArrayList<Socket>())
    val requests = Collections.synchronizedList(ArrayList<LibraryFixtureRequest>())
    val base = "http://127.0.0.1:${socket.localPort}/library/"
    val policy = LibraryUrlPolicy.loopback(base.toHttpUrl())

    init {
        executor.submit {
            while (!closed.get()) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                clients += client
                executor.submit {
                    client.use {
                        it.soTimeout = 10_000
                        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val first = reader.readLine() ?: return@use
                        val fields = first.split(' ')
                        val headers = LinkedHashMap<String, String>()
                        while (true) {
                            val line = reader.readLine()?.takeIf(String::isNotEmpty) ?: break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val request = LibraryFixtureRequest(fields[1], fields[0], headers)
                        requests += request
                        respond(request, it)
                    }
                    clients.remove(client)
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        socket.close()
        synchronized(clients) { clients.forEach { runCatching { it.close() } }; clients.clear() }
        executor.shutdownNow()
        executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
    }
}

internal fun Socket.respond(body: ByteArray, status: Int = 200, contentType: String = "application/opds+json",
                            headers: Map<String, String> = emptyMap()) {
    val output = getOutputStream()
    val head = buildString {
        append("HTTP/1.1 $status Fixture\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }
    output.write(head.toByteArray(Charsets.ISO_8859_1))
    output.write(body)
    output.flush()
}

internal fun Socket.respond(body: String, status: Int = 200, contentType: String = "application/opds+json",
                            headers: Map<String, String> = emptyMap()) = respond(body.toByteArray(), status, contentType, headers)

internal fun fixtureConnection(server: LibraryHttpFixture, content: RemoteLibraryContent = RemoteLibraryContent.SAFE) =
    RemoteLibraryConnection("00000000-0000-0000-0000-000000000034", "Fixture", server.base + "catalog", server.base,
        RemoteLibraryKind.OPDS, "reader", content, allowCleartextLan = true)
