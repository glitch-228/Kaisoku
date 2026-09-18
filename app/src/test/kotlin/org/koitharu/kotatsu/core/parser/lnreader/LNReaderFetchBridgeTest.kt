package org.koitharu.kotatsu.core.parser.lnreader

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class LNReaderFetchBridgeTest {
	@Test fun protectionResponseRetainsNativeSolverException() {
		val client = OkHttpClient.Builder().addInterceptor(CloudFlareInterceptor()).addInterceptor { chain ->
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
				.body("<html><head><title>Just a moment...</title></head><body>Enable JavaScript and cookies to continue</body></html>".toResponseBody()).build()
		}.build()
		val bridge = LNReaderFetchBridge(client, "webnovel")
		val response = JSONObject(bridge.fetch("https://www.webnovel.com/"))
		assertEquals(403, response.getInt("status"))
		assertTrue(response.getString("error").contains("CloudFlare"))
		assertTrue(bridge.pendingFatalException is CloudFlareProtectedException)
	}

	@Test fun unicodeRedirectAndExplicitGzipUseDecodedResponse() {
		val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
		val executor = Executors.newSingleThreadExecutor()
		var query: String? = null
		var encoding: String? = null
		val body = """{"name":"Моя жена"}"""
		val zipped = ByteArrayOutputStream().apply {
			GZIPOutputStream(this).use { it.write(body.toByteArray()) }
		}.toByteArray()
        val serving = executor.submit {
            repeat(2) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    val headers = generateSequence { reader.readLine().takeUnless { it.isEmpty() } }.toList()
                    val output = socket.getOutputStream()
                    if (index == 0) {
                        query = request
                        output.write("HTTP/1.1 302 Found\r\nLocation: /result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        encoding = headers.firstOrNull { it.startsWith("Accept-Encoding:", true) }?.substringAfter(':')?.trim()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: application/json; charset=utf-8\r\n" +
                            "X-Test: retained\r\nContent-Length: ${zipped.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(zipped)
                    }
                    output.flush()
                }
            }
        }
		try {
			val url = "http://127.0.0.1:${server.localPort}/search?q=Моя жена&encoded=a%20b"
			val result = JSONObject(LNReaderFetchBridge(OkHttpClient(), "test").fetch(url,
				"""{"headers":{"accept-encoding":"gzip"}}"""))
			assertEquals(body, result.getString("text"))
			assertEquals(200, result.getInt("status"))
			assertTrue(result.getString("url").endsWith("/result"))
			assertEquals("gzip", encoding)
			assertTrue(query!!.contains("%20"))
			assertTrue(query!!.contains("encoded=a%20b"))
			assertFalse(query!!.contains("%2520"))
			assertTrue(result.getJSONObject("headers").toString().contains("retained"))
				serving.get(5, TimeUnit.SECONDS)
		} finally { server.close(); executor.shutdownNow() }
	}
}
