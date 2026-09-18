package org.koitharu.kotatsu.core.parser.lnreader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor

/** Runs the shipped JS shims and pinned plugin bundles against the real Kotlin bridges.
 * Node provides the host JS VM; the separate instrumented test covers Android QuickJS JNI. */
class LNReaderPublishedPluginsTest {
	private fun runPlugin(id: String, html: (String) -> String, assertions: String, live: Boolean = false): JSONObject {
		val process = try { ProcessBuilder("node", "-e", HOST).start() } catch (_: java.io.IOException) {
			assumeTrue("Node is required for the published plugin compatibility tests", false)
			error("Node unavailable")
		}
		val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
			.addInterceptor(CloudFlareInterceptor()).addInterceptor { chain ->
			if (live) {
				val response = chain.proceed(chain.request())
				if (id == "webnovel") {
					val body = response.body.string()
					java.io.File(System.getProperty("java.io.tmpdir"), "kaisoku-webnovel-live.html").writeText(body)
					println("LIVE HTTP webnovel: ${response.code}, ${body.length} characters")
					return@addInterceptor response.newBuilder().body(body.toResponseBody(response.body.contentType())).build()
				}
				return@addInterceptor response
			}
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(html(chain.request().url.toString()).toResponseBody("text/html; charset=utf-8".toMediaType())).build()
		}.build()
		val fetch = LNReaderFetchBridge(client, id)
		val engine = LNReaderEngine(fetch)
		val dom = LNReaderDomBridge()
		val storage = LNReaderStorage()
		val scripts = listOf(
			fetch.toJavaScriptFunction(), engine.globalPolyfillsScript(), engine.resource("cheerio.js"),
			engine.resource("dayjs.min.js"), engine.resource("localizedFormat.js"),
			"dayjs.extend(dayjs_plugin_localizedFormat);", engine.storageScript(), engine.moduleScript(),
			checkNotNull(javaClass.getResourceAsStream("/lnreader/plugins/$id.js")).bufferedReader().use { it.readText() },
		)
		val writer = process.outputStream.bufferedWriter()
		writer.write(JSONObject().put("scripts", JSONArray(scripts)).put("assertions", assertions).toString() + "\n")
		writer.flush()
		val executor = Executors.newSingleThreadExecutor()
		try {
			return executor.submit<JSONObject> {
				val reader = process.inputStream.bufferedReader()
				while (true) {
					val line = reader.readLine() ?: error("JS VM exited: " + process.errorStream.bufferedReader().readText())
					val request = JSONObject(line)
					if (request.has("error")) error(request.getString("error"))
					if (request.has("result")) {
						fetch.pendingFatalException?.let { throw it }
						return@submit request.getJSONObject("result")
					}
					val args = request.getJSONArray("args")
					val result: Any? = when (request.getString("method")) {
						"dom" -> dom.call(args.getString(0), args.getString(1))
						"fetch" -> fetch.fetch(args.getString(0), args.optString(1))
						"storage" -> when (args.getString(0)) {
							"keys" -> JSONArray(storage.keys().toList()).toString()
							"get" -> storage.get(args.getString(1))
							"set" -> { storage.set(args.getString(1), args.getString(2)); null }
							else -> { storage.set(args.getString(1), null); null }
						}
						else -> error("Unexpected native call")
					}
					writer.write(JSONObject().put("value", result ?: JSONObject.NULL).toString() + "\n")
					writer.flush()
				}
				error("Unreachable")
			}.get(if (live) 120 else 30, TimeUnit.SECONDS)
		} finally {
			process.destroyForcibly()
			executor.shutdownNow()
		}
	}

	@Test fun livePublishedPluginChecksWhenRequested() {
		assumeTrue("Opt-in live source checks", System.getenv("KAISOKU_LIVE_PLUGINS") == "1")
		for (id in listOf("webnovel", "jaomix.ru", "RLIB", "rulate-api")) {
			val requested = System.getenv("KAISOKU_LIVE_PLUGIN")
			if (requested != null && requested != id) continue
			val result = runCatching { runPlugin(id, { error("No fixture in live checks") }, """
				var plugin = exports.default, report = {};
				try {
					var list = await plugin.popularNovels(1, {filters: plugin.filters});
					report.browse = list.length;
					if (!list.length) return report;
					var item = list[0]; report.title = item.name;
					try { report.search = (await plugin.searchNovels(item.name, 1)).length; } catch(e) { report.searchError = e.message; }
					var details = await plugin.parseNovel(item.path);
					report.details = details.name; report.chapters = (details.chapters || []).length;
					if (details.totalPages > 1 && plugin.parsePage) {
						var page = await plugin.parsePage(item.path, 2); report.chapterPage2 = (page.chapters || page).length;
					}
					if (report.chapters) report.chapterTextLength = (await plugin.parseChapter(details.chapters[0].path)).length;
				} catch(e) { report.error = e.message; }
				return report;
			""".trimIndent(), live = true) }
			println("LIVE $id: " + result.fold({ it.toString() }, { it.message.orEmpty().takeLast(1500) }))
		}
	}

	@Test fun webnovelRetainsTitlesCoversAndChainedDetails() {
		val result = runPlugin("webnovel", { url ->
			when {
				url.endsWith("/chapter") -> "<div class='cha-tit'>Chapter</div><div class='cha-words'><p>Readable text<span class='para-comment'>Comment</span></p></div>"
				"/catalog" in url -> "<div class='volume-item'>Volume 1<ul><li><a title='First chapter' href='/book/one/chapter'>First</a></li></ul></div>"
				"/stories/" in url || "/search" in url -> "<ul class='j_category_wrapper j_list_container'><li><a class='g_thumb' title='One' href='/book/one'><img data-original='//images/one' src='//images/one'></a></li></ul>"
				else -> "<a class='g_thumb'><img alt='One' src='//images/one'></a><div class='j_synopsis'><p>First<br>Second</p></div><div class='det-info'><span class='c_s'>Author:</span><b>Writer</b></div><div class='det-hd-detail'><span class='det-hd-tag' title='Fantasy'></span><svg title='Status'></svg><span>Ongoing</span></div>"
			}
		}, """
			var plugin = exports.default;
			var list = await plugin.popularNovels(1, {filters: plugin.filters});
			var search = await plugin.searchNovels('One', 1);
			var details = await plugin.parseNovel(list[0].path);
			return {list: list, search: search, details: details, text: await plugin.parseChapter(details.chapters[0].path)};
		""".trimIndent())
		assertEquals("One", result.getJSONArray("list").getJSONObject(0).getString("name"))
		assertEquals("https://images/one", result.getJSONArray("list").getJSONObject(0).getString("cover"))
		val details = result.getJSONObject("details")
		assertEquals("One", details.getString("name"))
		assertEquals("First\nSecond", details.getString("summary"))
		assertEquals("Writer", details.getString("author"))
		assertEquals(1, details.getJSONArray("chapters").length())
		assertTrue(result.getString("text").contains("Readable text"))
		assertFalse(result.getString("text").contains("Comment"))
	}

	@Test fun jaomixChapterPagesNormalizeAfterDeduplication() = kotlinx.coroutines.test.runTest {
		// Reduced from the live first/last pages of /silnee-blagodarya-zemle/ (2026-09-16).
		val result = runPlugin("jaomix.ru", { url ->
			if (url.contains("admin-ajax")) {
				"<div class='title'><a title='Глава 2: Случайная встреча' href='https://jaomix.ru/book/2/'></a></div>" +
					"<div class='title'><a title='Глава 1: Молодой Ван Сюань' href='https://jaomix.ru/book/1/'></a></div>"
			} else {
				"<div class='desc-book'><h1>Сильнее благодаря Земле</h1></div>" +
					"<div class='title'><a title='Главы 624–611: Убийство и совершенствование' href='https://jaomix.ru/book/624/'></a></div>" +
					"<div class='title'><a title='Главы 623–610: Временная буря' href='https://jaomix.ru/book/623/'></a></div>"
			}
		}, """
			return {first: (await exports.default.parseNovel('/book/')).chapters,
			        last: (await exports.default.parsePage('/book/', '13')).chapters};
		""".trimIndent())
		fun chapters(key: String): List<LNReaderChapter> {
			val array = result.getJSONArray(key)
			return (0 until array.length()).map { index ->
				val item = array.getJSONObject(index)
				LNReaderChapter(item.getString("name"), item.getString("path"))
			}
		}
		val first = chapters("first")
		val last = chapters("last")
		val all = loadAllNovelChapters(LNReaderNovelDetails("Book", "/book/", chapters = first, totalPages = 2)) {
			if (it == 1) first else last
		}
		assertEquals(listOf("/book/1/", "/book/2/", "/book/623/", "/book/624/"),
			normalizeNovelChapterOrder("jaomix.ru", all) { it.name }.map { it.path })
	}

	@Test fun jaomixChapterKeepsContentAndRemovesAdNodes() {
		// Structure checked against the reported chapter 291 on 2026-09-16; text is synthetic.
		val result = runPlugin("jaomix.ru", { _ ->
			"<div class='entry-content'><p>First paragraph.</p><div class='adblock-service'>Ad</div>" +
				"<p>Second <a href='/book/'>linked</a> paragraph.</p><div class='lazyblock'>Ad</div></div>"
		}, """
			return {text: await exports.default.parseChapter('/moya-zhena-bessmertnaya-lisa/glava-291-zhdat-etu-princessu-dva-goda/')};
		""".trimIndent())
		val text = result.getString("text")
		assertTrue(text.contains("First paragraph."))
		assertTrue(text.contains("Second linked paragraph."))
		assertFalse(text.contains("Ad"))
		assertFalse(text.contains("<a"))
	}

	@Test fun jaomixSearchEncodesCyrillicAndKeepsAttributes() {
		val requests = ArrayList<String>()
		val result = runPlugin("jaomix.ru", { url ->
			requests.add(url)
			"<div class='block-home'><div class='one'><div class='img-home'><a title='Моя жена' href='https://jaomix.ru/one/'><img src='/one-150x150.jpg'></a></div></div></div>"
		}, """
			var novels = await exports.default.searchNovels('Моя жена', 1);
			return {novels: novels, formatted: require('dayjs')('2026-09-12').format('LLL')};
		""".trimIndent())
		assertEquals("Моя жена", result.getJSONArray("novels").getJSONObject(0).getString("name"))
		assertTrue(requests.single().contains("%20"))
		assertFalse(requests.single().contains(" "))
		assertTrue(result.getString("formatted").contains("2026"))
	}

	@Test fun ranobelibLoadsIntlAndUsesRealStorageAndDayjs() {
		val result = runPlugin("RLIB", { url -> when {
			"/chapter?" in url -> """{"data":{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Readable text"}]}]}}}"""
			url.endsWith("/chapters") -> """{"data":[{"volume":"1","number":"1","index":1,"branches":[{"branch_id":0,"created_at":"2026-09-12T12:00:00.000Z"}]}]}"""
			"fields[]=summary" in java.net.URLDecoder.decode(url, "UTF-8") -> """{"data":{"rus_name":"One","genres":[{"name":"Fantasy"}],"summary":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Summary"}]}]}}}"""
			else -> """{"data":[{"rus_name":"One","slug_url":"1--one","cover":{"default":"https://images/one"}}]}"""
		} }, """
			var novels = await exports.default.searchNovels('One', 1);
			var details = await exports.default.parseNovel(novels[0].path);
			var storage = require('@libs/storage').storage;
			storage.set('test', {value: 42});
			storage.set('expired', 9, Date.now() - 1);
			if (storage.get('expired') !== undefined) throw new Error('Expired storage item survived');
			return {novels: novels, headers: exports.default.imageRequestInit.headers,
				stored: storage.get('test').value, zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
				text: await exports.default.parseChapter(details.chapters[0].path), url: exports.default.resolveUrl(novels[0].path, true)};
		""".trimIndent())
		assertEquals("One", result.getJSONArray("novels").getJSONObject(0).getString("name"))
		assertEquals(42, result.getInt("stored"))
		val headers = result.getJSONObject("headers")
		assertEquals("https://ranobelib.me", headers.getString("Referer"))
		assertEquals("3", headers.getString("Site-Id"))
		assertTrue(headers.getString("Accept").contains("image/"))
		assertTrue(result.getString("zone").isNotBlank())
		assertTrue(result.getString("text").contains("Readable text"))
		assertEquals("https://ranobelib.me/ru/book/1--one", result.getString("url"))
	}

	@Test fun rulateUsesTheFetchJsonContract() {
		val result = runPlugin("rulate-api", { url -> when {
			"/bookChapters?" in url -> """{"response":[{"id":2,"title":"Chapter","can_read":true,"subscription":0,"ord":1,"cdate":1789214400}]}"""
			"/chapter?" in url -> """{"response":{"text":"<p>Readable text</p>"}}"""
			"/book?" in url -> """{"response":{"t_title":"One","genres":[],"tags":[],"description":"Summary"}}"""
			else -> """{"status":"success","response":[{"id":1,"t_title":"One","img":"https://images/one"}]}"""
		} }, """
			var novels = await exports.default.searchNovels('One', 1);
			var details = await exports.default.parseNovel(novels[0].path);
			return {novels: novels, text: await exports.default.parseChapter(details.chapters[0].path)};
		""".trimIndent())
		assertEquals("One", result.getJSONArray("novels").getJSONObject(0).getString("name"))
		assertTrue(result.getString("text").contains("Readable text"))
	}

	companion object {
		private val HOST = """
			const fs = require('fs'), vm = require('vm');
			function readLine() {
			    let bytes = [], b = Buffer.alloc(1);
			    while (fs.readSync(0, b, 0, 1, null)) { if (b[0] === 10) break; bytes.push(b[0]); }
			    return Buffer.from(bytes).toString('utf8');
			}
			function native(method, args) {
			    fs.writeSync(1, JSON.stringify({method, args}) + '\n');
			    return JSON.parse(readLine()).value;
			}
			const request = JSON.parse(readLine());
			const context = vm.createContext({
			    console: {log() {}, error() {}, warn() {}, info() {}, debug() {}},
			    __nativeDom: (...args) => native('dom', args),
			    __nativeFetch: (...args) => native('fetch', args),
			    __nativeStorage: (...args) => native('storage', args)
			});
			(async () => {
			    for (const script of request.scripts) vm.runInContext(script, context, {timeout: 5000});
			    const result = await vm.runInContext('(async function(){' + request.assertions + '})()', context);
			    fs.writeSync(1, JSON.stringify({result}) + '\n');
			})().catch(error => fs.writeSync(1, JSON.stringify({error: error.stack}) + '\n'));
		""".trimIndent()
	}
}
