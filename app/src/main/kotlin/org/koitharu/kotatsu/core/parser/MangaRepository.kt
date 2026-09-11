package org.koitharu.kotatsu.core.parser

import android.content.Context
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.parser.external.ExternalMangaRepository
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceManager
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaRepository
import org.koitharu.kotatsu.browsersource.data.BrowserSourceMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.customsource.domain.CustomMangaSource
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

internal fun findBuiltInParserSource(sourceName: String): MangaParserSource? =
	MangaParserSource.entries.firstOrNull { it.name == sourceName }

interface MangaRepository {

	val source: MangaSource

	val sortOrders: Set<SortOrder>

	var defaultSortOrder: SortOrder

	val filterCapabilities: MangaListFilterCapabilities

	suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga>

	suspend fun getDetails(manga: Manga): Manga

	suspend fun getPages(chapter: MangaChapter): List<MangaPage>

	suspend fun getPageUrl(page: MangaPage): String

	suspend fun getFilterOptions(): MangaListFilterOptions

	suspend fun getRelated(seed: Manga): List<Manga>

	fun getImageClient(): OkHttpClient? = null

	fun createPageRequest(pageUrl: String, page: MangaPage): Request = Request.Builder()
		.url(pageUrl)
		.get()
		.header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
		.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
		.tag(MangaSource::class.java, page.source)
		.build()

	/**
	 * Builds the page Request the downloader will fire for [page]; uses [getPageUrl] unless this
	 * repository has a cheaper in-memory path (e.g. a cached `Page` for a Mihon extension).
	 */
	/**
	 * Resolves and fetches the canonical page bytes through the image-proxy connector (when one is
	 * set up), mirroring `ImageProxyInterceptor.interceptPageRequest`.
	 */
	suspend fun getPageResponse(
		page: MangaPage,
		okHttp: OkHttpClient,
		imageProxyInterceptor: org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor,
	): Response {
		return imageProxyInterceptor.interceptPageRequest(getPageRequest(page), okHttp)
	}

	suspend fun getPageRequest(page: MangaPage): Request = createPageRequest(getPageUrl(page), page)

	/**
	 * Source-defined filter/search UI descriptors surfaced at the list root (e.g. the TsukiMix-style
	 * `filter` map for Mihon extensions, or `SetupFields` for Content-Provider plugins).
	 * `null` when the source doesn't declare any.
	 */
	suspend fun getExternalFilters(): Any? = null

	/** Returns whether source-defined filters currently differ from their defaults. */
	fun hasExternalFiltersApplied(): Boolean = false

	/** Restores source-defined filters to their extension-provided defaults. */
	fun resetExternalFilters(): Boolean = false

	suspend fun find(manga: Manga): Manga? {
		val list = getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
		return list.find { x -> x.id == manga.id }
	}

	@Singleton
	class Factory @Inject constructor(
		@ApplicationContext private val context: Context,
		private val localMangaRepository: LocalMangaRepository,
		private val loaderContext: MangaLoaderContext,
		private val contentCache: MemoryContentCache,
		private val mirrorSwitcher: MirrorSwitcher,
		private val mihonExtensionManager: MihonExtensionManager,
		private val lnReaderSourceManager: LnReaderSourceManager,
	) {

		private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()

		@AnyThread
		fun create(source: MangaSource): MangaRepository {
			when (source) {
				is MangaSourceInfo -> return create(source.mangaSource)
				LocalMangaSource -> return localMangaRepository
				UnknownMangaSource -> return EmptyMangaRepository(source)
			}
			cache[source]?.get()?.let { return it }
			return synchronized(cache) {
				cache[source]?.get()?.let { return it }
				val repository = createRepository(source)
				if (repository != null) {
					cache[source] = WeakReference(repository)
					repository
				} else {
					EmptyMangaRepository(source)
				}
			}
		}

		private fun createRepository(source: MangaSource): MangaRepository? = when (source) {
			// Constructing a built-in parser can still throw (a malformed source, a removed symbol, or
			// an unavailable source picked up from an old backup). Contain it so the source degrades to
			// an empty one instead of taking down the whole app.
			is MangaParserSource -> createBuiltInRepository(source)

			is PluginMangaSource -> createPluginRepository(source)

			TestMangaSource -> TestMangaRepository(
				loaderContext = loaderContext,
				cache = contentCache,
			)

			is ExternalMangaSource -> if (source.isAvailable(context)) {
				ExternalMangaRepository(
					contentResolver = context.contentResolver,
					source = source,
					cache = contentCache,
				)
			} else {
				EmptyMangaRepository(source)
			}

			is MihonMangaSource -> mihonExtensionManager.resolve(source)?.let {
				MihonMangaRepository(
					loadedSource = it,
					cache = contentCache,
				)
			} ?: EmptyMangaRepository(source)

			// Installed LNReader novel plugin: resolve the entity from the manager's
			// registry-backed snapshot; a missing plugin degrades to an empty source.
			is LnReaderMangaSource -> runCatchingCancellable {
				lnReaderSourceManager.peekEntity(source.pluginId)?.let { entity ->
					LnReaderMangaRepository(
						entity = entity,
						httpClient = lnReaderSourceManager.httpClient,
						cache = contentCache,
						diskCacheDir = java.io.File(context.cacheDir, "lnreader_chapters"),
					)
				}
			}.getOrNull() ?: EmptyMangaRepository(source)

			// User-defined in-app browser source: pages are stashed by BrowserSourceActivity and served
			// back from the page store (only BROWSER_SOURCE custom sources exist in this build).
			is CustomMangaSource -> BrowserSourceMangaRepository(source)

			else -> null
		}

		private fun createBuiltInRepository(source: MangaParserSource): ParserMangaRepository? =
			runCatchingCancellable {
				ParserMangaRepository(
					parser = loaderContext.newParserInstance(source),
					cache = contentCache,
					mirrorSwitcher = mirrorSwitcher,
				)
			}.onFailure { it.printStackTraceDebug() }.getOrNull()

		private fun createPluginRepository(source: PluginMangaSource): MangaRepository? {
			// Plugins carry their own parser classes but share MangaLoaderContext with the host. Older
			// Android runtimes can dispatch a virtual call to the wrong slot after that abstract host
			// API gains methods (issue #12 called getConfig() as evaluateJs() with a null continuation).
			// Resolve domain once now so an incompatible copy is detected before it reaches any screen.
			val plugin = runCatchingCancellable {
				PluginMangaRepository(
					loadedParser = DynamicParserManager.createParser(source, loaderContext, context),
					settings = SourceSettings(context, source),
					cache = contentCache,
				)
			}.onFailure { it.printStackTraceDebug() }.getOrNull()
			val compatiblePlugin = plugin?.let { repository ->
				runCatchingCancellable {
					repository.domain.takeIf(String::isNotBlank)
				}.onFailure { it.printStackTraceDebug() }.getOrNull()?.let { repository }
			}
			if (compatiblePlugin != null) {
				return compatiblePlugin
			}
			val builtInSource = findBuiltInParserSource(source.sourceName) ?: return null
			return createBuiltInRepository(builtInSource)
		}
	}
}
