/*
 * Copyright 2026 Kototoro contributors (https://github.com/skepsun/kototoro)
 * Licensed under Apache License, Version 2.0. Ported to Kaisoku for
 * LNReader plugin support; storage coupling replaced with a plain
 * installation callback.
 */
package org.koitharu.kotatsu.core.parser.lnreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages LNReader plugin repositories.
 *
 * Fetches the plugin index (plugins.min.json) and downloads individual JS bundles.
 * Installation into local storage is delegated to [installer].
 */
class LNReaderRepository(
	private val httpClient: OkHttpClient,
	private val installer: LNReaderPluginInstaller,
) {

	companion object {
		private const val TAG = "LNReaderRepository"

		/** Official LNReader plugin repository (preset/recommended). */
		const val OFFICIAL_REPO_URL =
			"https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"

		/** Recommended repositories — always shown even if user hasn't added them. */
		val PRESET_REPOS = listOf(
			RepoEntry(
				url = OFFICIAL_REPO_URL,
				label = "LNReader Official",
			),
		)
	}

	/**
	 * Fetch and parse the plugin index from a repository URL.
	 */
	suspend fun fetchPluginIndex(repoUrl: String): Result<List<LNReaderPluginInfo>> =
		withContext(Dispatchers.IO) {
			try {
				val request = Request.Builder().url(repoUrl).build()
				httpClient.newCall(request).execute().use { response ->
					if (!response.isSuccessful) {
						return@withContext Result.failure(
							RuntimeException("HTTP ${response.code}: ${response.message}")
						)
					}
					val body = response.body?.string()
						?: return@withContext Result.failure(RuntimeException("Empty response"))

					val plugins = parsePluginIndex(body)
					LnLog.d(TAG, "Fetched ${plugins.size} plugins from $repoUrl")
					Result.success(plugins)
				}
			} catch (e: Exception) {
				LnLog.e(TAG, "Failed to fetch plugin index from $repoUrl", e)
				Result.failure(e)
			}
		}

	/**
	 * Download a plugin's JS bundle from its URL and install it as a novel source.
	 */
	suspend fun installPlugin(plugin: LNReaderPluginInfo): Result<Int> =
		withContext(Dispatchers.IO) {
			try {
				val request = Request.Builder().url(plugin.url).build()
				httpClient.newCall(request).execute().use { response ->
					if (!response.isSuccessful) {
						return@withContext Result.failure(
							RuntimeException("HTTP ${response.code}: ${response.message}")
						)
					}
					val jsContent = response.body?.string()
						?: return@withContext Result.failure(RuntimeException("Empty JS bundle"))

					LnLog.d(TAG, "Downloaded plugin ${plugin.id} (${jsContent.length} bytes)")
					installer.install(
						jsContent = jsContent,
						metadataOverride = LNReaderPluginMetadata(
							id = plugin.id,
							name = plugin.name,
							site = plugin.site,
							version = plugin.version,
							lang = plugin.lang,
							icon = plugin.iconUrl,
						),
					)
				}
			} catch (e: Exception) {
				LnLog.e(TAG, "Failed to install plugin ${plugin.id}", e)
				Result.failure(e)
			}
		}

	private fun parsePluginIndex(json: String): List<LNReaderPluginInfo> {
		val array = JSONArray(json)
		val plugins = mutableListOf<LNReaderPluginInfo>()

		for (i in 0 until array.length()) {
			val obj = array.optJSONObject(i) ?: continue
			plugins.add(
				LNReaderPluginInfo(
					id = obj.optString("id", ""),
					name = obj.optString("name", ""),
					site = obj.optString("site", ""),
					lang = obj.optString("lang", ""),
					version = obj.optString("version", ""),
					url = obj.optString("url", ""),
					iconUrl = obj.optString("iconUrl", ""),
				)
			)
		}

		return plugins.filter { it.id.isNotBlank() && it.url.isNotBlank() }
	}
}

fun interface LNReaderPluginInstaller {

	/**
	 * Store the downloaded JS bundle as a novel source; return the new row id.
	 */
	fun install(jsContent: String, metadataOverride: LNReaderPluginMetadata): Result<Int>
}

/**
 * A single plugin entry from the repository index.
 */
data class LNReaderPluginInfo(
	val id: String,
	val name: String,
	val site: String,
	val lang: String,
	val version: String,
	val url: String,
	val iconUrl: String,
)

/**
 * A preset repository entry.
 */
data class RepoEntry(
	val url: String,
	val label: String,
)
