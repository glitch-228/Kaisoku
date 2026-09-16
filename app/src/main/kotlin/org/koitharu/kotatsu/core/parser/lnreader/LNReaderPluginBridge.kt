/*
 * Copyright 2026 Kototoro contributors (https://github.com/skepsun/kototoro)
 * Licensed under Apache License, Version 2.0. Ported to Kaisoku for
 * LNReader plugin support.
 */
package org.koitharu.kotatsu.core.parser.lnreader

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridge between Kotlin and LNReader JavaScript plugins.
 * Calls JS methods via evaluateScript() with an IIFE + Promise polling pattern.
 *
 * Each plugin method:
 * 1. Wraps the call in an async IIFE
 * 2. Stores result in globalThis.__result_{id}
 * 3. Polls with exponential backoff until done
 * 4. Parses JS result into Kotlin data classes
 */
class LNReaderPluginBridge(
	private val qjs: QuickJs,
	private val pluginId: String,
	private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
	companion object {
		private const val TAG = "LNReaderPluginBridge"
		private const val DEFAULT_TIMEOUT_MS = 30_000L
	}

	private var nextCall = 0L

	private val sanitizedId = pluginId.replace(Regex("[^a-zA-Z0-9_]"), "_")
	private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

	// ==================== Plugin Metadata ====================

	/**
	 * Extract plugin metadata from JS context.
	 */
	suspend fun getPluginMetadata(): LNReaderPluginMetadata {
		val metadataJson = qjs.evaluate<String>(
			"""
			(function() {
				var plugin = globalThis.__plugin_${sanitizedId};
				if (!plugin) return JSON.stringify({error: 'plugin not found'});
				return JSON.stringify({
					id: plugin.id || '',
					name: plugin.name || 'Unknown',
					site: plugin.site || '',
					version: plugin.version || '1.0.0',
					icon: plugin.icon || '',
					lang: plugin.lang || 'en'
				});
			})();
			""".trimIndent(),
			"<metadata>"
		) ?: "{}"

		val obj = json.parseToJsonElement(metadataJson).jsonObject
		return LNReaderPluginMetadata(
			id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: pluginId,
			name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "Unknown",
			site = obj["site"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "",
			version = obj["version"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "1.0.0",
			lang = obj["lang"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "en",
			icon = obj["icon"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "",
		)
	}

	/**
	 * Extracts the plugin's exported `filters` object statically.
	 */
	suspend fun getFilters(): List<LNReaderFilter> {
		val script = """
			(function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin || !plugin.filters) return JSON.stringify({ success: true, data: [] });

					var result = [];
					for (var key in plugin.filters) {
						var filterObj = plugin.filters[key];
						if (!filterObj || typeof filterObj !== 'object') continue;

						var opts = [];
						if (Array.isArray(filterObj.options)) {
							opts = filterObj.options.map(function(o) {
								return { label: String(o.label||''), value: String(o.value||'') };
							});
						}

						result.push({
							key: String(key),
							label: String(filterObj.label || key),
							type: String(filterObj.type || 'picker'),
							options: opts
						});
					}
					return JSON.stringify({ success: true, data: result });
				} catch (e) {
					return JSON.stringify({ success: false, error: String(e) });
				}
			})();
		""".trimIndent()

		val resultJson = qjs.evaluate<String>(script, "<getFilters>")
		return try {
			val obj = json.parseToJsonElement(resultJson).jsonObject
			if (obj["success"]?.jsonPrimitive?.booleanOrNull == true) {
				val dataArr = obj["data"]?.jsonArray ?: JsonArray(emptyList())
				dataArr.mapNotNull {
					val f = it.jsonObject
					val key = f["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
					val label = f["label"]?.jsonPrimitive?.contentOrNull ?: key
					val type = f["type"]?.jsonPrimitive?.contentOrNull ?: "picker"
					val opts = f["options"]?.jsonArray?.mapNotNull { optElement ->
						val optObj = optElement.jsonObject
						val oLabel = optObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
						val oValue = optObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
						LNReaderFilterOption(oLabel, oValue)
					} ?: emptyList()
					LNReaderFilter(key, label, type, opts)
				}
			} else throw LNReaderJSException("getFilters failed: ${obj["error"]?.jsonPrimitive?.contentOrNull}")
		} catch (e: Exception) {
			throw LNReaderJSException("Invalid plugin filters: ${e.message}", e)
		}
	}

	// ==================== Content Methods ====================

	/**
	 * Call plugin.popularNovels(page, {filters}).
	 * Returns list of novel items.
	 */
	suspend fun popularNovels(page: Int, selectedFilters: Map<String, String>? = null, latest: Boolean = false): List<LNReaderNovelItem> {
		val resultVar = "__popularResult_${sanitizedId}_${nextCall++}"
		val filterOverrides = if (selectedFilters.isNullOrEmpty()) "" else {
			selectedFilters.entries.joinToString("\n") { (k, v) ->
				"if(defaultFilters['${escapeForJS(k)}']) defaultFilters['${escapeForJS(k)}'].value = '${escapeForJS(v)}';"
			}
		}
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.popularNovels !== 'function') throw new Error('popularNovels not found');

					var defaultFilters = {};
				if (plugin.filters) {
					// Pass the full filter objects so plugins can access .value, .type, etc.
					for (var key in plugin.filters) {
						if (plugin.filters[key]) {
							defaultFilters[key] = JSON.parse(JSON.stringify(plugin.filters[key])); // deep copy
						} else {
							defaultFilters[key] = { value: '' };
						}
					}
				}
				$filterOverrides
				var result = await plugin.popularNovels($page, { showLatestNovels: $latest, filters: defaultFilters });
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = { success: true, data: JSON.stringify(result) };
				} catch (error) {
					console.error("PLUGIN EVAL ERROR: " + String(error) + "\nSTACK: " + (error ? error.stack : "null"));
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = {
						success: false,
						error: (error && error.message) ? error.message : String(error)
					};
				}
			})();
		""".trimIndent()

		val resultJson = executeAsyncAndPoll(script, resultVar, "popularNovels")
		return parseNovelList(resultJson)
	}

	/**
	 * Call plugin.searchNovels(query, page).
	 */
	suspend fun searchNovels(query: String, page: Int): List<LNReaderNovelItem> {
		val resultVar = "__searchResult_${sanitizedId}_${nextCall++}"
		val escapedQuery = escapeForJS(query)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.searchNovels !== 'function') throw new Error('searchNovels not found');
					var result = await plugin.searchNovels('$escapedQuery', $page);
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = { success: true, data: JSON.stringify(result) };
				} catch (error) {
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = {
						success: false,
						error: (error && error.message) ? error.message : String(error)
					};
				}
			})();
		""".trimIndent()

		val resultJson = executeAsyncAndPoll(script, resultVar, "searchNovels")
		return parseNovelList(resultJson)
	}

	/**
	 * Call plugin.parseNovel(url).
	 * Returns novel details with chapter list.
	 */
	suspend fun parseNovel(novelPath: String): LNReaderNovelDetails {
		val resultVar = "__parseNovelResult_${sanitizedId}_${nextCall++}"
		val escapedPath = escapeForJS(novelPath)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.parseNovel !== 'function') throw new Error('parseNovel not found');
					var result = await plugin.parseNovel('$escapedPath');
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = { success: true, data: JSON.stringify(result) };
				} catch (error) {
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = {
						success: false,
						error: (error && error.message) ? error.message : String(error)
					};
				}
			})();
		""".trimIndent()

		val resultJson = executeAsyncAndPoll(script, resultVar, "parseNovel")
		return parseNovelDetails(resultJson)
	}

	/**
	 * Call plugin.parsePage(novelPath, page) to fetch chapters for a single page.
	 * Many LNReader plugins paginate their chapter lists and return them via this method.
	 */
	suspend fun parsePage(novelPath: String, page: Int): List<LNReaderChapter> {
		val resultVar = "__parsePageResult_${sanitizedId}_${page}_${nextCall++}"
		val escapedPath = escapeForJS(novelPath)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.parsePage !== 'function') {
						throw new Error('parsePage required for paginated chapters');
					}
					var res = await plugin.parsePage('$escapedPath', $page);
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = { success: true, data: JSON.stringify(res) };
				} catch (error) {
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = {
						success: false,
						error: (error && error.message) ? error.message : String(error)
					};
				}
			})();
		""".trimIndent()

		val resultJson = executeAsyncAndPoll(script, resultVar, "parsePage[$page]")
		return try {
			val element = json.parseToJsonElement(resultJson)
			val array = if (element is JsonObject) {
				element["chapters"]?.jsonArray ?: error("Chapter page has no chapters array")
			} else {
				element.jsonArray
			}

			array.map { chElement ->
				val chObj = chElement.jsonObject
				val chName = chObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
				val chPath = chObj["path"]?.jsonPrimitive?.contentOrNull
					?: chObj["url"]?.jsonPrimitive?.contentOrNull ?: error("Chapter is missing its path")
				val releaseTime = chObj["releaseTime"]?.jsonPrimitive?.contentOrNull
				val chapterNumber = chObj["chapterNumber"]?.jsonPrimitive?.contentOrNull
					?: chObj["chapterNumber"]?.jsonPrimitive?.intOrNull?.toString()
				LNReaderChapter(name = chName, path = chPath, releaseTime = releaseTime, chapterNumber = chapterNumber)
			}
		} catch (e: Exception) {
			throw LNReaderJSException("Invalid chapter page $page: ${e.message}", e)
		}
	}

	/**
	 * Call plugin.parseChapter(chapterUrl).
	 * Returns chapter HTML text content.
	 */
	suspend fun parseChapter(chapterPath: String): String {
		val resultVar = "__parseChapterResult_${sanitizedId}_${nextCall++}"
		val escapedChapterPath = escapeForJS(chapterPath)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.parseChapter !== 'function') throw new Error('parseChapter not found');
					var result = await plugin.parseChapter('$escapedChapterPath');
					var text = '';
					if (typeof result === 'string') {
						text = result;
					} else if (result && result.chapterText) {
						text = result.chapterText;
					} else if (result && result.text) {
						text = result.text;
					} else {
						throw new Error('parseChapter returned unsupported content');
					}
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = { success: true, data: text };
				} catch (error) {
					if (Object.prototype.hasOwnProperty.call(globalThis, '$resultVar')) globalThis.$resultVar = {
						success: false,
						error: (error && error.message) ? error.message : String(error)
					};
				}
			})();
		""".trimIndent()

		return executeAsyncAndPoll(script, resultVar, "parseChapter").takeIf(String::isNotBlank)
			?: throw LNReaderJSException("parseChapter returned empty text for $chapterPath")
	}

	// ==================== Internal Helpers ====================

	/**
	 * Execute an async JS script, poll for result in global variable, return JSON string.
	 */
	private suspend fun executeAsyncAndPoll(
		script: String,
		resultVar: String,
		methodName: String,
	): String = try {
		withTimeoutOrNull(timeoutMs) {
			qjs.evaluate<Any?>("globalThis.$resultVar = undefined;\n" + script, "<$methodName>")
			while (true) {
				val result = qjs.evaluate<String?>(
					"JSON.stringify(globalThis.$resultVar) || null", "<result>",
				)
				if (result != null) {
					val obj = json.parseToJsonElement(result).jsonObject
					if (obj["success"]?.jsonPrimitive?.booleanOrNull != true) {
						val reason = obj["error"]?.jsonPrimitive?.contentOrNull
							?.takeUnless { it.isBlank() || it == "null" || it == "undefined" }
							?: "Plugin rejected the request without error details. Retry the chapter or open it on the source website."
						throw LNReaderJSException("$methodName failed: $reason")
					}
					return@withTimeoutOrNull obj["data"]?.jsonPrimitive?.contentOrNull
						?: throw LNReaderJSException("$methodName returned no data")
				}
				delay(20)
			}
			error("Unreachable")
		} ?: throw LNReaderJSException("$methodName timed out after ${timeoutMs / 1000} seconds. Please retry.")
	} finally {
		withContext(NonCancellable) {
			runCatching { qjs.evaluate<Any?>("delete globalThis.$resultVar;", "<cleanup>") }
		}
	}

	suspend fun resolveUrl(path: String, isNovel: Boolean = true): String? = qjs.evaluate<String?>(
		"""
		(function() {
			var plugin = globalThis.__plugin_${sanitizedId};
			return typeof plugin.resolveUrl === 'function' ? plugin.resolveUrl('${escapeForJS(path)}', $isNovel) : null;
		})();
		""".trimIndent(), "<resolveUrl>",
	)

	suspend fun imageHeaders(): Map<String, String> {
		val result = qjs.evaluate<String>(
			"JSON.stringify(globalThis.__plugin_${sanitizedId}.imageRequestInit?.headers || {})", "<imageHeaders>",
		)
		return json.parseToJsonElement(result).jsonObject.mapValues { it.value.jsonPrimitive.content }
	}

	/**
	 * Parse JSON array of novel items.
	 * LNReader format: [{name, path, cover}, ...]
	 */
	private fun parseNovelList(jsonStr: String): List<LNReaderNovelItem> {
		return try {
			val array = json.parseToJsonElement(jsonStr).jsonArray
			array.map { element ->
				val obj = element.jsonObject
				val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: error("Novel is missing its name")
				val path = obj["path"]?.jsonPrimitive?.contentOrNull
					?: obj["url"]?.jsonPrimitive?.contentOrNull ?: error("Novel is missing its path")
				val cover = obj["cover"]?.jsonPrimitive?.contentOrNull ?: ""
				require(name.isNotBlank() && path.isNotBlank()) { "Novel has an empty name or path" }
				LNReaderNovelItem(name = name, path = path, cover = cover)
			}
		} catch (e: Exception) {
			throw LNReaderJSException("Invalid novel list: ${e.message}", e)
		}
	}

	/**
	 * Parse novel details JSON.
	 * LNReader format: {name, path, cover, author, summary, genres, status, chapters: [{name, path, releaseTime}]}
	 */
	private fun parseNovelDetails(jsonStr: String): LNReaderNovelDetails {
		val obj = try {
			json.parseToJsonElement(jsonStr).jsonObject
		} catch (e: Exception) {
			LnLog.e(TAG, "Failed to parse novel details JSON: ${e.message}\nRaw JSON: ${jsonStr.take(1000)}")
			throw LNReaderJSException("Invalid novel details: ${e.message}", e)
		}

		require(obj.isNotEmpty()) { "Empty novel details" }

		val totalPages = obj["totalPages"]?.jsonPrimitive?.intOrNull ?: 0
		val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
		val path = obj["path"]?.jsonPrimitive?.contentOrNull
			?: obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
		val cover = obj["cover"]?.jsonPrimitive?.contentOrNull ?: ""
		val author = obj["author"]?.jsonPrimitive?.contentOrNull ?: ""
		val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
			?: obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
		val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: ""

		val genres = try {
			when (val g = obj["genres"]) {
				is JsonArray -> g.map { item ->
                    if (item is JsonObject) item["name"]?.jsonPrimitive?.contentOrNull
                        ?: item["title"]?.jsonPrimitive?.contentOrNull ?: error("Genre is missing its name")
                    else item.jsonPrimitive.contentOrNull ?: error("Invalid genre")
                }
				is JsonPrimitive -> g.contentOrNull.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
				else -> emptyList()
			}
		} catch (e: Exception) { throw LNReaderJSException("Invalid genres: ${e.message}", e) }

		val chapters = try {
			obj["chapters"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonArray?.map { element ->
				val chObj = element.jsonObject
				val chName = chObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
				val chPath = chObj["path"]?.jsonPrimitive?.contentOrNull
					?: chObj["url"]?.jsonPrimitive?.contentOrNull ?: error("Chapter is missing its path")
				val releaseTime = chObj["releaseTime"]?.jsonPrimitive?.contentOrNull
				val chapterNumber = chObj["chapterNumber"]?.jsonPrimitive?.contentOrNull
					?: chObj["chapterNumber"]?.jsonPrimitive?.intOrNull?.toString()
				LNReaderChapter(name = chName, path = chPath, releaseTime = releaseTime, chapterNumber = chapterNumber)
			} ?: emptyList()
		} catch (e: Exception) {
			throw LNReaderJSException("Invalid chapters: ${e.message}", e)
		}

		return LNReaderNovelDetails(
			name = name, path = path, cover = cover,
			author = author, summary = summary, genres = genres,
			status = status, chapters = chapters, totalPages = totalPages
		)
	}

	private fun escapeForJS(str: String): String {
		return str
			.replace("\\", "\\\\")
			.replace("'", "\\'")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t")
	}
}

// ==================== Data Models ====================

data class LNReaderNovelItem(
	val name: String,
	val path: String,
	val cover: String = ""
)

data class LNReaderNovelDetails(
	val name: String,
	val path: String,
	val cover: String = "",
	val author: String = "",
	val summary: String = "",
	val genres: List<String> = emptyList(),
	val status: String = "",
	val chapters: List<LNReaderChapter> = emptyList(),
	val totalPages: Int = 0
)

data class LNReaderChapter(
	val name: String,
	val path: String,
	val releaseTime: String? = null,
	val chapterNumber: String? = null
)

data class LNReaderFilter(
	val key: String,
	val label: String,
	val type: String,
	val options: List<LNReaderFilterOption>
)

data class LNReaderFilterOption(
	val label: String,
	val value: String
)
