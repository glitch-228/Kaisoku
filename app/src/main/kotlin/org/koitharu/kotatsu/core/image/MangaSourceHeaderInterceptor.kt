package org.koitharu.kotatsu.core.image

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaRepository
import javax.inject.Inject
import javax.inject.Provider

class MangaSourceHeaderInterceptor @Inject constructor(
	private val repositoryFactory: Provider<MangaRepository.Factory>,
) : Interceptor {

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val mangaSource = chain.request.extras[mangaSourceKey]?.unwrap()
		if (mangaSource is LnReaderMangaSource) {
			val repo = repositoryFactory.get().create(mangaSource) as? LnReaderMangaRepository
				?: return chain.proceed()
			val headers = chain.request.httpHeaders.newBuilder()
			repo.getImageHeaders().forEach { (name, value) -> headers.set(name, value) }
			return chain.withRequest(chain.request.newBuilder().httpHeaders(headers.build()).build()).proceed()
		}
		val sourceName = when (mangaSource) {
			is MangaParserSource -> mangaSource.name
			is PluginMangaSource -> mangaSource.name
			else -> return chain.proceed()
		}
		val request = chain.request
		val newHeaders = request.httpHeaders.newBuilder()
			.set(CommonHeaders.MANGA_SOURCE, sourceName)
			.build()
		val newRequest = request.newBuilder()
			.httpHeaders(newHeaders)
			.build()
		return chain.withRequest(newRequest).proceed()
	}
}
