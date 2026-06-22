package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.scrobbling.discord.data.DiscordRepository
import javax.inject.Inject

/**
 * Discord OAuth2 (PKCE) login: launches the authorize page and catches the
 * `kaisoku|kotatsu://discord-auth?code=…` redirect to exchange the code for an access token.
 * Used only for the OAuth presence path; the user-token flow stays in [DiscordAuthActivity].
 */
@AndroidEntryPoint
class DiscordOauthActivity : ComponentActivity() {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var repository: DiscordRepository

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		handleIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent) {
		val data = intent.data
		if (data != null && (data.scheme == "kaisoku" || data.scheme == "kotatsu") && data.host == "discord-auth") {
			val code = data.getQueryParameter("code")
			if (code != null) {
				lifecycleScope.launch {
					try {
						repository.authorize(code)
						setResult(RESULT_OK)
						finish()
					} catch (e: Exception) {
						e.printStackTraceDebug()
						startAuth()
					}
				}
			} else {
				finish()
			}
		} else {
			startAuth()
		}
	}

	private fun startAuth() {
		val intent = Intent(Intent.ACTION_VIEW, repository.oauthUrl.toUri()).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		try {
			startActivity(intent)
		} catch (_: Exception) {
			intent.data = repository.oauthFallbackUrl.toUri()
			try {
				startActivity(intent)
			} catch (e: Exception) {
				e.printStackTraceDebug()
				finish()
			}
		}
	}
}
