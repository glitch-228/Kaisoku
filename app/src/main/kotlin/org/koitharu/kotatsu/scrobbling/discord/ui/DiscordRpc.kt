package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Context
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import com.my.kizzyrpc.KizzyRPC
import com.my.kizzyrpc.entities.presence.Activity
import com.my.kizzyrpc.entities.presence.Assets
import com.my.kizzyrpc.entities.presence.Metadata
import com.my.kizzyrpc.entities.presence.Presence
import com.my.kizzyrpc.entities.presence.Timestamps
import com.my.kizzyrpc.websocket.DiscordWebSocket
import dagger.Lazy
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import java.util.Collections
import javax.inject.Inject

private const val STATUS_ONLINE = AppSettings.DISCORD_STATUS_ONLINE
private const val STATUS_IDLE = AppSettings.DISCORD_STATUS_IDLE
private const val STATUS_INVISIBLE = AppSettings.DISCORD_STATUS_INVISIBLE
private const val BUTTON_TEXT_LIMIT = 32
private const val DEBOUNCE_TIMEOUT = 16_000L // 16 sec
private const val WSRV_PREFIX = "https://wsrv.nl/?url="

@ViewModelScoped
class DiscordRpc @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val repository: DiscordRepository,
	private val oauthRpc: Lazy<DiscordOauthRpc>,
	lifecycle: ViewModelLifecycle,
) : RetainedLifecycle.OnClearedListener {

	private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
	private val appId = context.getString(R.string.discord_app_id)
	private val appName = context.getString(R.string.app_name)
	private val appIcon = context.getString(R.string.app_icon_url)
	private val mpCache = Collections.synchronizedMap(ArrayMap<String, String>())
	private var lastUpdate = 0L

	private var rpc: KizzyRPC? = null

	private var rpcUpdateJob: Job? = null

	@Volatile
	private var oauthConstructed = false

	@Volatile
	private var lastActivity: Activity? = null

	@Volatile
	private var isIdle = false

	init {
		lifecycle.addOnClearedListener(this)
		// Invisible is a per-session status, so it has to be re-sent over our own gateway
		// connection as soon as it is toggled. The activity (and its start timestamp) stays in
		// memory, so turning invisible off brings the activity back with the original elapsed time.
		settings.observe(AppSettings.KEY_DISCORD_RPC_STATUS)
			.drop(1)
			.onEach { refreshPresence() }
			.launchIn(coroutineScope)
	}

	private fun refreshPresence() {
		if (settings.isDiscordRpcOauth) {
			if (oauthConstructed) {
				oauthRpc.get().refreshPresence()
			}
			return
		}
		val activity = lastActivity ?: return
		rpc?.updateRpcAsync(activity, idle = isIdle)
	}

	override fun onCleared() {
		try {
			closeKizzy()
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
		if (oauthConstructed) {
			runCatching { oauthRpc.get().close() }.onFailure { it.printStackTraceDebug() }
		}
	}

	fun clearRpc(): Unit {
		val oauth = oauth()
		dispatch {
			if (oauth != null) {
				oauth.clearRpc()
			} else {
				closeKizzy()
			}
		}
	}

	fun setIdle(): Unit {
		val oauth = oauth()
		dispatch {
			if (oauth != null) {
				oauth.setIdle()
			} else {
				lastActivity?.let { activity ->
					getRpc()?.updateRpcAsync(activity, idle = true)
				}
			}
		}
	}

	private fun oauth(): DiscordOauthRpc? {
		if (!settings.isDiscordRpcOauth) {
			return null
		}
		val oauth = oauthRpc.get()
		oauthConstructed = true
		return oauth
	}

	private fun closeKizzy() {
		synchronized(this) {
			rpc?.closeRPC()
			rpc = null
			lastUpdate = 0L
		}
	}

	private fun dispatch(block: suspend () -> Unit): Unit {
		coroutineScope.launch {
			try {
				block()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				e.printStackTraceDebug()
			}
		}
	}

	@AnyThread
	fun updateRpc(manga: Manga, state: ReaderUiState): Unit {
		val oauth = oauth()
		dispatch {
			if (oauth != null) {
				oauth.updateRpc(manga, state)
				return@dispatch
			}
			val client = getRpc() ?: return@dispatch
			if (settings.isDiscordRpcSkipNsfw && manga.isNsfw()) {
				closeKizzy()
				return@dispatch
			}
			client.run {
				// Prefer the high-res cover when the source ships one — small thumbnails get
				// rejected by Discord's media proxy and show as the placeholder card on the user
				// profile. Trim blanks so an empty string doesn't shadow a real fallback.
				val coverUrl = manga.largeCoverUrl?.takeUnless { it.isBlank() }
					?: manga.coverUrl?.takeUnless { it.isBlank() }
				val buttons = buildDiscordRpcButtons(
					communityUrl = context.getString(R.string.url_discord),
					communityLabel = context.getString(R.string.telegram_group),
					buttonTextLimit = BUTTON_TEXT_LIMIT,
				)
				updateRpcAsync(
					activity = Activity(
						applicationId = appId,
						name = appName,
						details = manga.title,
						state = state.getChapterTitle(context.resources),
						type = 3,
						timestamps = Timestamps(
							start = lastActivity?.timestamps?.start ?: System.currentTimeMillis(),
						),
						assets = Assets(
							largeImage = coverUrl,
							largeText = context.getString(R.string.reading_s, manga.title),
							smallText = context.getString(R.string.discord_rpc_description),
							smallImage = appIcon,
						),
						buttons = buttons?.labels,
						metadata = buttons?.let { Metadata(it.urls) },
					),
					idle = false,
				)
			}
		}
	}

	private fun KizzyRPC.updateRpcAsync(activity: Activity, idle: Boolean) {
		isIdle = idle
		val prevJob = rpcUpdateJob
		rpcUpdateJob = coroutineScope.launch {
			prevJob?.cancelAndJoin()
			val debounceTime = lastUpdate + DEBOUNCE_TIMEOUT - SystemClock.elapsedRealtime()
			if (debounceTime > 0) {
				delay(debounceTime)
			}
			val hideButtons = activity.buttons?.any { it != null && it.utf8Size() > BUTTON_TEXT_LIMIT } ?: false
			val mappedActivity = activity.copy(
				assets = activity.assets?.let {
					it.copy(
						// Route source covers through wsrv.nl before handing them to Discord:
						// most parser hosts gate the cover behind a Referer header and Discord's
						// media proxy fetches anonymously, so the upload silently fails and the
						// user profile renders the "?" placeholder card. wsrv.nl provides a
						// stable, headerless URL Discord can re-host without trouble.
						largeImage = it.largeImage?.toWsrvProxy()?.toMediaProxyUrl(),
						smallImage = it.smallImage?.toMediaProxyUrl(),
					)
				},
				buttons = activity.buttons.takeUnless { hideButtons },
				metadata = activity.metadata.takeUnless { hideButtons },
			)
			lastActivity = mappedActivity
			val since = activity.timestamps?.start ?: System.currentTimeMillis()
			val isInvisible = settings.isDiscordRpcInvisible
			// While invisible, withhold the activity but keep it in lastActivity with its start
			// timestamp, so it comes back with the original elapsed time.
			if (!isInvisible || !sendInvisible(since)) {
				updateRPC(
					activity = mappedActivity,
					status = resolveStatus(idle),
					since = since,
				)
				if (isInvisible) {
					// the socket was just opened by updateRPC, now drop the activity
					sendInvisible(since)
				}
			}
			lastUpdate = SystemClock.elapsedRealtime()
		}
	}

	/** The status picked in settings wins; "online" still turns into idle when the reader is left. */
	private fun resolveStatus(idle: Boolean): String = when (val status = settings.discordRpcStatus) {
		STATUS_ONLINE -> if (idle) STATUS_IDLE else STATUS_ONLINE
		else -> status
	}

	/**
	 * KizzyRPC.updateRPC always attaches an activity, so send the invisible presence with no
	 * activities straight over its gateway socket. Returns false when that is not possible
	 * (socket not connected yet, or the field could not be found).
	 */
	private suspend fun KizzyRPC.sendInvisible(since: Long): Boolean {
		if (!isRpcRunning()) {
			return false
		}
		val socket = runCatching {
			KizzyRPC::class.java.declaredFields
				.first { DiscordWebSocket::class.java.isAssignableFrom(it.type) }
				.apply { isAccessible = true }
				.get(this) as DiscordWebSocket
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull() ?: return false
		socket.sendActivity(
			Presence(
				activities = emptyList(),
				afk = true,
				since = since,
				status = STATUS_INVISIBLE,
			),
		)
		return true
	}

	suspend fun String.toMediaProxyUrl(): String? {
		if (repository.isMediaProxyUrl(this)) {
			return this
		}
		mpCache[this]?.let {
			return it
		}
		return runCatchingCancellable {
			repository.getMediaProxyUrl(this)
		}.onSuccess { url ->
			mpCache[this] = url
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	/**
	 * Wrap an http(s) image URL through wsrv.nl so the eventual fetcher (Discord's media proxy)
	 * receives a stable headerless URL. Pass-through for already-proxied URLs and for anything
	 * that isn't a network image (e.g. existing Discord `mp:` URLs, app icons on local schemes).
	 */
	private fun String.toWsrvProxy(): String {
		if (startsWith(WSRV_PREFIX, ignoreCase = true)) return this
		if (!startsWith("http://", ignoreCase = true) && !startsWith("https://", ignoreCase = true)) return this
		return WSRV_PREFIX + java.net.URLEncoder.encode(this, Charsets.UTF_8.name()) + "&we"
	}

	private fun getRpc(): KizzyRPC? {
		rpc?.let {
			return it
		}
		return synchronized(this) {
			rpc?.let {
				return@synchronized it
			}
			if (settings.isDiscordRpcEnabled) {
				settings.discordToken?.let { KizzyRPC(it) }
			} else {
				null
			}.also {
				rpc = it
			}
		}
	}
}
