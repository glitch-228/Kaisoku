package org.koitharu.kotatsu.core.image

import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.fetch.ImageFetchResult
import coil3.asImage
import coil3.request.Options
import coil3.toAndroidUri
import kotlinx.coroutines.runInterruptible
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isZipUri
import coil3.Uri as CoilUri
import org.koitharu.kotatsu.core.archive.ArchiveAccess
import org.koitharu.kotatsu.core.archive.ArchiveImageUris
import java.io.File

class CbzFetcher(
	private val uri: Uri,
	private val options: Options,
) : Fetcher {

	override suspend fun fetch() = runInterruptible {
		if (ArchiveImageUris.isSensitive(options.context, uri)) {
            // Returning an already decoded bitmap avoids Coil's seekable-source plaintext temp files.
            val bitmap = ArchiveBitmapDecoder.decode(options.context, uri, thumbnail = true)
            return@runInterruptible ImageFetchResult(bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
        }
		val filePath = uri.schemeSpecificPart.toPath()
		val entryName = requireNotNull(uri.fragment)
		val entryPath = entryName.toPath()
		val fs = options.fileSystem.openZip(filePath)
		val mimeType = fs.source(entryPath).buffer().inputStream().use { input ->
			BitmapDecoderCompat.probeMimeType(input, MimeTypes.getMimeTypeFromExtension(entryName))
		}
		SourceFetchResult(
			source = ImageSource(entryPath, fs),
			mimeType = mimeType?.toString(),
			dataSource = DataSource.DISK,
		)
	}

	class Factory : Fetcher.Factory<CoilUri> {

		override fun create(
			data: CoilUri,
			options: Options,
			imageLoader: ImageLoader
		): Fetcher? {
			val androidUri = data.toAndroidUri()
			return if (androidUri.isZipUri() || (androidUri.scheme == "content" && androidUri.authority == options.context.packageName + ".archive")) {
				CbzFetcher(androidUri, options)
			} else {
				null
			}
		}
	}
}
