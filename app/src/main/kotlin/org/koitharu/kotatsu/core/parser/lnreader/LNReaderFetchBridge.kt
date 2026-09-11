/*
 * Copyright 2026 Kototoro contributors (https://github.com/skepsun/kototoro)
 * Licensed under Apache License, Version 2.0. Ported to Kaisoku for
 * LNReader plugin support.
 */
package org.koitharu.kotatsu.core.parser.lnreader

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

/**
 * Native fetch API bridge for LNReader JS plugins.
 * Registered as __nativeFetch in the QuickJS context.
 *
 * LNReader plugins call `fetchApi(url)` which resolves to this bridge.
 * Returns a map matching the Fetch Response interface: {ok, status, statusText, url, text, headers}
 *
 * CloudFlare/captcha exceptions from the interceptor chain are captured into
 * [pendingFatalException] and re-thrown by the caller after the JS poll completes,
 * so Kaisoku's solver UI engages even if the plugin swallows the error.
 */
class LNReaderFetchBridge(
	private val httpClient: OkHttpClient,
	private val pluginId: String,
) {
	companion object {
		private const val TAG = "LNReaderFetchBridge"
		private const val DEFAULT_USER_AGENT =
			"Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
	}

	var pendingFatalException: Exception? = null

	/**
	 * Performs an HTTP request matching the Fetch API contract.
	 * Called from JavaScript via native bridge.
	 */
	fun fetch(url: String, initStr: String? = null): String {
		return try {
			LnLog.d(TAG, "[$pluginId] Fetching: $url")

			if (!isValidUrl(url)) {
				return errorResponse(url, 0, "Security Error", "Invalid URL: $url")
			}

			val init = try {
				if (!initStr.isNullOrEmpty()) org.json.JSONObject(initStr) else null
			} catch (e: Exception) {
				null
			}

			val method = (init?.optString("method"))?.ifEmpty { "GET" }?.uppercase() ?: "GET"
			val headersMap = extractHeaders(init)
			val body = extractBody(init)

			val requestBuilder = Request.Builder()
				.url(url)

			val headerBuilder = Headers.Builder()
			if (!headersMap.containsKey("User-Agent")) {
				headerBuilder.add("User-Agent", DEFAULT_USER_AGENT)
			}
			headersMap.forEach { (key, value) ->
				headerBuilder.add(key, value)
			}
			requestBuilder.headers(headerBuilder.build())

			when (method) {
				"GET" -> requestBuilder.get()
				"POST" -> {
					val contentType = headersMap["Content-Type"] ?: "application/x-www-form-urlencoded"
					val requestBody = (body ?: "").toRequestBody(contentType.toMediaType())
					requestBuilder.post(requestBody)
				}
				"PUT" -> {
					val contentType = headersMap["Content-Type"] ?: "application/x-www-form-urlencoded"
					val requestBody = (body ?: "").toRequestBody(contentType.toMediaType())
					requestBuilder.put(requestBody)
				}
				"DELETE" -> requestBuilder.delete()
				else -> requestBuilder.method(method, null)
			}

			httpClient.newCall(requestBuilder.build()).execute().use { response ->
				val responseBody = response.body?.string() ?: ""
				val responseHeaders = mutableMapOf<String, String>()
				response.headers.forEach { (name, value) ->
					responseHeaders[name] = value
				}

				LnLog.d(TAG, "[$pluginId] Success: ${response.code} (${responseBody.length} bytes)")

				val responseJson = org.json.JSONObject()
				responseJson.put("ok", response.isSuccessful)
				responseJson.put("status", response.code)
				responseJson.put("statusText", response.message.ifEmpty { "OK" })
				responseJson.put("url", url)
				responseJson.put("text", responseBody)

				val jsHeaders = org.json.JSONObject()
				responseHeaders.forEach { (k, v) -> jsHeaders.put(k, v) }
				responseJson.put("headers", jsHeaders)

				responseJson.toString()
			}
		} catch (e: Exception) {
			val causeList = generateSequence(e as Throwable) { it.cause }.toList()
			val interactiveEx = causeList.find {
				it.javaClass.name.contains("CloudFlare") ||
					it.javaClass.name.contains("InteractiveAction")
			} as? Exception

			if (interactiveEx != null) {
				LnLog.e(TAG, "[$pluginId] Native Protection engaged: ${interactiveEx.message}")
				pendingFatalException = interactiveEx
				errorResponse(url, 403, "Protected", interactiveEx.message ?: "Protected")
			} else if (e is java.io.IOException) {
				LnLog.e(TAG, "[$pluginId] Network error: ${e.message}")
				errorResponse(url, 0, "Network Error", e.message ?: "Network error")
			} else {
				LnLog.e(TAG, "[$pluginId] Fatal error: ${e.message}")
				errorResponse(url, 0, "Fatal Error", "Fatal error: ${e.message}")
			}
		}
	}

	/**
	 * Generate the JavaScript wrapper function for injection into QuickJS.
	 */
	fun toJavaScriptFunction(): String {
		return """
			class Headers {
				constructor(init) {
					this.map = {};
					if (init) {
						if (typeof init.forEach === 'function') {
							init.forEach((value, key) => this.append(key, value));
						} else {
							for (const key in init) {
								this.append(key, init[key]);
							}
						}
					}
				}
				append(key, value) {
					const k = key.toLowerCase();
					if (this.map[k]) {
						this.map[k] += ', ' + value;
					} else {
						this.map[k] = value;
					}
				}
				set(key, value) {
					this.map[key.toLowerCase()] = value;
				}
				get(key) {
					return this.map[key.toLowerCase()] || null;
				}
				has(key) {
					return this.map.hasOwnProperty(key.toLowerCase());
				}
				delete(key) {
					delete this.map[key.toLowerCase()];
				}
				forEach(callback) {
					for (const key in this.map) {
						callback(this.map[key], key, this);
					}
				}
				toJSON() {
					return this.map;
				}
			}
			globalThis.Headers = Headers;

			globalThis.fetchApi = function(url, init) {
				var initStr = init ? JSON.stringify(init) : "{}";
				var responseStr = __nativeFetch(url, initStr);
				var response = responseStr ? JSON.parse(responseStr) : {};

				if (response.error) {
					return Promise.reject(new Error(response.error));
				}

				var resObj = {
					ok: response.ok,
					status: response.status,
					statusText: response.statusText,
					url: response.url,
					headers: new Headers(response.headers || {})
				};

				resObj.text = function() { return Promise.resolve(response.text || ''); };
				resObj.json = function() {
					try {
						return Promise.resolve(JSON.parse(response.text || '{}'));
					} catch(e) {
						return Promise.reject(e);
					}
				};
				return Promise.resolve(resObj);
			};

			globalThis.fetch = function(url, init) {
				return globalThis.fetchApi(url, init);
			};
		""".trimIndent()
	}

	private fun extractHeaders(init: org.json.JSONObject?): Map<String, String> {
		if (init == null || !init.has("headers")) return emptyMap()
		val headersObj = init.optJSONObject("headers") ?: return emptyMap()
		val result = mutableMapOf<String, String>()
		val keys = headersObj.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			result[key] = headersObj.optString(key)
		}
		return result
	}

	private fun extractBody(init: org.json.JSONObject?): String? {
		if (init == null || !init.has("body")) return null
		val body = init.opt("body")
		return when (body) {
			is String -> body
			is org.json.JSONObject -> {
				val parts = mutableListOf<String>()
				val keys = body.keys()
				while (keys.hasNext()) {
					val key = keys.next()
					parts.add("$key=${java.net.URLEncoder.encode(body.optString(key), "UTF-8")}")
				}
				parts.joinToString("&")
			}
			else -> body?.toString()
		}
	}

	private fun isValidUrl(url: String): Boolean {
		return try {
			val uri = URI(url)
			val scheme = uri.scheme?.lowercase()
			scheme == "http" || scheme == "https"
		} catch (e: Exception) {
			false
		}
	}

	private fun errorResponse(url: String, status: Int, statusText: String, error: String): String {
		val res = org.json.JSONObject()
		res.put("ok", false)
		res.put("status", status)
		res.put("statusText", statusText)
		res.put("url", url)
		res.put("text", "")
		res.put("error", error)
		return res.toString()
	}
}
