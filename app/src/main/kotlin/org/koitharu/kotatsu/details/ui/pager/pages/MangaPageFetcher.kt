package org.koitharu.kotatsu.details.ui.pager.pages

import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.request.Options
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.source
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.image.InkStoryImageDecoder
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.fetch
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isNetworkUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.toMimeType
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.mimeType
import org.koitharu.kotatsu.parsers.util.requireBody
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject

class MangaPageFetcher(
    private val okHttpClient: OkHttpClient,
    private val pagesCache: LocalStorageCache,
    private val options: Options,
    private val page: MangaPage,
    private val mangaRepositoryFactory: MangaRepository.Factory,
    private val imageProxyInterceptor: ImageProxyInterceptor,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (!page.preview.isNullOrEmpty()) {
            runCatchingCancellable {
                imageLoader.fetch(checkNotNull(page.preview), options)
            }.onSuccess {
                return it
            }
        }
        val repo = mangaRepositoryFactory.create(page.source)
        val pageUrl = repo.getPageUrl(page)
        if (org.koitharu.kotatsu.remote.library.LibraryImageReference.parse(pageUrl) != null) {
            return imageLoader.fetch(pageUrl, options)
        }
        if (options.diskCachePolicy.readEnabled) {
            pagesCache[pageUrl]?.let { file ->
                if (BitmapDecoderCompat.probeMimeType(file) == null) {
                    recoverInkStoryCacheFile(pageUrl, file)?.let { (decodedFile, mimeType) ->
                        return SourceFetchResult(
                            source = ImageSource(decodedFile.toOkioPath(), options.fileSystem),
                            mimeType = mimeType.toString(),
                            dataSource = DataSource.DISK,
                        )
                    }
                }
                val mimeType = BitmapDecoderCompat.probeMimeType(file)
                    ?: MimeTypes.getMimeTypeFromExtension(file.name)
                return SourceFetchResult(
                    source = ImageSource(file.toOkioPath(), options.fileSystem),
                    mimeType = mimeType?.toString(),
                    dataSource = DataSource.DISK,
                )
            }
        }
        return loadPage(repo, pageUrl)
    }

    private suspend fun loadPage(repo: MangaRepository, pageUrl: String): FetchResult? = if (pageUrl.toUri().isNetworkUri()) {
        fetchPage(repo, pageUrl)
    } else {
        runCatchingCancellable {
            imageLoader.fetch(pageUrl, options)
        }.getOrElse { error ->
            val (file, mimeType) = repairLocalPage(pageUrl) ?: throw error
            SourceFetchResult(
                source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
                mimeType = mimeType.toString(),
                dataSource = DataSource.DISK,
            )
        }
    }

    private suspend fun fetchPage(repo: MangaRepository, pageUrl: String): FetchResult {
        val request = repo.createPageRequest(pageUrl, page)
        val httpClient = repo.getImageClient() ?: okHttpClient
        return imageProxyInterceptor.interceptPageRequest(request, httpClient).use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.toNetworkResponse())
            }
            val file = response.requireBody().use { body ->
                val responseMimeType = body.contentType()?.toMimeType()
                if (InkStoryImageDecoder.isInkStorySource(page.source)) {
                    val payload = InkStoryImageDecoder.resolveNetworkPayload(
                        bytes = body.bytes(),
                        responseMimeType = responseMimeType,
                        pageUrl = pageUrl,
                        mangaSource = page.source,
                    ) ?: throw IllegalStateException("InkStory payload is not a supported image")
                    pagesCache.set(pageUrl, payload.bytes.inputStream().source(), payload.mimeType)
                } else {
                    pagesCache.set(pageUrl, body.source(), responseMimeType)
                }
            }
            val mimeType = BitmapDecoderCompat.probeMimeType(file)
                ?: MimeTypes.getMimeTypeFromExtension(file.name)
            SourceFetchResult(
                source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
                mimeType = mimeType?.toString(),
                dataSource = DataSource.NETWORK,
            )
        }
    }

    private suspend fun recoverInkStoryCacheFile(pageUrl: String, file: File): Pair<File, MimeType>? {
        val payload = InkStoryImageDecoder.resolveNetworkPayload(
            bytes = file.readBytes(),
            responseMimeType = MimeTypes.getMimeTypeFromExtension(file.name),
            pageUrl = pageUrl,
            mangaSource = page.source,
        ) ?: return null
        return pagesCache.set(pageUrl, payload.bytes.inputStream().source(), payload.mimeType) to payload.mimeType
    }

    private suspend fun repairLocalPage(pageUrl: String): Pair<File, MimeType>? {
        val uri = pageUrl.toUri()
        if (org.koitharu.kotatsu.core.archive.ArchiveImageUris.isSensitive(options.context, uri)) return null
        val bytes = when {
            uri.isZipUri() -> FileSystem.SYSTEM.openZip(uri.schemeSpecificPart.toPath()).use { zipFs ->
                val entryPath = ("/" + uri.fragment.orEmpty()).toPath()
                zipFs.source(entryPath).use { it.buffer().readByteArray() }
            }

            uri.isFileUri() -> File(requireNotNull(uri.path)).readBytes()
            else -> return null
        }
        val payload = InkStoryImageDecoder.resolveLocalPayload(bytes) ?: return null
        return pagesCache.set(pageUrl, payload.bytes.inputStream().source(), payload.mimeType) to payload.mimeType
    }

    private fun Response.toNetworkResponse() = NetworkResponse(
        code = code,
        requestMillis = sentRequestAtMillis,
        responseMillis = receivedResponseAtMillis,
        headers = headers.toNetworkHeaders(),
        body = body?.source()?.let(::NetworkResponseBody),
        delegate = this,
    )

    private fun Headers.toNetworkHeaders(): NetworkHeaders {
        val headers = NetworkHeaders.Builder()
        for ((key, values) in this) {
            headers.add(key, values)
        }
        return headers.build()
    }

    class Factory @Inject constructor(
        @MangaHttpClient private val okHttpClient: OkHttpClient,
        @PageCache private val pagesCache: LocalStorageCache,
        private val mangaRepositoryFactory: MangaRepository.Factory,
        private val imageProxyInterceptor: ImageProxyInterceptor,
    ) : Fetcher.Factory<MangaPage> {

        override fun create(data: MangaPage, options: Options, imageLoader: ImageLoader) = MangaPageFetcher(
            okHttpClient = okHttpClient,
            pagesCache = pagesCache,
            options = options,
            page = data,
            mangaRepositoryFactory = mangaRepositoryFactory,
            imageProxyInterceptor = imageProxyInterceptor,
            imageLoader = imageLoader,
        )
    }
}
