package org.koitharu.kotatsu.core.parser.lnreader

import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.db.entity.LnReaderSourceEntity
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MangaRepository for LNReader novel sources: maps the plugin bridge output onto
 * Kaisoku's Manga/MangaChapter/MangaPage models.
 *
 * Novel chapters are text: [getChapterHtml] is the real content path (used by the
 * novel reader); [getPagesImpl] also exposes the chapter HTML as a single
 * base64 data-URL page so image-oriented callers degrade gracefully.
 * The chapter URL carries `novelPath|||chapterPath` so the plugin can be re-invoked.
 */
class LnReaderMangaRepository(
	val entity: LnReaderSourceEntity,
	private val httpClient: OkHttpClient,
	cache: MemoryContentCache,
	private val diskCacheDir: File? = null,
	private val storage: LNReaderStorage = LNReaderStorage(),
) : CachingMangaRepository(cache) {

	override val source: LnReaderMangaSource = entity.toMangaSource()

	override val sortOrders: Set<SortOrder> = setOf(SortOrder.POPULARITY, SortOrder.UPDATED)

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
	)

	private val listMutex = kotlinx.coroutines.sync.Mutex()
	private val pagination = LNReaderPagination()
	@Volatile var imageHeaders: Map<String, String> = entity.site?.let { mapOf("Referer" to it) }.orEmpty()
		private set
	@Volatile private var imageHeadersLoaded = false
	private val imageHeadersMutex = kotlinx.coroutines.sync.Mutex()

	suspend fun getImageHeaders(): Map<String, String> {
		if (!imageHeadersLoaded) imageHeadersMutex.withLock {
			if (!imageHeadersLoaded) executeInPluginContext { }
		}
		return imageHeaders
	}

	private val chapterHtmlMutex = MultiMutex<String>()
	private val chapterHtmlCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
			return size > 8
		}
	}

	// ==================== Listing / details ====================

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> =
		listMutex.withLock {
			val query = filter?.query?.trim().orEmpty()
			val key = query to (order ?: defaultSortOrder)
			val page = pagination.pageFor(key.toString(), offset)
			val result = executeInPluginContext { bridge ->
				val novels = if (query.isNotEmpty()) bridge.searchNovels(query, page)
				else bridge.popularNovels(page, latest = key.second == SortOrder.UPDATED)
				novels.map { novel ->
					novel.toManga().let { it.copy(publicUrl = absoluteUrl(bridge.resolveUrl(novel.path) ?: it.publicUrl)) }
				}
			}
			pagination.accept(key.toString(), offset, page, result.size)
			result
		}

	override suspend fun getDetailsImpl(manga: Manga): Manga {
		val novelPath = manga.url.substringBefore(CHAPTER_SEPARATOR)
		if (novelPath.isBlank()) return manga
		return executeInPluginContext { bridge ->
			val details = bridge.parseNovel(novelPath)
			val chapters = normalizeNovelChapterOrder(
				entity.pluginId, loadAllNovelChapters(details) { page -> bridge.parsePage(novelPath, page) },
			) { it.name }
			val existingChapters = manga.chapters.orEmpty().associateBy { it.url.substringAfter(CHAPTER_SEPARATOR) }

			Manga(
				id = manga.id,
				title = details.name.ifBlank { manga.title },
				altTitles = emptySet(),
				url = details.path.ifBlank { novelPath },
				publicUrl = absoluteUrl(bridge.resolveUrl(novelPath) ?: manga.publicUrl.ifBlank { novelPath }),
				rating = manga.rating,
				contentRating = manga.contentRating,
				coverUrl = details.cover.takeIf(String::isNotBlank)?.let(::absoluteUrl) ?: manga.coverUrl,
				largeCoverUrl = details.cover.takeIf(String::isNotBlank)?.let(::absoluteUrl) ?: manga.largeCoverUrl,
				tags = details.genres.mapTo(LinkedHashSet()) {
					MangaTag(title = it, key = it.lowercase(Locale.ROOT), source = source)
				},
				state = when (details.status.lowercase(Locale.ROOT)) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus", "on hiatus" -> MangaState.PAUSED
					"cancelled", "dropped" -> MangaState.ABANDONED
					else -> null
				},
				authors = details.author.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: manga.authors,
				description = details.summary.ifBlank { manga.description },
				chapters = chapters.mapIndexed { index, ch ->
					MangaChapter(
						id = existingChapters[ch.path]?.id ?: stableId("${details.path}|${ch.path}"),
						title = ch.name.ifBlank { ch.chapterNumber?.let { "Chapter $it" } ?: "Chapter ${index + 1}" },
						number = ch.chapterNumber?.toFloatOrNull() ?: (index + 1).toFloat(),
						volume = 0,
						url = "${details.path.ifBlank { novelPath }}$CHAPTER_SEPARATOR${ch.path}",
						scanlator = ch.releaseTime,
						uploadDate = 0L,
						branch = null,
						source = source,
					)
				},
				source = source,
			)
		}
	}

	// ==================== Chapter content ====================

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> {
		val html = loadChapterHtml(chapter)
		if (html.isBlank()) return emptyList()
		val encoded = android.util.Base64.encodeToString(
			html.toByteArray(Charsets.UTF_8),
			android.util.Base64.NO_WRAP,
		)
		return listOf(
			MangaPage(
				id = stableId(chapter.url),
				url = "data:text/html;base64,$encoded",
				preview = null,
				source = source,
			)
		)
	}

	/**
	 * Chapter HTML for the novel reader. Falls back to the data-URL page cache.
	 */
	suspend fun getChapterHtml(chapter: MangaChapter): String = loadChapterHtml(chapter)

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	override fun getImageClient(): OkHttpClient? = httpClient

	override fun createPageRequest(pageUrl: String, page: MangaPage): okhttp3.Request =
		super.createPageRequest(pageUrl, page).newBuilder().apply {
			imageHeaders.forEach { (name, value) -> header(name, value) }
		}.build()

	// ==================== Internal ====================

	private fun absoluteUrl(path: String): String =
		entity.site?.toHttpUrlOrNull()?.resolve(path)?.toString() ?: path

	private suspend fun <T> executeInPluginContext(block: suspend (LNReaderPluginBridge) -> T): T {
		return withContext(Dispatchers.IO) {
			val fetchBridge = LNReaderFetchBridge(httpClient, entity.pluginId)
			val engine = LNReaderEngine(fetchBridge, storage)
			val qjs = engine.createPluginContext(entity.jsCode, entity.pluginId)
			try {
				val bridge = LNReaderPluginBridge(qjs, entity.pluginId)
				imageHeaders = imageHeaders + bridge.imageHeaders()
				imageHeadersLoaded = true
				block(bridge).also {
					// Re-throw any fatal interactive exceptions that were tunneled out of the fetch
					// bridge even if JS swallowed the error and resolved the promise.
					fetchBridge.pendingFatalException?.let { throw it }
				}
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				fetchBridge.pendingFatalException?.let { throw it }
				LnLog.e(TAG, "executeInPluginContext failed for ${source.name}", e)
				val context = fetchBridge.lastRequestDescription?.let { "\n$it" }.orEmpty()
				throw LNReaderJSException("LNReader JS Error in ${source.name}: ${e.message ?: e.javaClass.simpleName}$context", e)
			} finally {
				qjs.close()
			}
		}
	}

	private suspend fun loadChapterHtml(chapter: MangaChapter): String {
		val cacheKey = chapter.url
		synchronized(chapterHtmlCache) {
			chapterHtmlCache[cacheKey]?.let { return it }
		}
		return chapterHtmlMutex.withLock(cacheKey) {
			synchronized(chapterHtmlCache) {
				chapterHtmlCache[cacheKey]?.let { return@withLock it }
			}
			val diskFile = diskFileFor(cacheKey)
			val cached = diskFile?.takeIf { it.isFile }?.let { file ->
				runCatching { withContext(Dispatchers.IO) { file.readText() } }.getOrNull()
			}
			if (cached != null && cached.isNotBlank()) {
				synchronized(chapterHtmlCache) {
					chapterHtmlCache[cacheKey] = cached
				}
				return@withLock cached
			}
			val chapterPath = chapter.url.split(CHAPTER_SEPARATOR, limit = 2).getOrNull(1) ?: chapter.url
			val html = executeInPluginContext { bridge ->
				val raw = bridge.parseChapter(chapterPath)
				val baseUrl = absoluteUrl(bridge.resolveUrl(chapterPath, isNovel = false) ?: chapterPath)
				org.jsoup.Jsoup.parseBodyFragment(raw, baseUrl).apply {
					outputSettings().prettyPrint(false)
					select("img[src]").forEach { image ->
						image.absUrl("src").takeIf(String::isNotBlank)?.let { image.attr("src", it) }
					}
				}.body().html()
			}
			if (html.isNotBlank()) {
				synchronized(chapterHtmlCache) {
					chapterHtmlCache[cacheKey] = html
				}
				diskFile?.let { file ->
					withContext(Dispatchers.IO) {
						runCatching {
							file.parentFile?.mkdirs()
							val tmp = File(file.parentFile, file.name + ".tmp")
							tmp.writeText(html)
							if (!tmp.renameTo(file)) {
								tmp.delete()
							}
						}
					}
				}
			}
			html
		}
	}

	private fun diskFileFor(cacheKey: String): File? {
		val dir = diskCacheDir ?: return null
		val digest = java.security.MessageDigest.getInstance("SHA-256")
			.digest((entity.pluginId + '\n' + cacheKey).toByteArray(Charsets.UTF_8))
		val name = digest.joinToString("") { "%02x".format(it) }
		return File(dir, name)
	}

	private fun LNReaderNovelItem.toManga(): Manga = Manga(
		id = stableId(path),
		title = name,
		altTitles = emptySet(),
		url = path,
		publicUrl = path,
		rating = 0f,
		contentRating = null,
		coverUrl = cover.takeIf(String::isNotBlank)?.let(::absoluteUrl),
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		largeCoverUrl = null,
		description = null,
		chapters = null,
		source = source,
	)

	private fun stableId(rawValue: String): Long {
		var hash = LONG_HASH_SEED
		source.name.forEach { hash = 31 * hash + it.code }
		rawValue.forEach { hash = 31 * hash + it.code }
		return hash
	}

	companion object {
		private const val TAG = "LnReaderRepo"
		const val CHAPTER_SEPARATOR = "|||"
		private const val LONG_HASH_SEED = 1125899906842597L
	}
}
