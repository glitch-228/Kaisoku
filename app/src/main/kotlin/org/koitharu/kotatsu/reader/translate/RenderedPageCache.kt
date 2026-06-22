package org.koitharu.kotatsu.reader.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk LRU cache for rendered translation overlays. Each entry stores
 *  - `<hash>.jpg` — the rendered page (overlay baked in)
 *  - `<hash>.json` — the parsed [TranslatedBlock] list (so OCR sheet can read text on a hit)
 *
 * Eviction is by file mtime, capped at [MAX_BYTES]. Atomicity uses `<name>.part` + rename.
 */
@Singleton
class RenderedPageCache @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	data class Entry(val bitmap: Bitmap, val blocks: List<TranslatedBlock>)

	private val mutex = Mutex()

	private val rootDir: File
		get() = File(context.cacheDir, ROOT_DIR).apply { mkdirs() }

	fun keyFor(pageId: Long, settings: AppSettings): String {
		val raw = buildString {
			append(CACHE_VERSION).append('|')
			append(pageId).append('|')
			append(settings.translateProvider.name).append('|')
			append(settings.translateEndpoint.trim()).append('|')
			append(settings.translateModel.trim()).append('|')
			append(settings.translateSourceLanguage).append('|')
			append(settings.translateTargetLanguage).append('|')
			append(settings.translateOverlayBackground)
		}
		return sha256(raw)
	}

	suspend fun get(key: String): Entry? = mutex.withLock {
		val jpg = File(rootDir, "$key.jpg")
		val json = File(rootDir, "$key.json")
		if (!jpg.isFile || !json.isFile) return@withLock null
		runInterruptible(Dispatchers.IO) {
			val now = System.currentTimeMillis()
			jpg.setLastModified(now)
			json.setLastModified(now)
			val bitmap = BitmapFactory.decodeFile(jpg.absolutePath) ?: return@runInterruptible null
			val blocks = runCatching { decodeBlocks(json.readText()) }.getOrDefault(emptyList())
			Entry(bitmap, blocks)
		}
	}

	suspend fun put(key: String, bitmap: Bitmap, blocks: List<TranslatedBlock>) = mutex.withLock {
		runInterruptible(Dispatchers.IO) {
			val jpgPart = File(rootDir, "$key.jpg.part")
			FileOutputStream(jpgPart).use { out ->
				bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
			}
			jpgPart.renameTo(File(rootDir, "$key.jpg"))
			val jsonPart = File(rootDir, "$key.json.part")
			jsonPart.writeText(encodeBlocks(blocks))
			jsonPart.renameTo(File(rootDir, "$key.json"))
			trim()
		}
	}

	suspend fun clearAll() = mutex.withLock {
		runInterruptible(Dispatchers.IO) { rootDir.deleteRecursively() }
	}

	suspend fun sizeBytes(): Long = mutex.withLock {
		runInterruptible(Dispatchers.IO) {
			rootDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
		}
	}

	private fun trim() {
		val files = rootDir.listFiles()?.filter { it.isFile } ?: return
		val total = files.sumOf { it.length() }
		if (total <= MAX_BYTES) return
		val pairs = files.groupBy { it.nameWithoutExtension }
		val oldestFirst = pairs.values.sortedBy { it.maxOfOrNull { f -> f.lastModified() } ?: 0L }
		var remaining = total
		for (group in oldestFirst) {
			if (remaining <= MAX_BYTES) break
			for (f in group) {
				remaining -= f.length()
				f.delete()
			}
		}
	}

	private fun encodeBlocks(blocks: List<TranslatedBlock>): String {
		val arr = JSONArray()
		for (b in blocks) {
			arr.put(
				JSONObject()
					.put("o", b.originalText)
					.put("t", b.translatedText)
					.put("l", b.rect.left.toDouble())
					.put("u", b.rect.top.toDouble())
					.put("r", b.rect.right.toDouble())
					.put("d", b.rect.bottom.toDouble()),
			)
		}
		return arr.toString()
	}

	private fun decodeBlocks(json: String): List<TranslatedBlock> {
		val arr = JSONArray(json)
		val out = ArrayList<TranslatedBlock>(arr.length())
		for (i in 0 until arr.length()) {
			val o = arr.getJSONObject(i)
			out += TranslatedBlock(
				originalText = o.optString("o", ""),
				translatedText = o.optString("t", ""),
				rect = RectF(
					o.optDouble("l").toFloat(),
					o.optDouble("u").toFloat(),
					o.optDouble("r").toFloat(),
					o.optDouble("d").toFloat(),
				),
			)
		}
		return out
	}

	private fun sha256(raw: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
		return digest.joinToString("") { "%02x".format(it) }
	}

	private companion object {
		const val ROOT_DIR = "translate-renders"
		const val CACHE_VERSION = 1
		const val MAX_BYTES = 200L * 1024L * 1024L
	}
}
