package org.koitharu.kotatsu.reader.translate

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.util.await
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

@Singleton
class MultimodalTranslator @Inject constructor(
	@MangaHttpClient private val okHttpClient: OkHttpClient,
	private val settings: AppSettings,
) : PageTranslator {

	private val rateMutex = Mutex()
	private var lastCallAt = 0L

	// Bound each request so a stalled provider can't hang the page (and spin the progress) forever.
	private val translateClient by lazy {
		okHttpClient.newBuilder()
			.callTimeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
			.build()
	}

	/**
	 * Send the page bitmap to the configured multimodal LLM and parse the JSON response into [TranslatedBlock]s.
	 * Returned rectangles are normalised image-space (0..1 on each axis).
	 *
	 * @throws TranslateException with a categorised reason on any failure
	 */
	override suspend fun translate(
		bitmap: Bitmap,
		sourceLang: String,
		targetLang: String,
		onTotal: (Int) -> Unit,
		onTileDone: (success: Boolean) -> Unit,
	): PageTranslationResult = withContext(Dispatchers.IO) {
		val endpoint = settings.translateEndpoint.trim()
		val apiKey = settings.translateApiKey.trim()
		val model = settings.translateModel.trim().ifBlank {
			when (settings.translateProvider) {
				TranslateProvider.GEMINI -> "gemini-2.5-flash"
				TranslateProvider.OPENAI_COMPATIBLE -> "gpt-4o-mini"
				// Routed to GoogleLensTranslator, never here — present only for exhaustiveness.
				TranslateProvider.GOOGLE_LENS -> ""
			}
		}

		if (endpoint.isEmpty()) throw TranslateException.NoEndpoint()
		if (apiKey.isEmpty()) throw TranslateException.NoKey()

		val isNativeGoogleFormat = settings.translateProvider == TranslateProvider.GEMINI ||
			endpoint.contains("generateContent") ||
			endpoint.contains("googleapis.com/v1beta/models/")

		// Webtoon pages are long vertical strips; a single 1024px-box downscale would crush their
		// width to ~100px and make the text unreadable (the model then loops on garbage). Translate
		// the page in full-width horizontal tiles instead, then map each tile's coordinates back.
		val tiles = planTiles(bitmap.width, bitmap.height)
		onTotal(tiles.size)
		val all = ArrayList<TranslatedBlock>()
		var lastError: Throwable? = null
		var anySuccess = false
		for (tile in tiles) {
			var ok = false
			try {
				all += translateTile(bitmap, tile, sourceLang, targetLang, endpoint, apiKey, model, isNativeGoogleFormat)
				anySuccess = true
				ok = true
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				// Tolerate a single tile failing (rate limit, timeout, …): keep the parts already
				// done so a long page isn't lost wholesale. Only surface an error if nothing worked.
				lastError = e
			}
			onTileDone(ok)
		}
		if (!anySuccess) {
			lastError?.let { throw it }
		}
		PageTranslationResult.Blocks(sanitizeBlocks(all))
	}

	private suspend fun translateTile(
		src: Bitmap,
		tile: Tile,
		sourceLang: String,
		targetLang: String,
		endpoint: String,
		apiKey: String,
		model: String,
		isNativeGoogleFormat: Boolean,
	): List<TranslatedBlock> {
		val base64Image = runInterruptible { encodeRegion(src, tile.y0, tile.y1 - tile.y0) }
		if (base64Image.isEmpty()) throw TranslateException.Parse("Failed to encode bitmap")

		val payload = if (isNativeGoogleFormat) {
			buildGeminiPayload(base64Image, sourceLang, targetLang, model)
		} else {
			buildOpenAiPayload(base64Image, sourceLang, targetLang, model)
		}
		val finalUrl = resolveUrl(endpoint, apiKey, model, isNativeGoogleFormat)
		// org.json escapes '/' to '\/' which trips some restrictive proxies — undo that.
		val payloadStr = payload.toString().replace("\\/", "/")

		val request = Request.Builder()
			.url(finalUrl)
			.post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
			.apply {
				if (!isNativeGoogleFormat) {
					header("Authorization", "Bearer $apiKey")
				}
				applyCustomHeaders(this)
			}
			.build()

		val response = executeWithRetry(request)
		return response.use {
			val body = it.body?.string().orEmpty()
			if (!it.isSuccessful) {
				throw TranslateException.Http(it.code, body)
			}
			val tileBlocks = parseResponse(body, src.width, tile.y1 - tile.y0)
			mapTileToFull(tileBlocks, tile.y0, tile.y1, src.height)
		}
	}

	/** One request, honoring a global rate limit and backing off on 429 / 5xx (incl. Retry-After). */
	private suspend fun executeWithRetry(request: Request): Response {
		var attempt = 0
		while (true) {
			rateGate()
			val response = try {
				translateClient.newCall(request).await()
			} catch (e: IOException) {
				throw TranslateException.Network(e)
			}
			if (response.isSuccessful || attempt >= MAX_RETRIES) return response
			val retryable = response.code == 429 || response.code in 500..599
			if (!retryable) return response
			val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000L }
			response.close()
			delay((retryAfterMs ?: (BASE_BACKOFF_MS shl attempt)).coerceAtMost(MAX_BACKOFF_MS))
			attempt++
		}
	}

	/** Space out request starts so free-tier quotas aren't blown by tiling / fast page turns. */
	private suspend fun rateGate() {
		val minInterval = 60_000L / settings.translateRpm.coerceIn(1, 60)
		rateMutex.withLock {
			val wait = lastCallAt + minInterval - System.currentTimeMillis()
			if (wait > 0) delay(wait)
			lastCallAt = System.currentTimeMillis()
		}
	}

	private fun buildOpenAiPayload(base64Image: String, sourceLang: String, targetLang: String, model: String): JSONObject {
		val combinedPrompt = "$SYSTEM_PROMPT\n\n${buildUserPrompt(sourceLang, targetLang)}"
		return JSONObject().apply {
			put("model", model)
			put("temperature", 0.1)
			put("max_tokens", 4096)
			put(
				"messages",
				JSONArray().put(
					JSONObject().put("role", "user").put(
						"content",
						JSONArray()
							.put(JSONObject().put("type", "text").put("text", combinedPrompt))
							.put(
								JSONObject()
									.put("type", "image_url")
									.put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image")),
							),
					),
				),
			)
		}
	}

	private fun buildGeminiPayload(base64Image: String, sourceLang: String, targetLang: String, model: String): JSONObject {
		// model is encoded in the endpoint URL for native Gemini — model arg unused.
		return JSONObject().apply {
			put(
				"system_instruction",
				JSONObject().put("parts", JSONObject().put("text", SYSTEM_PROMPT)),
			)
			put(
				"contents",
				JSONArray().put(
					JSONObject()
						.put("role", "user")
						.put(
							"parts",
							JSONArray()
								.put(JSONObject().put("text", buildUserPrompt(sourceLang, targetLang)))
								.put(
									JSONObject().put(
										"inline_data",
										JSONObject()
											.put("mime_type", "image/jpeg")
											.put("data", base64Image),
									),
								),
						),
				),
			)
			put(
				"generationConfig",
				JSONObject()
					.put("temperature", 0)
					.put("responseMimeType", "application/json"),
			)
		}
	}

	private fun resolveUrl(endpoint: String, apiKey: String, model: String, isNativeGoogleFormat: Boolean): String {
		val trimmed = endpoint.trimEnd('/')
		if (!isNativeGoogleFormat) {
			return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
		}
		// Native Gemini: accept either the bare ".../v1beta/models/" base (model comes from the Model
		// field, like the OpenAI-compatible layout) or a full ".../models/<model>:generateContent" URL.
		val withMethod = if (endpoint.contains(":generateContent") || endpoint.contains(":streamGenerateContent")) {
			endpoint
		} else {
			"$trimmed/${model.trim()}:generateContent"
		}
		if (!withMethod.contains("key=")) {
			val sep = if (withMethod.contains("?")) "&" else "?"
			return "$withMethod${sep}key=$apiKey"
		}
		return withMethod
	}

	private fun applyCustomHeaders(builder: Request.Builder) {
		val headers = settings.translateCustomHeaders.trim()
		if (headers.isBlank() || !headers.startsWith("{")) return
		runCatching {
			val json = JSONObject(headers)
			for (key in json.keys()) {
				val value = json.optString(key)
				if (value.isNotBlank()) builder.header(key, value)
			}
		}
	}

	private fun buildUserPrompt(sourceLang: String, targetLang: String): String = buildString {
		appendLine("Please identify all the text in the image.")
		appendLine("This is a manga page. The text is in $sourceLang. Please translate it into $targetLang.")
		appendLine("Please output the information of each text block in a JSON array format. Do not use markdown blocks, output raw JSON only.")
		appendLine("Group text by speech bubble or caption: return ONE entry per bubble/caption/SFX, not one per line. If a bubble contains multiple lines, concatenate them into a single `original_text` separated by spaces, and return a single coordinates rectangle that covers the entire bubble.")
		appendLine("The JSON format MUST be an array of objects, where each object contains:")
		appendLine("- `coordinates`: an array of exactly 4 numbers [ymin, xmin, ymax, xmax], representing normalized coordinates from 0 to 1000 that tightly enclose the entire bubble/caption. If you are unsure about the coordinates, strictly output [0, 0, 0, 0] instead of leaving it empty.")
		appendLine("- `original_text`: the original text from the bubble (joined with spaces if multi-line).")
		appendLine("- `translated_text`: the $targetLang translation.")
		appendLine("- IMPORTANT: If the detected text is explicitly a pirate manga website URL, watermark, or completely meaningless background texture rather than human dialogue/story structure, set `translated_text` exactly to '$IGNORE_BLOCK_MARKER'.")
	}

	private fun parseResponse(rawBody: String, imageWidth: Int, imageHeight: Int): List<TranslatedBlock> {
		if (rawBody.isBlank()) throw TranslateException.Parse("empty body")
		val content = extractMessageContent(rawBody)
		if (content.isBlank()) throw TranslateException.Parse("no message content")
		val jsonArray = parseJsonArray(content)
			?: throw TranslateException.Parse("not a JSON array: ${content.take(120)}")

		val blocks = mutableListOf<TranslatedBlock>()
		for (i in 0 until jsonArray.length()) {
			val obj = jsonArray.optJSONObject(i) ?: continue
			val coords = obj.optJSONArray("coordinates") ?: continue
			if (coords.length() < 4) continue

			val yminNorm = coords.optDouble(0, 0.0)
			val xminNorm = coords.optDouble(1, 0.0)
			val ymaxNorm = coords.optDouble(2, 0.0)
			val xmaxNorm = coords.optDouble(3, 0.0)

			val left = ((xminNorm / 1000.0) * imageWidth).coerceIn(0.0, imageWidth.toDouble())
			val top = ((yminNorm / 1000.0) * imageHeight).coerceIn(0.0, imageHeight.toDouble())
			val right = ((xmaxNorm / 1000.0) * imageWidth).coerceIn(0.0, imageWidth.toDouble())
			val bottom = ((ymaxNorm / 1000.0) * imageHeight).coerceIn(0.0, imageHeight.toDouble())
			if (left >= right || top >= bottom) continue

			val originalText = obj.optString("original_text", "")
			val translatedText = obj.optString("translated_text", "").ifBlank {
				obj.optString("translation", "")
			}
			if (translatedText.isBlank() || translatedText.contains(IGNORE_BLOCK_MARKER)) continue

			blocks += TranslatedBlock(
				originalText = originalText,
				translatedText = translatedText,
				rect = RectF(
					(left / imageWidth).toFloat(),
					(top / imageHeight).toFloat(),
					(right / imageWidth).toFloat(),
					(bottom / imageHeight).toFloat(),
				),
			)
		}
		return blocks
	}

	private fun extractMessageContent(rawBody: String): String = runCatching {
		val json = JSONObject(rawBody)
		val choices = json.optJSONArray("choices")
		if (choices != null && choices.length() > 0) {
			val message = choices.optJSONObject(0)?.optJSONObject("message")
			if (message != null) return@runCatching message.optString("content", "")
		}
		val candidates = json.optJSONArray("candidates")
		if (candidates != null && candidates.length() > 0) {
			val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
			if (parts != null && parts.length() > 0) {
				return@runCatching parts.optJSONObject(0)?.optString("text", "").orEmpty()
			}
		}
		""
	}.getOrDefault("")

	private fun parseJsonArray(content: String): JSONArray? {
		var clean = content.replace("```json", "").replace("```", "").trim()
		// Tolerate the occasional malformed `"key":,` shape from Gemini.
		clean = clean.replace(Regex("\"\\s*:\\s*,"), "\": null,")
		return runCatching { JSONArray(clean) }.getOrNull()
	}

	private data class Tile(val y0: Int, val y1: Int)

	/** Full-width horizontal slices, sized so each stays legible (~1024px wide) after scaling. */
	private fun planTiles(width: Int, height: Int): List<Tile> {
		val scale = minOf(1f, TARGET_WIDTH.toFloat() / width)
		val scaledWidth = width * scale
		val scaledHeight = height * scale
		// Keep tiles roughly square: vision models lose vertical accuracy on extremely tall
		// images (boxes drift downward), so cap a tile's height relative to its width.
		val maxTileHeight = scaledWidth * MAX_TILE_ASPECT
		if (scaledHeight <= maxTileHeight) return listOf(Tile(0, height))
		val count = ceil(scaledHeight / maxTileHeight).toInt().coerceIn(1, MAX_TILES)
		val step = height / count
		val overlap = (step * TILE_OVERLAP).toInt()
		return (0 until count).map { i ->
			val y0 = (i * step - overlap).coerceAtLeast(0)
			val y1 = if (i == count - 1) height else ((i + 1) * step + overlap).coerceAtMost(height)
			Tile(y0, y1)
		}
	}

	/** Remap a tile's normalised rects (0..1 of the tile) into full-image normalised space. */
	private fun mapTileToFull(blocks: List<TranslatedBlock>, y0: Int, y1: Int, fullHeight: Int): List<TranslatedBlock> {
		if (y0 == 0 && y1 == fullHeight) return blocks
		val span = (y1 - y0).toFloat()
		val h = fullHeight.toFloat()
		return blocks.map { b ->
			b.copy(
				rect = RectF(
					b.rect.left,
					(y0 + b.rect.top * span) / h,
					b.rect.right,
					(y0 + b.rect.bottom * span) / h,
				),
			)
		}
	}

	/** Drop degenerate repetition (e.g. a model that loops one token over an unreadable page). */
	private fun sanitizeBlocks(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
		if (blocks.size <= 1) return blocks
		val counts = HashMap<String, Int>()
		val out = ArrayList<TranslatedBlock>(blocks.size)
		for (b in blocks) {
			val key = b.translatedText.trim().lowercase()
			if (key.isEmpty()) continue
			val n = (counts[key] ?: 0) + 1
			counts[key] = n
			if (n <= MAX_DUPLICATE) out += b
		}
		if (out.size >= 5 && out.distinctBy { it.translatedText.trim().lowercase() }.size == 1) {
			return emptyList()
		}
		return out
	}

	private fun encodeRegion(src: Bitmap, y0: Int, regionHeight: Int): String {
		val scale = minOf(1f, TARGET_WIDTH.toFloat() / src.width)
		val targetW = (src.width * scale).toInt().coerceAtLeast(1)
		val targetH = (regionHeight * scale).toInt().coerceAtLeast(1)
		val region = if (y0 == 0 && regionHeight == src.height) {
			src
		} else {
			Bitmap.createBitmap(src, 0, y0, src.width, regionHeight)
		}
		val scaled = if (region.width != targetW || region.height != targetH) {
			Bitmap.createScaledBitmap(region, targetW, targetH, true)
		} else {
			region
		}
		try {
			val out = ByteArrayOutputStream()
			scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
			return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
		} finally {
			if (scaled !== region) scaled.recycle()
			if (region !== src) region.recycle()
		}
	}

	companion object {
		private const val SYSTEM_PROMPT = "You are a manga translation assistant with precise vision capabilities."
		private const val IGNORE_BLOCK_MARKER = "KAISOKU_IGNORE_BLOCK"
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		private const val TARGET_WIDTH = 1024
		private const val MAX_TILE_ASPECT = 1.4f
		private const val MAX_TILES = 12
		private const val TILE_OVERLAP = 0.06f
		private const val MAX_RETRIES = 3
		private const val BASE_BACKOFF_MS = 1000L
		private const val MAX_BACKOFF_MS = 30_000L
		private const val MAX_DUPLICATE = 3
		private const val REQUEST_TIMEOUT_SEC = 90L
	}
}
