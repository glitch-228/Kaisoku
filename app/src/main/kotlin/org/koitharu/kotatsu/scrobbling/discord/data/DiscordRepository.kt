package org.koitharu.kotatsu.scrobbling.discord.data

import android.content.Context
import android.util.Base64
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseRaw
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

private const val SCHEME_MP = "mp:"
private const val DISCORD_OAUTH_REDIRECT_URI = "kotatsu://discord-auth"

@Reusable
class DiscordRepository @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
	@BaseHttpClient private val httpClient: OkHttpClient,
) {

	private val appId = context.getString(R.string.discord_app_id)

	suspend fun getMediaProxyUrl(url: String): String? {
		if (isMediaProxyUrl(url)) {
			return url
		}
		val token = checkNotNull(settings.discordToken) {
			"Discord token is missing"
		}
		val request = Request.Builder()
			.url("https://discord.com/api/v10/applications/${appId}/external-assets")
			.header(CommonHeaders.AUTHORIZATION, token)
			.post("{\"urls\":[\"${url}\"]}".toRequestBody("application/json".toMediaType()))
			.build()
		val body = httpClient.newCall(request).await().parseRaw()
		when (val json = Json.parseToJsonElement(body)) {
			is JsonObject -> throw RuntimeException(json.jsonObject["message"]?.jsonPrimitive?.content)
			is JsonArray -> {
				val externalAssetPath = json.firstOrNull()
					?.jsonObject
					?.get("external_asset_path")
					?.toString()
					?.replace("\"", "")
				return externalAssetPath?.let { SCHEME_MP + it }
			}
			else -> throw RuntimeException("Unexpected response: $json")
		}
	}

	fun isMediaProxyUrl(url: String) = url.startsWith(SCHEME_MP)

	suspend fun checkToken(token: String) {
		val request = Request.Builder()
			.url("https://discord.com/api/v10/users/@me")
			.header(CommonHeaders.AUTHORIZATION, token)
			.get()
			.build()
		httpClient.newCall(request).await().ensureSuccess().closeQuietly()
	}

	// --- OAuth (sdk.social_layer_presence) path; used only when isDiscordRpcOauth is on ---

	/**
	 * OAuth presence covers can't go through Discord's application external-assets endpoint
	 * (that needs a user token). Upload the cached cover to a short-lived host and let the
	 * asset registrar resolve it instead.
	 */
	suspend fun getMediaProxyUrl(file: File): String? {
		val requestBody = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("reqtype", "fileupload")
			.addFormDataPart("time", "24h")
			.addFormDataPart(
				"fileToUpload", file.name,
				file.asRequestBody("image/*".toMediaTypeOrNull()),
			).build()
		val request = Request.Builder()
			.url("https://litterbox.catbox.moe/resources/internals/api.php")
			.post(requestBody)
			.build()
		var response: okhttp3.Response? = null
		return try {
			response = httpClient.newCall(request).await()
			if (response.isSuccessful) response.parseRaw().trim() else null
		} catch (_: Exception) {
			null
		} finally {
			response?.closeQuietly()
		}
	}

	val oauthUrl: String
		get() {
			val verifier = UUID.randomUUID().toString() + UUID.randomUUID().toString()
			settings.discordCodeVerifier = verifier
			val challenge = generateCodeChallenge(verifier)
			val state = UUID.randomUUID().toString()
			return "discord://action/oauth2/authorize?client_id=$appId" +
				"&scope=openid%20sdk.social_layer_presence" +
				"&response_type=code" +
				"&state=$state" +
				"&code_challenge=$challenge" +
				"&code_challenge_method=S256" +
				"&redirect_uri=$DISCORD_OAUTH_REDIRECT_URI"
		}

	val oauthFallbackUrl: String
		get() = "https://discord.com/oauth2/authorize?client_id=$appId" +
			"&scope=openid%20sdk.social_layer_presence" +
			"&response_type=code&redirect_uri=$DISCORD_OAUTH_REDIRECT_URI" +
			"&code_challenge=${generateCodeChallenge(settings.discordCodeVerifier.orEmpty())}" +
			"&code_challenge_method=S256"

	suspend fun authorize(code: String) {
		val verifier = settings.discordCodeVerifier ?: throw IllegalStateException("Code verifier is missing")
		val request = Request.Builder()
			.url("https://discord.com/api/v10/oauth2/token")
			.post(
				FormBody.Builder()
					.add("client_id", appId)
					.add("grant_type", "authorization_code")
					.add("code", code)
					.add("redirect_uri", DISCORD_OAUTH_REDIRECT_URI)
					.add("code_verifier", verifier)
					.build(),
			).build()
		val response = httpClient.newCall(request).await().ensureSuccess()
		val raw = try {
			response.parseRaw()
		} finally {
			response.closeQuietly()
		}
		val json = Json.parseToJsonElement(raw).jsonObject
		val accessToken = json["access_token"]?.jsonPrimitive?.content
		val tokenType = json["token_type"]?.jsonPrimitive?.content ?: "Bearer"
		settings.discordToken = "$tokenType $accessToken"
		settings.discordRefreshToken = json["refresh_token"]?.jsonPrimitive?.content
		settings.discordCodeVerifier = null
	}

	suspend fun refreshToken() {
		val refreshToken = settings.discordRefreshToken ?: throw IllegalStateException("Refresh token is missing")
		val request = Request.Builder()
			.url("https://discord.com/api/v10/oauth2/token")
			.post(
				FormBody.Builder()
					.add("client_id", appId)
					.add("grant_type", "refresh_token")
					.add("refresh_token", refreshToken)
					.build(),
			).build()
		val response = httpClient.newCall(request).await().ensureSuccess()
		val raw = try {
			response.parseRaw()
		} finally {
			response.closeQuietly()
		}
		val json = Json.parseToJsonElement(raw).jsonObject
		val accessToken = json["access_token"]?.jsonPrimitive?.content
		val tokenType = json["token_type"]?.jsonPrimitive?.content ?: "Bearer"
		settings.discordToken = "$tokenType $accessToken"
		settings.discordRefreshToken = json["refresh_token"]?.jsonPrimitive?.content
	}

	private fun generateCodeChallenge(verifier: String): String {
		val bytes = verifier.toByteArray(Charsets.US_ASCII)
		val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
		return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
	}
}
