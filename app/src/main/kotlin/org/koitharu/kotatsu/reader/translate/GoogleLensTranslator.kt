package org.koitharu.kotatsu.reader.translate

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.util.await
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Keyless, server-less translator: Google Lens OCR (the Chromium "lens overlay" upload API, which
 * ships a public API key — no user key) detects text + bubble boxes, then Google's keyless
 * `translate_a` endpoint translates them. Produces [PageTranslationResult.Blocks], so the existing
 * renderer / tap-to-reveal / OCR sheet all work. Pure HTTP, no WebView.
 */
@Singleton
class GoogleLensTranslator @Inject constructor(
	@MangaHttpClient private val okHttpClient: OkHttpClient,
	private val settings: AppSettings,
) : PageTranslator {

	override suspend fun translate(
		bitmap: Bitmap,
		sourceLang: String,
		targetLang: String,
		onTotal: (Int) -> Unit,
		onTileDone: (success: Boolean) -> Unit,
	): PageTranslationResult = withContext(Dispatchers.IO) {
		val tl = targetLang.ifBlank { "en" }
		// Webtoon pages are very tall strips; scaling the whole page to a sane width would crush it to
		// a few px wide and Lens would read nothing. Scale by width and OCR in full-width horizontal
		// tiles instead, mapping each tile's boxes back to full-page space (like the LLM path).
		val tiles = planTiles(bitmap.width, bitmap.height)
		onTotal(tiles.size)
		val all = ArrayList<TranslatedBlock>()
		var lastError: Throwable? = null
		var anySuccess = false
		for (tile in tiles) {
			try {
				val enc = runInterruptible { encodeRegion(bitmap, tile) }
				all += mapTileToFull(lensOcr(enc, tl), tile, bitmap.height)
				anySuccess = true
				onTileDone(true)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				lastError = e
				onTileDone(false)
			}
		}
		if (!anySuccess) lastError?.let { throw it }
		if (all.isEmpty()) return@withContext PageTranslationResult.Blocks(emptyList())
		PageTranslationResult.Blocks(translateBlocks(all, sourceLang.ifBlank { "auto" }, tl))
	}

	private suspend fun lensOcr(image: EncodedImage, language: String): List<TranslatedBlock> {
		val payload = LensProtocol.encodeRequest(image.bytes, image.width, image.height, language)
		val request = Request.Builder()
			.url(LENS_ENDPOINT)
			.header("X-Goog-Api-Key", LENS_API_KEY)
			.header("User-Agent", CHROME_UA)
			.header("Accept-Language", "$language,en;q=0.9")
			.post(payload.toRequestBody(PROTO_MEDIA_TYPE))
			.build()
		val response = try {
			okHttpClient.newCall(request).await()
		} catch (e: IOException) {
			throw TranslateException.Network(e)
		}
		return response.use {
			if (!it.isSuccessful) {
				throw TranslateException.Http(it.code, it.body?.string().orEmpty())
			}
			val bytes = it.body?.bytes() ?: throw TranslateException.Parse("empty Lens response")
			val parsed = runCatching { LensProtocol.decodeResponse(bytes) }
				.getOrElse { e -> throw TranslateException.Parse("Lens decode: ${e.message}", e) }
			val blocks = extractBlocks(parsed)
			Log.i(TAG, "lens ${bytes.size}B ${image.width}x${image.height} -> ${blocks.size} blocks")
			blocks
		}
	}

	private fun extractBlocks(response: LensProtocol.ServerResponse): List<TranslatedBlock> {
		val layout = response.objectsResponse?.text?.textLayout ?: return emptyList()
		val out = ArrayList<TranslatedBlock>()
		for (paragraph in layout.paragraphs) {
			val sb = StringBuilder()
			for (line in paragraph.lines) {
				for ((i, word) in line.words.withIndex()) {
					sb.append(word.plainText)
					when {
						word.textSeparator.isNotEmpty() -> sb.append(word.textSeparator)
						i < line.words.size - 1 -> sb.append(' ')
					}
				}
				sb.append(' ')
			}
			val text = sb.toString().replace(WHITESPACE, " ").trim()
			if (text.isEmpty()) continue
			val box = paragraph.geometry?.boundingBox
				?: paragraph.lines.firstNotNullOfOrNull { it.geometry?.boundingBox }
				?: continue
			if (box.coordinateType != LensProtocol.COORD_NORMALIZED) continue
			val left = (box.centerX - box.width / 2f).coerceIn(0f, 1f)
			val top = (box.centerY - box.height / 2f).coerceIn(0f, 1f)
			val right = (box.centerX + box.width / 2f).coerceIn(0f, 1f)
			val bottom = (box.centerY + box.height / 2f).coerceIn(0f, 1f)
			if (left >= right || top >= bottom) continue
			out += TranslatedBlock(originalText = text, translatedText = "", rect = RectF(left, top, right, bottom))
		}
		return out
	}

	private suspend fun translateBlocks(
		blocks: List<TranslatedBlock>,
		sourceLang: String,
		targetLang: String,
	): List<TranslatedBlock> = coroutineScope {
		// Translate each bubble independently (newline-batching mis-aligns and leaves bubbles
		// untranslated). Dedupe identical strings and bound concurrency so we don't hammer the
		// endpoint into a 429.
		val semaphore = Semaphore(TRANSLATE_CONCURRENCY)
		val unique = blocks.mapTo(LinkedHashSet()) { it.originalText }
		val translations = unique.map { text ->
			async {
				text to runCatching {
					semaphore.withPermit { translateText(text, sourceLang, targetLang) }
				}.getOrDefault(text)
			}
		}.awaitAll().toMap()
		blocks.mapNotNull { b ->
			val t = translations[b.originalText]?.trim().orEmpty().ifBlank { b.originalText }
			if (t.isBlank()) null else b.copy(translatedText = t)
		}
	}

	private suspend fun translateText(text: String, sourceLang: String, targetLang: String): String {
		if (text.isBlank()) return ""
		val q = URLEncoder.encode(text, "UTF-8")
		val url = "$TRANSLATE_ENDPOINT?client=gtx&dt=t&sl=$sourceLang&tl=$targetLang&q=$q"
		val request = Request.Builder().url(url).header("User-Agent", CHROME_UA).build()
		val response = try {
			okHttpClient.newCall(request).await()
		} catch (e: IOException) {
			throw TranslateException.Network(e)
		}
		return response.use {
			val body = it.body?.string().orEmpty()
			if (!it.isSuccessful) throw TranslateException.Http(it.code, body)
			parseTranslateResponse(body)
		}
	}

	/** translate_a/single shape: `[[[translated, original, ...], ...], ...]` — concat all segments. */
	private fun parseTranslateResponse(body: String): String {
		val root = runCatching { JSONArray(body) }.getOrNull() ?: throw TranslateException.Parse("translate: not JSON")
		val segments = root.optJSONArray(0) ?: return ""
		val sb = StringBuilder()
		for (i in 0 until segments.length()) {
			sb.append(segments.optJSONArray(i)?.optString(0).orEmpty())
		}
		return sb.toString()
	}

	private data class Tile(val y0: Int, val y1: Int)

	/** Full-width horizontal slices, sized so each stays legible (~[TARGET_WIDTH] wide) after scaling. */
	private fun planTiles(width: Int, height: Int): List<Tile> {
		val scale = minOf(1f, TARGET_WIDTH.toFloat() / width)
		val scaledHeight = height * scale
		if (scaledHeight <= MAX_TILE_HEIGHT) return listOf(Tile(0, height))
		val count = ceil(scaledHeight / MAX_TILE_HEIGHT).toInt().coerceIn(1, MAX_TILES)
		val step = height / count
		val overlap = (step * TILE_OVERLAP).toInt()
		return (0 until count).map { i ->
			val y0 = (i * step - overlap).coerceAtLeast(0)
			val y1 = if (i == count - 1) height else ((i + 1) * step + overlap).coerceAtMost(height)
			Tile(y0, y1)
		}
	}

	/** Map a tile's normalised rects (0..1 of the tile) into full-image normalised space. */
	private fun mapTileToFull(blocks: List<TranslatedBlock>, tile: Tile, fullHeight: Int): List<TranslatedBlock> {
		if (tile.y0 == 0 && tile.y1 == fullHeight) return blocks
		val span = (tile.y1 - tile.y0).toFloat()
		val h = fullHeight.toFloat()
		return blocks.map { b ->
			b.copy(
				rect = RectF(
					b.rect.left,
					(tile.y0 + b.rect.top * span) / h,
					b.rect.right,
					(tile.y0 + b.rect.bottom * span) / h,
				),
			)
		}
	}

	private fun encodeRegion(src: Bitmap, tile: Tile): EncodedImage {
		val regionHeight = tile.y1 - tile.y0
		val scale = minOf(1f, TARGET_WIDTH.toFloat() / src.width)
		val targetW = (src.width * scale).toInt().coerceAtLeast(1)
		val targetH = (regionHeight * scale).toInt().coerceAtLeast(1)
		val region = if (tile.y0 == 0 && regionHeight == src.height) {
			src
		} else {
			Bitmap.createBitmap(src, 0, tile.y0, src.width, regionHeight)
		}
		val scaled = if (region.width != targetW || region.height != targetH) {
			Bitmap.createScaledBitmap(region, targetW, targetH, true)
		} else {
			region
		}
		return try {
			val out = ByteArrayOutputStream()
			scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
			EncodedImage(out.toByteArray(), scaled.width, scaled.height)
		} finally {
			if (scaled !== region) scaled.recycle()
			if (region !== src) region.recycle()
		}
	}

	private class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

	companion object {
		private const val TAG = "KaisokuLens"
		private const val LENS_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
		private const val LENS_API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
		private const val TRANSLATE_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
		private const val TARGET_WIDTH = 1080
		private const val MAX_TILE_HEIGHT = 1600f
		private const val TILE_OVERLAP = 0.04f
		private const val MAX_TILES = 20
		private const val TRANSLATE_CONCURRENCY = 6
		private val PROTO_MEDIA_TYPE = "application/x-protobuf".toMediaType()
		private val WHITESPACE = Regex("\\s+")
		private const val CHROME_UA =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/124.0.0.0 Safari/537.36"
	}
}
