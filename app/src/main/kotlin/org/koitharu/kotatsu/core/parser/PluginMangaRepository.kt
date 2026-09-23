package org.koitharu.kotatsu.core.parser

import kotlinx.coroutines.Dispatchers
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy

class PluginMangaRepository(
	private val loadedParser: DynamicParserManager.LoadedParser,
	private val settings: SourceSettings,
	cache: MemoryContentCache,
	private val appSettings: AppSettings,
) : CachingMangaRepository(cache), Interceptor {

	private val delegate: Any
		get() = loadedParser.delegate

	private val filterOptionsLazy = suspendLazy(Dispatchers.Default) {
		callSuspend<MangaListFilterOptions>("getFilterOptions")
	}

	override val source: PluginMangaSource
		get() = loadedParser.source

	override val sortOrders: Set<SortOrder>
		get() = call("getAvailableSortOrders") ?: setOf(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = call("getFilterCapabilities") ?: MangaListFilterCapabilities()

	override var defaultSortOrder: SortOrder
		get() = settings.defaultSortOrder?.takeIf { it in sortOrders }
			?: appSettings.defaultBrowseSortOrder(sortOrders, sortOrders.first())
		set(value) {
			settings.defaultSortOrder = value
		}

	val domain: String
		get() = call<String>("getDomain").orEmpty()

	val domains: Array<out String>
		get() = configKeyDomain?.presetValues ?: emptyArray()

	val configKeyDomain: ConfigKey.Domain?
		get() = call("getConfigKeyDomain")

	override fun intercept(chain: Interceptor.Chain): Response {
		return call("intercept", chain) ?: chain.proceed(chain.request())
	}

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		return callSuspend(
			"getList",
			offset,
			order ?: defaultSortOrder,
			filter ?: MangaListFilter.EMPTY,
		)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> =
		callSuspend("getPages", chapter)

	override suspend fun getPageUrl(page: MangaPage): String =
		callSuspend<String>("getPageUrl", page).also { result ->
			check(result.isNotEmpty()) { "Page url is empty" }
		}

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptionsLazy.get()

	suspend fun getFavicons(): Favicons = callSuspend("getFavicons")

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> =
		callSuspend("getRelatedManga", seed)

	override suspend fun getDetailsImpl(manga: Manga): Manga =
		callSuspend("getDetails", manga)

	fun getAuthProvider(): MangaParserAuthProvider? = delegate as? MangaParserAuthProvider

	fun getRequestHeaders(): Headers = call("getRequestHeaders") ?: Headers.Builder().build()

	fun getConfigKeys(): List<ConfigKey<*>> = ArrayList<ConfigKey<*>>().also {
		DynamicParserManager.invoke(delegate, "onCreateConfig", it)
	}

	fun getConfig(): SourceSettings = settings

	@Suppress("UNCHECKED_CAST")
	private fun <T> call(name: String, vararg args: Any?): T? =
		DynamicParserManager.invoke(delegate, name, *args) as? T

	@Suppress("UNCHECKED_CAST")
	private suspend fun <T> callSuspend(name: String, vararg args: Any?): T =
		DynamicParserManager.invokeSuspend(delegate, name, *args) as T
}
