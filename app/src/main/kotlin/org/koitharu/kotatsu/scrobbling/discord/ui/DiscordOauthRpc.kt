package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Context
import android.os.SystemClock
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
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import okio.utf8Size
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

private const val STATUS_ONLINE = "online"
private const val STATUS_IDLE = "idle"
private const val BUTTON_TEXT_LIMIT = 32
private const val DEBOUNCE_TIMEOUT = 3_000L // 3 sec

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
) : RetainedLifecycle.OnClearedListener {

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
	private val apiInstance = API()

	@Volatile
	private var lastPresence: RichPresence? = null

	init {
		lifecycle.addOnClearedListener(this)
	}

	override fun onCleared() {
		clearRpc()
		apiInstance.close()
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

		val btn1Name = context.getString(R.string.read_on_s, appName)
		val btn2Name = context.getString(R.string.read_on_s, manga.source.getTitle(context))
		if (btn1Name.utf8Size() <= BUTTON_TEXT_LIMIT && btn2Name.utf8Size() <= BUTTON_TEXT_LIMIT) {
			presence.setButtons(
				mapOf("name" to btn1Name, "url" to manga.appUrl.toString()),
				mapOf("name" to btn2Name, "url" to manga.publicUrl),
			)
		}
		updateRpcAsync(presence, idle = false, isNsfw = manga.isNsfw())
	}

	private fun updateRpcAsync(presence: RichPresence, idle: Boolean, isNsfw: Boolean) {
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
				val data = mutableMapOf<String, Any?>(
					"activities" to listOf(presence.toJSON()),
					"status" to if (idle) STATUS_IDLE else STATUS_ONLINE,
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
		rpc ?: settings.discordToken?.takeIf { settings.isDiscordRpcEnabled }?.let { token ->
			GatewayClient().apply {
				onReady = { lastPresence?.let { updateRpcAsync(it, idle = false, isNsfw = false) } }
				onResumed = { lastPresence?.let { updateRpcAsync(it, idle = false, isNsfw = false) } }
				coroutineScope.launch {
					try {
						var currentToken = token
						runCatchingCancellable { repository.checkToken(currentToken) }.onFailure {
							repository.refreshToken()
							currentToken = settings.discordToken ?: token
						}
						connect(GatewayConnectOptions(token = currentToken))
					} catch (e: Exception) {
						e.printStackTraceDebug().also { clearRpc() }
					}
				}
			}
		}.also { rpc = it }
	}

	private fun getRegistrar(): DiscordAssetRegistrar? {
		val currentToken = settings.discordToken ?: return null
		if (assetRegistrar == null || registrarToken != currentToken) {
			registrarToken = currentToken
			assetRegistrar = DiscordAssetRegistrar(apiInstance, appId, currentToken)
		}
		return assetRegistrar
	}
}
