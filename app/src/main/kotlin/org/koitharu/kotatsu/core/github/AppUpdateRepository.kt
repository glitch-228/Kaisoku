package org.koitharu.kotatsu.core.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.os.AppValidator
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.asArrayList
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"

@Singleton
class AppUpdateRepository @Inject constructor(
	private val appValidator: AppValidator,
	private val settings: AppSettings,
	@BaseHttpClient private val okHttp: OkHttpClient,
	@ApplicationContext context: Context,
) {

	private val availableUpdate = MutableStateFlow<AppVersion?>(null)
	private val releasesUrl = buildString {
		append("https://api.github.com/repos/")
		append(context.getString(R.string.github_updates_repo))
		append("/releases?page=1&per_page=10")
	}

	val isUpdateAvailable: Boolean
		get() = availableUpdate.value != null

	fun observeAvailableUpdate() = availableUpdate.asStateFlow()

	suspend fun getAvailableVersions(): List<AppVersion> {
		android.util.Log.d("UPDATE_DEBUG", "=== Getting available versions from: $releasesUrl ===")
		val request = Request.Builder()
			.get()
			.url(releasesUrl)
		val jsonArray = okHttp.newCall(request.build()).await().parseJsonArray()
		android.util.Log.d("UPDATE_DEBUG", "GitHub API returned ${jsonArray.length()} releases")

		return jsonArray.mapJSONNotNull { json ->
			val releaseName = json.getString("name")
			val releaseTag = json.getString("tag_name")
			android.util.Log.d("UPDATE_DEBUG", "Processing release: '$releaseName' (tag: '$releaseTag')")

			val assets = json.optJSONArray("assets")
			android.util.Log.d("UPDATE_DEBUG", "  Assets found: ${assets?.length() ?: 0}")

			if (assets != null) {
				for (i in 0 until assets.length()) {
					val assetObj = assets.getJSONObject(i)
					val assetName = assetObj.optString("name", "unknown")
					val contentType = assetObj.optString("content_type", "unknown")
					android.util.Log.d("UPDATE_DEBUG", "    Asset $i: '$assetName' (content_type: '$contentType')")
				}
			}

			val asset = assets?.find { jo ->
				val contentType = jo.optString("content_type")
				val matches = contentType == CONTENT_TYPE_APK
				android.util.Log.d("UPDATE_DEBUG", "  Checking asset content_type: '$contentType' == '$CONTENT_TYPE_APK' -> $matches")
				matches
			}

			if (asset == null) {
				android.util.Log.d("UPDATE_DEBUG", "  No valid APK asset found for release '$releaseName'")
				return@mapJSONNotNull null
			}

			val versionName = releaseTag.removePrefix("v")
			android.util.Log.d("UPDATE_DEBUG", "  Creating AppVersion: name='$versionName' (from tag='$releaseTag'), versionId=${VersionId(versionName)}")

			AppVersion(
				id = json.getLong("id"),
				url = json.getString("html_url"),
				name = versionName,
				apkSize = asset.getLong("size"),
				apkUrl = asset.getString("browser_download_url"),
				description = json.getString("body"),
			)
		}
	}

	suspend fun fetchUpdate(): AppVersion? = withContext(Dispatchers.IO) {
		android.util.Log.d("UPDATE_DEBUG", "=== Starting fetchUpdate ===")

		if (!isUpdateSupported()) {
			android.util.Log.d("UPDATE_DEBUG", "Update not supported, returning null")
			return@withContext null
		}
		android.util.Log.d("UPDATE_DEBUG", "Update is supported, proceeding...")

		runCatchingCancellable {
			val currentVersion = VersionId(BuildConfig.VERSION_NAME)
			android.util.Log.d("UPDATE_DEBUG", "Current version: ${BuildConfig.VERSION_NAME} -> $currentVersion")

			val available = getAvailableVersions().asArrayList()
			android.util.Log.d("UPDATE_DEBUG", "Found ${available.size} available versions:")
			available.forEach { version ->
				android.util.Log.d("UPDATE_DEBUG", "  - ${version.name} -> ${version.versionId} (stable: ${version.versionId.isStable})")
			}

			available.sortBy { it.versionId }
			android.util.Log.d("UPDATE_DEBUG", "After sorting by version:")
			available.forEach { version ->
				android.util.Log.d("UPDATE_DEBUG", "  - ${version.name} -> ${version.versionId}")
			}

			if (currentVersion.isStable && !settings.isUnstableUpdatesAllowed) {
				val beforeFiltering = available.size
				available.retainAll { it.versionId.isStable }
				android.util.Log.d("UPDATE_DEBUG", "Filtered unstable versions: $beforeFiltering -> ${available.size}")
			}

			val maxVersion = available.maxByOrNull { it.versionId }
			android.util.Log.d("UPDATE_DEBUG", "Max available version: ${maxVersion?.name} -> ${maxVersion?.versionId}")

			val result = maxVersion?.takeIf { it.versionId > currentVersion }
			android.util.Log.d("UPDATE_DEBUG", "Update result: ${result?.name} (${result?.versionId} > $currentVersion = ${result?.versionId?.let { it > currentVersion }})")

			result
		}.onFailure {
			android.util.Log.e("UPDATE_DEBUG", "Error during update check", it)
			it.printStackTraceDebug()
		}.onSuccess {
			android.util.Log.d("UPDATE_DEBUG", "Setting availableUpdate to: ${it?.name}")
			availableUpdate.value = it
		}.getOrNull()
	}

	suspend fun isUpdateSupported(): Boolean {
		return appValidator.isOriginalApp.getOrNull() == true
	}

	private inline fun JSONArray.find(predicate: (JSONObject) -> Boolean): JSONObject? {
		val size = length()
		for (i in 0 until size) {
			val jo = getJSONObject(i)
			if (predicate(jo)) {
				return jo
			}
		}
		return null
	}
}
