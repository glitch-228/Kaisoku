package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.AnyThread
import com.discord.oauth2rpc.API
import com.discord.oauth2rpc.DiscordAssetRegistrar
import com.discord.oauth2rpc.GatewayClient
import com.discord.oauth2rpc.GatewayConnectOptions
import com.discord.oauth2rpc.structures.RichPresence
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.appUrl
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.scrobbling.discord.data.DiscordRepository
import java.io.File
import java.util.Collections
import javax.inject.Inject

private const val STATUS_ONLINE = AppSettings.DISCORD_STATUS_ONLINE
private const val STATUS_IDLE = AppSettings.DISCORD_STATUS_IDLE
private const val BUTTON_TEXT_LIMIT = 32
private const val DEBOUNCE_TIMEOUT = 3_000L // 3 sec
private const val PRESENCE_SCOPE = "sdk.social_layer_presence"
private const val TAG = "DiscordOauth"

/**
 * Discord rich presence over OAuth2 (sdk.social_layer_presence), as an alternative to the
 * user-token [DiscordRpc]. Selected when [AppSettings.isDiscordRpcOauth] is on; the token in
 * [AppSettings.discordToken] is then a "Bearer …" access token managed by [DiscordRepository].
 */
@ViewModelScoped
class DiscordOauthRpc @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val repository: DiscordRepository,
	private val imageLoader: ImageLoader,
	lifecycle: ViewModelLifecycle,
) {

	private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
	private val appId = context.getString(R.string.discord_app_id)
	private val appName = context.getString(R.string.app_name)
	private val appIcon = context.getString(R.string.app_icon_url)
	private val mpCache = Collections.synchronizedMap(HashMap<String, String>())
	private var lastUpdate = 0L
	private var rpc: GatewayClient? = null
	private var rpcUpdateJob: Job? = null
	private var assetRegistrar: DiscordAssetRegistrar? = null
	private var registrarToken: String? = null
	private val apiInstance: Lazy<API> = lazy { API() }

	@Volatile
	private var lastPresence: RichPresence? = null

	@Volatile
	private var isIdle = false

	fun close() {
		clearRpc()
		if (apiInstance.isInitialized()) {
			apiInstance.value.close()
		}
	}

	fun clearRpc() = synchronized(this) {
		rpc?.disconnect()
		rpc = null
		lastUpdate = 0L
	}

	fun setIdle() {
		lastPresence?.let { presence ->
			updateRpcAsync(presence, idle = true, isNsfw = false)
		}
	}

	/** Re-send the last presence, e.g. after invisible mode was toggled. */
	fun refreshPresence() {
		if (rpc == null) return
		lastPresence?.let { presence ->
			updateRpcAsync(presence, idle = isIdle, isNsfw = false)
		}
	}

	@AnyThread
	fun updateRpc(manga: Manga, state: ReaderUiState) {
		if (settings.isDiscordRpcSkipNsfw && manga.isNsfw()) {
			clearRpc()
			return
		}
		val coverUrl = manga.largeCoverUrl?.takeUnless { it.isBlank() }
			?: manga.coverUrl?.takeUnless { it.isBlank() }
		val presence = RichPresence()
			.setApplicationId(appId)
			.setName(appName)
			.setDetails(manga.title)
			.setState(state.getChapterTitle(context.resources))
			.setType(3) // WATCHING
			.setStartTimestamp(lastPresence?.timestamps?.get("start") ?: System.currentTimeMillis())
			.setAssetsLargeImage(coverUrl)
			.setAssetsLargeText(context.getString(R.string.reading_s, manga.title))
			.setAssetsSmallImage(appIcon)
			.setAssetsSmallText(context.getString(R.string.discord_rpc_description))

		val buttons = buildDiscordRpcButtons(
			communityUrl = context.getString(R.string.url_discord),
			communityLabel = context.getString(R.string.telegram_group),
			buttonTextLimit = BUTTON_TEXT_LIMIT,
		)
		if (buttons != null) {
			presence.setButtons(
				mapOf("name" to buttons.labels[0], "url" to buttons.urls[0]),
			)
		}
		updateRpcAsync(presence, idle = false, isNsfw = manga.isNsfw())
	}

	private fun updateRpcAsync(presence: RichPresence, idle: Boolean, isNsfw: Boolean) {
		isIdle = idle
		val prevJob = rpcUpdateJob
		rpcUpdateJob = coroutineScope.launch {
			prevJob?.cancelAndJoin()
			val debounceTime = lastUpdate + DEBOUNCE_TIMEOUT - SystemClock.elapsedRealtime()
			if (debounceTime > 0) {
				delay(debounceTime)
			}
			launch { getRpc() }
			presence.setAssetsLargeImage(presence.assets["largeImage"]?.toMediaProxyUrl(isNsfw))
			presence.setAssetsSmallImage(presence.assets["smallImage"]?.toMediaProxyUrl(false))
			lastPresence = presence
			getRpc()?.let { client ->
				// While invisible, the activity is withheld but kept in lastPresence with its
				// start timestamp, so it comes back with the original elapsed time.
				val invisible = settings.isDiscordRpcInvisible
				val data = mutableMapOf<String, Any?>(
					"activities" to if (invisible) emptyList<Any?>() else listOf(presence.toJSON()),
					"status" to when (val status = settings.discordRpcStatus) {
						STATUS_ONLINE -> if (idle) STATUS_IDLE else STATUS_ONLINE
						else -> status
					},
					"since" to (presence.timestamps?.get("start") ?: System.currentTimeMillis()),
					"afk" to idle,
				)
				client.send(3, data)
				lastUpdate = SystemClock.elapsedRealtime()
			}
		}
	}

	private suspend fun String.toMediaProxyUrl(isNsfw: Boolean): String? {
		if (repository.isMediaProxyUrl(this)) return this
		return mpCache[this] ?: runCatchingCancellable {
			val file = getCacheFile(this)
			val upload = file?.let { repository.getMediaProxyUrl(it) }
			val contentRating = if (isNsfw) 1 else 0
			if (upload != null) {
				getRegistrar()?.resolve(upload, contentRating)
			} else {
				getRegistrar()?.resolve(this, contentRating)
			}
		}.onSuccess { url -> url?.let { mpCache[this] = it } }
			.onFailure { it.printStackTraceDebug() }
			.getOrNull()
	}

	private suspend fun getCacheFile(url: String): File? {
		var snapshot = imageLoader.diskCache?.openSnapshot(url)
		if (snapshot == null) {
			val request = ImageRequest.Builder(context).data(url).build()
			val result = imageLoader.execute(request)
			if (result is SuccessResult) {
				snapshot = imageLoader.diskCache?.openSnapshot(url)
			}
		}
		return snapshot?.use { File(it.data.toString()) }
	}

	private fun getRpc(): GatewayClient? = rpc ?: synchronized(this) {
		rpc ?: run {
			val token = settings.discordToken?.takeIf { settings.isDiscordRpcEnabled }
			if (token != null && !settings.discordScopes.orEmpty().contains(PRESENCE_SCOPE)) {
				Log.w(TAG, "token lacks presence scope; not connecting")
				return@synchronized null
			}
			token?.let { currentTokenValue ->
				GatewayClient().apply {
					onReady = {
						Log.i(TAG, "gateway ready")
						lastPresence?.let { updateRpcAsync(it, idle = false, isNsfw = false) }
					}
					onResumed = {
						Log.i(TAG, "gateway resumed")
						lastPresence?.let { updateRpcAsync(it, idle = false, isNsfw = false) }
					}
					coroutineScope.launch {
						try {
							var currentToken = currentTokenValue
							runCatchingCancellable { repository.checkToken(currentToken) }.onFailure {
								repository.refreshToken()
								currentToken = settings.discordToken ?: currentTokenValue
							}
							Log.i(TAG, "connecting to gateway")
							connect(GatewayConnectOptions(token = currentToken))
						} catch (e: Exception) {
							Log.w(TAG, "gateway connect failed", e)
							e.printStackTraceDebug().also { clearRpc() }
						}
					}
				}
			}.also { rpc = it }
		}
	}

	private fun getRegistrar(): DiscordAssetRegistrar? {
		val currentToken = settings.discordToken ?: return null
		if (assetRegistrar == null || registrarToken != currentToken) {
			registrarToken = currentToken
			assetRegistrar = DiscordAssetRegistrar(apiInstance.value, appId, currentToken)
		}
		return assetRegistrar
	}
}
