package org.koitharu.kotatsu.core.parser

import android.content.Context
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
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
			is MangaParserSource -> runCatchingCancellable {
				ParserMangaRepository(
					parser = loaderContext.newParserInstance(source),
					cache = contentCache,
					mirrorSwitcher = mirrorSwitcher,
				)
			}.onFailure { it.printStackTraceDebug() }.getOrNull()

			// A third-party plugin built against an incompatible parsers ABI (e.g. a constructor whose
			// signature has since changed) throws NoSuchMethodError/LinkageError on construction. Contain
			// it so a broken plugin degrades to an empty source instead of crashing the whole app.
			is PluginMangaSource -> runCatchingCancellable {
				PluginMangaRepository(
					loadedParser = DynamicParserManager.createParser(source, loaderContext, context),
					settings = SourceSettings(context, source),
					cache = contentCache,
				)
			}.onFailure { it.printStackTraceDebug() }.getOrNull()

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

			else -> null
		}
	}
}
