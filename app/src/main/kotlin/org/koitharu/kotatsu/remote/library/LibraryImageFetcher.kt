package org.koitharu.kotatsu.remote.library

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.FileSystem
import okio.ForwardingSource
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.local.data.LocalStorageCache
import java.io.File
import javax.inject.Inject

/** Private-server covers and pages never pass through the website image proxy/shared HTTP client. */
class LibraryImageFetcher(
    private val manager: RemoteLibraryManager,
    private val cache: LocalStorageCache,
    private val reference: LibraryImageReference,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        val active = manager.session(reference.connectionId)
        if (active.connection.cacheScope != reference.scope) throw LibraryAuthenticationException()
        // Apply connection/content policy even when an older image is already cached.
        val publication = active.publication(reference.publicationId)
        val url = if (reference.pageIndex == null) publication.cover?.href
            ?: throw RemoteLibraryException("The publication has no supported cover") else active.page(publication, reference.pageIndex).url
        val key = reference.toString()
        if (options.diskCachePolicy.readEnabled) cache[key]?.let { file ->
            BitmapDecoderCompat.probeMimeType(file)?.let { mime ->
                return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), mime.toString(), DataSource.DISK)
            }
        }
        val root = File(options.context.cacheDir, "library_images")
        if (!root.isDirectory && !root.mkdirs()) throw RemoteLibraryException("Library image staging is unavailable")
        val stage = File(root, "image-${java.util.UUID.randomUUID()}")
        var ownedBySource = false
        try {
            active.transport.image(url, stage)
            val mime = BitmapDecoderCompat.probeMimeType(stage) ?: throw RemoteLibraryException("The library returned content other than an image")
            if (options.diskCachePolicy.writeEnabled) {
                val cached = stage.source().use { source -> cache.set(key, source, mime) }
                return SourceFetchResult(ImageSource(cached.toOkioPath(), FileSystem.SYSTEM), mime.toString(), DataSource.NETWORK)
            }
            // Coil closes the source on decode/cancellation. A decoder can obtain its own seekable
            // temporary copy through ImageSource without leaking our original staging file.
            val source = object : ForwardingSource(stage.source()) {
                override fun close() { try { super.close() } finally { stage.delete() } }
            }.buffer()
            val result = SourceFetchResult(ImageSource(source, FileSystem.SYSTEM), mime.toString(), DataSource.NETWORK)
            ownedBySource = true
            return result
        } finally { if (!ownedBySource) stage.delete() }
    }

    class Factory @Inject constructor(private val manager: RemoteLibraryManager, private val privateCache: RemoteLibraryImageCache) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            LibraryImageReference.parse(data.toString())?.let { LibraryImageFetcher(manager, privateCache.cache, it, options) }
    }
}
