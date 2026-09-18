package org.koitharu.kotatsu.core.parser.lnreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Instrumented test for the full LNReader engine (QuickJS JNI + bridges).
 * Runs the fixture plugin against a loopback HTTP server without touching real sites.
 */
@RunWith(AndroidJUnit4::class)
class LNReaderEngineInstrumentedTest {
	@Test fun coverHeadersLoadOnColdOpenWithoutFetchingDetailsOrChapters() = runBlocking {
		val application = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
		val entity = org.koitharu.kotatsu.core.db.entity.LnReaderSourceEntity(
			pluginId = "cover-test", name = "Cover Test", site = "https://example.test",
			jsCode = """
				exports.default = {
					imageRequestInit: {headers: {Referer: 'https://reader.example/', 'Site-Id': '3'}},
					parseNovel: function() { throw new Error('Must not load details for covers'); },
					parseChapter: function() { throw new Error('Must not load chapters for covers'); }
				};
			""".trimIndent(), createdAt = 0, updatedAt = 0,
		)
		val cache = org.koitharu.kotatsu.core.cache.MemoryContentCache(application)
		try {
			val repository = LnReaderMangaRepository(entity, OkHttpClient(), cache)
			val headers = repository.getImageHeaders()
			assertEquals("https://reader.example/", headers["Referer"])
			assertEquals("3", headers["Site-Id"])
			assertEquals(headers, repository.getImageHeaders())
		} finally {
			application.unregisterComponentCallbacks(cache)
		}
	}

	@Test fun chapterErrorsAreActionableAndCleanUpResults() = runBlocking {
		val engine = LNReaderEngine(LNReaderFetchBridge(OkHttpClient(), "errors"))
		engine.createPluginContext("""
			exports.default = {parseChapter: function(path) {
				if (path === 'null') return Promise.reject(null);
				if (path === 'empty') return Promise.resolve('');
				return new Promise(function(resolve) { globalThis.finishRequest = resolve; });
			}};
		""".trimIndent(), "errors").use { qjs ->
			val bridge = LNReaderPluginBridge(qjs, "errors", timeoutMs = 250)
			for ((path, message) in listOf("null" to "without error details", "empty" to "empty text", "timeout" to "timed out")) {
				try {
					bridge.parseChapter(path)
					org.junit.Assert.fail("Expected chapter failure for $path")
				} catch (e: LNReaderJSException) {
					assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(message))
				}
				assertEquals(0L, qjs.evaluate<Long>("Object.keys(globalThis).filter(k => k.startsWith('__parseChapterResult_')).length"))
			}
			qjs.evaluate<Any?>("if (globalThis.finishRequest) finishRequest('late text');", "<finish>")
			assertEquals(0L, qjs.evaluate<Long>("Object.keys(globalThis).filter(k => k.startsWith('__parseChapterResult_')).length"))
		}
	}

	@Test fun cancellationAndLateCompletionDoNotLeaveResultGlobals() = runBlocking {
		val engine = LNReaderEngine(LNReaderFetchBridge(OkHttpClient(), "cancel"))
		engine.createPluginContext("""
			exports.default = {popularNovels: function() {
				return new Promise(function(resolve) { globalThis.finishRequest = resolve; });
			}};
		""".trimIndent(), "cancel").use { qjs ->
			val bridge = LNReaderPluginBridge(qjs, "cancel")
			try {
				kotlinx.coroutines.withTimeout(250) { bridge.popularNovels(1) }
				org.junit.Assert.fail("The unresolved plugin promise should time out")
			} catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
			qjs.evaluate<Any?>("if (globalThis.finishRequest) finishRequest([]);", "<finish>")
			assertEquals(0L, qjs.evaluate<Long>("Object.keys(globalThis).filter(k => k.startsWith('__popularResult_')).length"))
		}
	}

	private lateinit var server: LoopbackHttpServer

	@Throws(Exception::class)
	override fun toString(): String = "LNReaderEngineInstrumentedTest"

	@org.junit.Before
	fun setUp() {
		server = LoopbackHttpServer()
		server.start()
	}

	@org.junit.After
	fun tearDown() {
		server.shutdown()
	}

	private fun pluginJs(siteUrl: String): String = """
		var Plugin = (function() {
			function Plugin() {
				this.id = 'test-plugin';
				this.name = 'Test Plugin';
				this.site = '$siteUrl';
				this.version = '1.0.0';
			}
			Plugin.prototype.popularNovels = function(page, opts) {
				return fetchApi(this.site + 'list?page=' + page).then(function(res) {
					return res.text();
				}).then(function(html) {
					var ${'$'} = cheerio.load(html);
					var novels = [];
					${'$'}('.novel').each(function(i, el) {
						var el2 = ${'$'}(el);
						novels.push({
							name: el2.find('.title').text(),
							path: el2.find('a').attr('href'),
							cover: ''
						});
					});
					return novels;
				});
			};
			Plugin.prototype.parseNovel = function(path) {
				return fetchApi(this.site + path).then(function(res) {
					return res.text();
				}).then(function(html) {
					var ${'$'} = cheerio.load(html);
					var chapters = [];
					${'$'}('.chapter').each(function(i, el) {
						var el2 = ${'$'}(el);
						chapters.push({ name: el2.text(), path: el2.attr('href') });
					});
					return {
						name: ${'$'}('.novel-title').text(),
						path: path,
						summary: ${'$'}('.summary').text(),
						chapters: chapters
					};
				});
			};
			Plugin.prototype.parseChapter = function(path) {
				return fetchApi(this.site + path).then(function(res) {
					return res.text();
				}).then(function(html) {
					var ${'$'} = cheerio.load(html);
					return ${'$'}('.content').html();
				});
			};
			return Plugin;
		})();
		exports.default = Plugin;
		exports.Plugin = Plugin;
	""".trimIndent()

	@Test
	fun fullPluginFlow() = runBlocking {
		val fetchBridge = LNReaderFetchBridge(OkHttpClient(), "test-plugin")
		val engine = LNReaderEngine(fetchBridge)
		engine.createPluginContext(pluginJs(server.baseUrl), "test-plugin").use { qjs ->
			val bridge = LNReaderPluginBridge(qjs, "test-plugin")

			val metadata = bridge.getPluginMetadata()
			assertEquals("test-plugin", metadata.id)
			assertEquals("Test Plugin", metadata.name)

			val novels = bridge.popularNovels(1)
			assertEquals(2, novels.size)
			assertEquals("Novel One", novels[0].name)
			assertEquals("novel/one", novels[0].path)

			val details = bridge.parseNovel("novel/one")
			assertEquals("Novel One", details.name)
			assertEquals("A summary.", details.summary)
			assertEquals(2, details.chapters.size)
			assertEquals("chapter/1", details.chapters[0].path)

			val chapterHtml = bridge.parseChapter("chapter/1")
			assertTrue(chapterHtml.contains("It was the best of times."))
		}
	}

	/**
	 * Minimal single-threaded HTTP server speaking just enough HTTP for OkHttp.
	 */
	private class LoopbackHttpServer {
		private val serverSocket = ServerSocket(0)
		private val routes = mutableMapOf<String, () -> String>()
		private var thread: Thread? = null

		val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}/"

		fun route(path: String, body: String) {
			routes[path] = { body }
		}

		fun start() {
			route("/list", LIST_HTML)
			route("/novel/one", NOVEL_HTML)
			route("/chapter/1", CHAPTER_HTML)
			thread = Thread {
				while (!serverSocket.isClosed) {
					try {
						val socket = serverSocket.accept()
						handle(socket)
					} catch (e: Exception) {
						break
					}
				}
			}.also { it.start() }
		}

		private fun handle(socket: Socket) {
			socket.use { s ->
				val reader = BufferedReader(InputStreamReader(s.getInputStream()))
				val writer = PrintWriter(s.getOutputStream())
				val requestLine = reader.readLine() ?: return
				val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: return
				var line: String?
				var contentLength = 0
				while (reader.readLine().also { line = it } != null && line!!.isNotBlank()) {
					if (line!!.lowercase().startsWith("content-length:")) {
						contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
					}
				}
				if (contentLength > 0) {
					val chars = CharArray(contentLength)
					var read = 0
					while (read < contentLength) {
						val n = reader.read(chars, read, contentLength - read)
						if (n < 0) break
						read += n
					}
				}
				val body = routes[path]?.invoke() ?: "not found"
				val status = if (path in routes) "200 OK" else "404 Not Found"
				val bytes = body.toByteArray(Charsets.UTF_8)
				writer.print("HTTP/1.1 $status\r\n")
				writer.print("Content-Type: text/html; charset=utf-8\r\n")
				writer.print("Content-Length: ${bytes.size}\r\n")
				writer.print("Connection: close\r\n")
				writer.print("\r\n")
				writer.print(body)
				writer.flush()
			}
		}

		fun shutdown() {
			runCatching { serverSocket.close() }
			thread?.interrupt()
		}

		companion object {
			private val LIST_HTML = """
				<html><body>
				<div class="novel"><h3 class="title"><a href="novel/one">Novel One</a></h3></div>
				<div class="novel"><h3 class="title"><a href="novel/two">Novel Two</a></h3></div>
				</body></html>
			""".trimIndent()

			private val NOVEL_HTML = """
				<html><body>
				<h1 class="novel-title">Novel One</h1>
				<div class="summary">A summary.</div>
				<div class="chapter-list">
				<div class="chapter" href="chapter/1">Chapter 1</div>
				<div class="chapter" href="chapter/2">Chapter 2</div>
				</div>
				</body></html>
			""".trimIndent()

			private val CHAPTER_HTML = """
				<html><body><div class="content"><p>It was the best of times.</p><p>It was the worst of times.</p></div></body></html>
			""".trimIndent()
		}
	}
}
