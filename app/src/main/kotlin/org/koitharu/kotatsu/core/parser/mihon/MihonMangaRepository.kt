package org.koitharu.kotatsu.core.parser.mihon

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.Locale

internal data class OrderedMihonChapter(val chapter: SChapter, val number: Float)

/**
 * Mihon/Tachiyomi sources return chapters newest-first, whereas Kotatsu (and every native parser)
 * expects them oldest-first — index 0 must be the first/oldest chapter. Reverse the list, resolve
 * each chapter's number (source-provided `chapter_number` when > 0, else a 1-based positional
 * fallback), then stable-sort ascending by number so any source order — not just the usual
 * newest-first — ends up in reading order. This is bridge-layer normalization, not per-extension
 * work: it mirrors Mihon (which orders by parsed chapter number) and the Futon/Kototoro bridges.
 */
internal fun List<SChapter>.toKaisokuChapterOrder(): List<OrderedMihonChapter> = asReversed()
	.mapIndexed { index, chapter ->
		val number = runCatching { chapter.chapter_number }.getOrDefault(-1f)
			.takeIf { it > 0f } ?: (index + 1).toFloat()
		OrderedMihonChapter(chapter, number)
	}
	.sortedBy { it.number }

class MihonMangaRepository(
	private val loadedSource: MihonExtensionManager.LoadedSource,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

	override val source: MihonMangaSource
		get() = loadedSource.wrapper

	private val mihonSource = loadedSource.catalogueSource
	private var mihonFilters = mihonSource.getFilterList()
	private val defaultFilterState = mihonFilters.stateFingerprint()
	private var lastOffset = -1
	private var currentPage = 1

	override val sortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		add(SortOrder.RELEVANCE)
		if (mihonSource.supportsLatest) {
			add(SortOrder.UPDATED)
		}
	}

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = mihonFilters.isNotEmpty(),
	)

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getExternalFilters(): Any? = mihonFilters.takeIf { it.isNotEmpty() }

	override fun hasExternalFiltersApplied(): Boolean =
		mihonFilters.stateFingerprint() != defaultFilterState

	override fun resetExternalFilters(): Boolean {
		if (!hasExternalFiltersApplied()) return false
		mihonFilters = mihonSource.getFilterList()
		return true
	}

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
		runCatching {
			if (offset <= 0 || offset < lastOffset) {
				currentPage = 1
			} else if (offset > lastOffset && lastOffset >= 0) {
				currentPage += 1
			}
			lastOffset = offset
			val query = filter?.query?.trim().orEmpty()
			val page = currentPage.coerceAtLeast(1)
			val mangas = when {
				query.isNotEmpty() || hasExternalFiltersApplied() ->
					mihonSource.getSearchManga(page, query, mihonFilters)
				(order == SortOrder.UPDATED || order == SortOrder.UPDATED_ASC) && mihonSource.supportsLatest ->
					mihonSource.getLatestUpdates(page)

				else -> mihonSource.getPopularManga(page)
			}
			mangas.mangas.map { it.toKaisokuManga() }
		}.getOrElse { throw mapHostedFailure(it) }
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val seed = manga.toSManga()
		val details = runCatching { mihonSource.getMangaDetails(seed) }.getOrElse { error ->
			when (val mapped = mapHostedFailure(error)) {
				is AuthRequiredException -> throw mapped
				else -> seed
			}
		}
		val seedUrl = seed.safeUrl()
		val seedTitle = seed.safeTitle()
		val seedThumbnail = seed.safeThumbnailUrl()
		if (details.safeUrl().isBlank() && seedUrl.isNotBlank()) {
			details.url = seedUrl
		}
		if (details.safeTitle().isBlank() && seedTitle.isNotBlank()) {
			details.title = seedTitle
		}
		if (details.safeThumbnailUrl().isNullOrBlank() && !seedThumbnail.isNullOrBlank()) {
			details.thumbnail_url = seedThumbnail
		}
		val chapters = loadChapters(seed, details).toKaisokuChapterOrder().map { (chapter, number) ->
			chapter.toKaisokuChapter(number = number)
		}
		details.toKaisokuManga(chapters = chapters).copy(id = manga.id)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		val sChapter = chapter.toSChapter()
		runCatching {
			mihonSource.getPageList(sChapter).mapIndexed { index, page ->
				page.toKaisokuPage(index)
			}
		}.getOrElse { throw mapPageFailure(it) }
	}

	override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
		val uri = page.url.toUri()
		if (uri.isDirectPageUrl()) {
			val rawImageUrl = uri.getQueryParameter("image_url").orEmpty()
			return@withContext rawImageUrl.takeIf { it.isNotBlank() }?.let(::absolutizeUrl) ?: page.url
		}
		if (uri.scheme != RESOLVE_SCHEME) {
			return@withContext page.url
		}
		val index = uri.getQueryParameter("index")?.toIntOrNull() ?: 0
		val rawPageUrl = uri.getQueryParameter("page_url").orEmpty()
		val httpSource = mihonSource as? HttpSource ?: return@withContext rawPageUrl
		val resolvedUrl = runCatching {
			absolutizeUrl(httpSource.getImageUrl(Page(index = index, url = rawPageUrl)))
		}.getOrElse { throw mapPageFailure(it) }
		rememberPageHeaders(
			url = resolvedUrl,
			page = Page(index = index, url = rawPageUrl, imageUrl = resolvedUrl),
		)
		resolvedUrl
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	override fun getImageClient(): OkHttpClient? = (mihonSource as? HttpSource)?.client

	override fun createPageRequest(pageUrl: String, page: MangaPage): Request {
		val httpSource = mihonSource as? HttpSource
		val request = if (httpSource != null) {
			(MihonSourceRegistry.getPage(source, pageUrl) ?: page.toStoredMihonPage(pageUrl))?.let { storedPage ->
				try {
					httpSource.imageRequest(storedPage)
				} catch (e: Throwable) {
					throw mapPageFailure(e)
				}
			}
		} else {
			null
		}
		return (request ?: Request.Builder()
			.url(pageUrl)
			.get()
			.header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
			.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
			.build())
			.newBuilder()
			.tag(org.koitharu.kotatsu.parsers.model.MangaSource::class.java, page.source)
			.build()
	}

	private fun SManga.toKaisokuManga(chapters: List<MangaChapter>? = null): Manga {
		val rawUrl = safeUrl()
		val rawTitle = safeTitle()
		val rawThumbnail = safeThumbnailUrl()
		val stableKey = rawUrl.ifBlank {
			rawThumbnail.orEmpty().ifBlank {
				rawTitle.ifBlank { source.name }
			}
		}
		val publicUrl = (mihonSource as? HttpSource)?.let { httpSource ->
			runCatching { httpSource.getMangaUrl(this) }.getOrNull()
		}.orEmpty().ifBlank {
			rawUrl.takeIf(String::isNotBlank)?.let(::absolutizeUrl).orEmpty()
		}
		return Manga(
			id = stableId(stableKey),
			title = rawTitle.ifBlank { publicUrl.substringAfterLast('/').ifBlank { "Untitled" } },
			altTitles = emptySet(),
			url = rawUrl,
			publicUrl = publicUrl,
			rating = RATING_UNKNOWN,
			contentRating = if (source.isNsfwSource) ContentRating.ADULT else null,
			coverUrl = rawThumbnail?.let(::absolutizeUrl),
			tags = safeGenres().orEmpty().mapTo(LinkedHashSet()) { genre ->
				MangaTag(title = genre, key = genre.lowercase(Locale.ROOT), source = source)
			},
			state = when (safeStatus()) {
				SManga.ONGOING -> MangaState.ONGOING
				SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaState.FINISHED
				SManga.CANCELLED -> MangaState.ABANDONED
				SManga.ON_HIATUS -> MangaState.PAUSED
				else -> null
			},
			authors = setOfNotNull(safeAuthor()?.takeIf(String::isNotBlank), safeArtist()?.takeIf(String::isNotBlank)),
			largeCoverUrl = rawThumbnail?.let(::absolutizeUrl),
			description = safeDescription(),
			chapters = chapters,
			source = source,
		)
	}

	private fun SChapter.toKaisokuChapter(number: Float): MangaChapter {
		val rawUrl = safeUrl()
		return MangaChapter(
			id = stableId(rawUrl.ifBlank { safeName().ifBlank { number.toString() } }),
			title = safeName().takeIf { it.isNotBlank() },
			number = number,
			volume = 0,
			url = rawUrl,
			scanlator = safeScanlator(),
			uploadDate = safeUploadDate(),
			branch = null,
			source = source,
		)
	}

	private fun Page.toKaisokuPage(index: Int): MangaPage {
		val rawImageUrl = imageUrl?.takeIf { it.isNotBlank() }
		if (rawImageUrl != null) {
			val directPageUrl = buildDirectPageUrl(
				index = index,
				rawPageUrl = url,
				rawImageUrl = rawImageUrl,
			)
			rememberPageHeaders(
				url = directPageUrl,
				page = copy(index = index),
			)
			return MangaPage(
				id = stableId(directPageUrl),
				url = directPageUrl,
				preview = rawImageUrl.takeIf(::canUseDirectPreview)?.let(::absolutizeUrl),
				source = source,
			)
		}
		val resolveUrl = Uri.Builder()
			.scheme(RESOLVE_SCHEME)
			.authority("page")
			.appendQueryParameter("index", index.toString())
			.appendQueryParameter("page_url", url)
			.build()
			.toString()
		return MangaPage(
			id = stableId(resolveUrl),
			url = resolveUrl,
			preview = null,
			source = source,
		)
	}

	private fun Manga.toSManga(): SManga = SManga.create().also {
		it.url = url
		it.title = title
		it.thumbnail_url = coverUrl
	}

	private fun MangaChapter.toSChapter(): SChapter = SChapter.create().also {
		it.url = url
		it.name = title ?: name
		it.chapter_number = number
		it.date_upload = uploadDate
		it.scanlator = scanlator
	}

	private fun rememberPageHeaders(url: String, page: Page) {
		MihonSourceRegistry.rememberPage(source, url, page)
		val httpSource = mihonSource as? HttpSource ?: return
		runCatching {
			httpSource.getPageHeaders(page)
		}.onSuccess { headers ->
			MihonSourceRegistry.rememberPageHeaders(source, url, headers)
		}
	}

	private fun absolutizeUrl(rawUrl: String): String {
		if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
			return rawUrl
		}
		val httpSource = mihonSource as? HttpSource ?: return rawUrl
		return when {
			rawUrl.startsWith("//") -> "https:$rawUrl"
			rawUrl.startsWith("/") -> httpSource.baseUrl.trimEnd('/') + rawUrl
			else -> httpSource.baseUrl.trimEnd('/') + "/" + rawUrl.removePrefix("./")
		}
	}

	private fun buildDirectPageUrl(index: Int, rawPageUrl: String, rawImageUrl: String): String {
		return Uri.Builder()
			.scheme("https")
			.authority(DIRECT_PAGE_HOST)
			.appendPath("page")
			.appendQueryParameter("index", index.toString())
			.appendQueryParameter("page_url", rawPageUrl)
			.appendQueryParameter("image_url", rawImageUrl)
			.build()
			.toString()
	}

	private fun MangaPage.toStoredMihonPage(resolvedUrl: String): Page? {
		val uri = url.toUri()
		val index = uri.getQueryParameter("index")?.toIntOrNull() ?: id.toInt()
		return when {
			uri.isDirectPageUrl() -> {
				val rawPageUrl = uri.getQueryParameter("page_url").orEmpty()
				val rawImageUrl = uri.getQueryParameter("image_url").orEmpty()
				Page(
					index = index,
					url = rawPageUrl,
					imageUrl = rawImageUrl.ifBlank { resolvedUrl },
				)
			}

			uri.scheme == RESOLVE_SCHEME -> {
				val rawPageUrl = uri.getQueryParameter("page_url").orEmpty()
				Page(
					index = index,
					url = rawPageUrl,
					imageUrl = resolvedUrl,
				)
			}

			else -> null
		}
	}

	private fun Uri.isDirectPageUrl(): Boolean {
		return host == DIRECT_PAGE_HOST && lastPathSegment == DIRECT_PAGE_PATH
	}

	private fun canUseDirectPreview(rawUrl: String): Boolean {
		return rawUrl.startsWith("http://", ignoreCase = true) ||
			rawUrl.startsWith("https://", ignoreCase = true) ||
			rawUrl.startsWith("//")
	}

	private fun stableId(rawValue: String): Long = mihonStableId(source.name, rawValue)

	private suspend fun loadChapters(seed: SManga, details: SManga): List<SChapter> {
		val candidates = buildList {
			if (details !== seed) {
				add(details)
			}
			add(seed)
		}
		var hadSuccessfulLoad = false
		var lastError: Throwable? = null
		for (candidate in candidates) {
			val result = runCatching { mihonSource.getChapterList(candidate) }
			val chapters = result.getOrNull()
			if (chapters != null) {
				hadSuccessfulLoad = true
				if (chapters.isNotEmpty()) {
					return chapters
				}
			} else {
				lastError = result.exceptionOrNull()
			}
		}
		if (hadSuccessfulLoad) {
			return emptyList()
		}
		throw mapHostedFailure(lastError ?: IllegalStateException("Unable to load chapters"))
	}

	private fun mapHostedFailure(error: Throwable): Throwable {
		if (error is AuthRequiredException) {
			return error
		}
		val message = buildString {
			appendLine(error.message.orEmpty())
			error.cause?.message?.takeIf { it.isNotBlank() }?.let(::append)
		}.lowercase(Locale.ROOT)
		val looksLikeHostedWebViewAuth = "необходима авторизация через webview" in message ||
			(("authoriz" in message || "авториза" in message || "login" in message || "sign in" in message) &&
				"webview" in message)
		return if (looksLikeHostedWebViewAuth) {
			AuthRequiredException(source, error)
		} else {
			error
		}
	}

	private fun mapPageFailure(error: Throwable): Throwable {
		if (error is AuthRequiredException) {
			return error
		}
		val message = buildString {
			appendLine(error.message.orEmpty())
			error.cause?.message?.takeIf { it.isNotBlank() }?.let(::append)
		}.lowercase(Locale.ROOT)
		val looksLikeAuthFailure = (
			("authoriz" in message || "авториза" in message || "login" in message || "sign in" in message) &&
				("webview" in message || "cookie" in message || "session" in message)
			) || "необходима авторизация через webview" in message
		return if (looksLikeAuthFailure) {
			AuthRequiredException(source, error)
		} else {
			error
		}
	}

	private fun SManga.safeUrl(): String = runCatching { url }.getOrDefault("")

	private fun SManga.safeTitle(): String = runCatching { title }.getOrDefault("")

	private fun SManga.safeThumbnailUrl(): String? = runCatching { thumbnail_url }.getOrNull()

	private fun SManga.safeGenres(): List<String>? = runCatching { getGenres() }.getOrNull()

	private fun SManga.safeStatus(): Int = runCatching { status }.getOrDefault(SManga.UNKNOWN)

	private fun SManga.safeAuthor(): String? = runCatching { author }.getOrNull()

	private fun SManga.safeArtist(): String? = runCatching { artist }.getOrNull()

	private fun SManga.safeDescription(): String? = runCatching { description }.getOrNull()

	private fun SChapter.safeUrl(): String = runCatching { url }.getOrDefault("")

	private fun SChapter.safeName(): String = runCatching { name }.getOrDefault("")

	private fun SChapter.safeScanlator(): String? = runCatching { scanlator }.getOrNull()

	private fun SChapter.safeUploadDate(): Long = runCatching { date_upload }.getOrDefault(0L)

	private companion object {
		private const val RESOLVE_SCHEME = "mihon-resolve"
		private const val DIRECT_PAGE_HOST = "mihon.invalid"
		private const val DIRECT_PAGE_PATH = "page"
	}
}

private fun FilterList.stateFingerprint(): List<Any?> = map { it.stateFingerprint() }

private fun Filter<*>.stateFingerprint(): Any? = when (this) {
	is Filter.Header, is Filter.Separator -> null
	is Filter.Group<*> -> listOf(name, state.map { (it as? Filter<*>)?.stateFingerprint() ?: it.toString() })
	is Filter.Sort -> listOf(name, state?.index, state?.ascending)
	else -> listOf(name, state)
}
