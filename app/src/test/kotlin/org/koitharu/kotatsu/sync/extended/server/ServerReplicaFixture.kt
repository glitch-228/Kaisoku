package org.koitharu.kotatsu.sync.extended.server

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.TreeMap
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext

/** Small HTTP/1.1 socket fixture that is visible on Android's unit-test bootclasspath. */
internal class ServerReplicaFixture private constructor(private val listener: ServerSocket) {
    val address: InetSocketAddress get() = listener.localSocketAddress as InetSocketAddress
    lateinit var executor: Executor
    private lateinit var handler: (ServerReplicaExchange) -> Unit
    @Volatile private var running = false

    fun createContext(path: String, handler: (ServerReplicaExchange) -> Unit) {
        require(path == "/")
        this.handler = handler
    }

    fun start() {
        running = true
        executor.execute {
            while (running) {
                val socket = try { listener.accept() } catch (error: SocketException) {
                    if (running) throw error else break
                }
                executor.execute {
                    socket.use {
                        try {
                            it.soTimeout = 5000
                            handler(ServerReplicaExchange(it))
                        } catch (_: IOException) {
                            // Disconnects, untrusted TLS and cancellation are deliberate fixture scenarios.
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        listener.close()
    }

    companion object {
        fun create(address: InetSocketAddress, sslContext: SSLContext? = null): ServerReplicaFixture {
            val socket = sslContext?.serverSocketFactory?.createServerSocket() ?: ServerSocket()
            socket.bind(address)
            return ServerReplicaFixture(socket)
        }
    }
}

internal class ServerReplicaHeaders {
    private val values = TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER)
    fun getFirst(name: String): String? = values[name]?.firstOrNull()
    fun set(name: String, value: String) { values[name] = mutableListOf(value) }
    fun add(name: String, value: String) { values.getOrPut(name) { mutableListOf() }.add(value) }
    fun writeTo(output: OutputStream) {
        values.forEach { (name, items) ->
            items.forEach { output.write("$name: $it\r\n".toByteArray(Charsets.US_ASCII)) }
        }
    }
}

internal class ServerReplicaExchange(private val socket: Socket) {
    val requestHeaders = ServerReplicaHeaders()
    val responseHeaders = ServerReplicaHeaders()
    val requestMethod: String
    val requestURI: URI
    val requestBody: ByteArrayInputStream
    private val output = socket.getOutputStream()
    private var sent = false
    private var chunked = false
    private var ended = false

    init {
        val input = BufferedInputStream(socket.getInputStream())
        val line = readLine(input).split(' ')
        if (line.size != 3) throw IOException("Invalid fixture request line")
        requestMethod = line[0]
        requestURI = URI(line[1])
        while (true) {
            val header = readLine(input)
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator <= 0) throw IOException("Invalid fixture header")
            requestHeaders.add(header.substring(0, separator), header.substring(separator + 1).trim())
        }
        val length = requestHeaders.getFirst("Content-Length")?.toIntOrNull() ?: 0
        if (length !in 0..ServerReplicaProtocol.MAX_BYTES) throw IOException("Invalid fixture request length")
        val bytes = ByteArray(length)
        DataInputStream(input).readFully(bytes)
        requestBody = ByteArrayInputStream(bytes)
    }

    val responseBody: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            check(sent && !ended)
            if (count == 0) return
            if (chunked) output.write("${count.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes, offset, count)
            if (chunked) output.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        override fun close() {
            if (ended) return
            if (chunked) output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            ended = true
            output.flush()
        }
    }

    fun sendResponseHeaders(status: Int, length: Long) {
        check(!sent)
        sent = true
        chunked = length == 0L && status != 304
        responseHeaders.set("Connection", "close")
        if (chunked) responseHeaders.set("Transfer-Encoding", "chunked")
        else responseHeaders.set("Content-Length", maxOf(0L, length).toString())
        output.write("HTTP/1.1 $status Fixture\r\n".toByteArray(Charsets.US_ASCII))
        responseHeaders.writeTo(output)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    fun close() = socket.close()

    private fun readLine(input: BufferedInputStream): String {
        val line = StringBuilder()
        while (line.length <= 8192) {
            val value = input.read()
            if (value == -1) throw EOFException("Incomplete fixture request")
            if (value == 10) return line.toString().removeSuffix("\r")
            line.append(value.toChar())
        }
        throw IOException("Fixture request line too long")
    }
}
