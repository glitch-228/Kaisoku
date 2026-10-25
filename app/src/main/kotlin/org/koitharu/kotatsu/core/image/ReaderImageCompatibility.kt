package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.createBitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import org.aomedia.avif.android.AvifDecoder
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/** Reopened streams retain raw file/ZIP coordinates; no conversion is written back to the source. */
internal class ReaderImageInput(private val context: Context, private val uri: Uri) {

    fun <T> read(block: (InputStream) -> T): T {
        ReaderDecodeLimits.checkInterrupted()
        return if (uri.isZipUri()) {
            if (org.koitharu.kotatsu.core.archive.ArchiveImageUris.isSensitive(context, uri)) {
                return org.koitharu.kotatsu.core.archive.ArchiveImageUris.openStream(context, uri).use(block)
            }
            ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = zip.getEntry(uri.fragment) ?: throw IOException("Missing reader image archive entry")
                zip.getInputStream(entry).use(block)
            }
        } else if (uri.scheme == "file" && uri.path.orEmpty().startsWith("/android_asset/")) {
            context.assets.open(uri.path.orEmpty().removePrefix("/android_asset/")).use(block)
        } else {
            (if (uri.scheme == "file") {
                File(uri.path ?: throw IOException("Missing reader image path")).inputStream()
            } else {
                context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open reader image")
            }).use(block)
        }
    }

    fun format(): ReaderImageHeader.Format? = read { input ->
        val header = ByteArray(64)
        var size = 0
        while (size < header.size) {
            val count = input.read(header, size, header.size - size)
            if (count < 0) break
            if (count == 0) throw IOException("Reader image stream made no progress")
            size += count
        }
        ReaderImageHeader.detect(header, size)
    }
}

internal class ReaderCompatibilityBitmap(
    val bitmap: Bitmap,
    private val reservation: Closeable,
) : Closeable {

    private var detached = false

    fun takeBitmap(): Bitmap {
        detached = true
        reservation.close()
        return bitmap
    }

    override fun close() {
        if (!detached) bitmap.recycle()
        reservation.close()
    }
}

internal object ReaderImageCompatibility {

    // libavif requires a direct buffer. Reuse one bounded allocation instead of relying on direct-buffer GC
    // between failed pages. Access is serialized by ReaderDecodeResources; bytes are cleared after every use.
    private var encodedBuffer: ByteBuffer? = null

    fun decode(
        context: Context,
        uri: Uri,
        bitmapConfig: Bitmap.Config,
        tryBitmapFactory: Boolean,
    ): ReaderCompatibilityBitmap = ReaderDecodeResources.withAllocationPermit {
        val input = ReaderImageInput(context, uri)
        when (input.format()) {
            ReaderImageHeader.Format.AVIF -> decodeAvif(context, input)
            ReaderImageHeader.Format.JPEG -> decodeJpeg(context, input, bitmapConfig, tryBitmapFactory)
            null -> throw IOException("No reader compatibility decoder for this image")
        }
    }

    fun tile(source: Bitmap, rect: Rect, sampleSize: Int, config: Bitmap.Config): Bitmap =
        ReaderDecodeResources.withAllocationPermit {
            require(sampleSize > 0 && rect.left >= 0 && rect.top >= 0 && !rect.isEmpty &&
                rect.right <= source.width && rect.bottom <= source.height)
            val width = ReaderDecodeLimits.sampledDimension(rect.width(), sampleSize)
            val height = ReaderDecodeLimits.sampledDimension(rect.height(), sampleSize)
            var output: Bitmap? = null
            var completed = false
            try {
                output = createBitmap(width, height, config)
                Canvas(output).drawBitmap(source, rect, Rect(0, 0, width, height), null)
                ReaderDecodeLimits.checkInterrupted()
                completed = true
                output
            } finally {
                if (!completed) output?.recycle()
            }
        }

    private fun decodeAvif(context: Context, input: ReaderImageInput): ReaderCompatibilityBitmap {
        return withEncoded(input) { encoded ->
            if (!AvifDecoder.isAvifImage(encoded)) throw IOException("Invalid reader AVIF signature")
            val info = AvifDecoder.Info()
            if (!AvifDecoder.getInfo(encoded, encoded.remaining(), info)) throw IOException("Invalid reader AVIF header")
            val bytes = ReaderDecodeLimits.workingBytes(info.width, info.height, context.isLowRamDevice())
            val reservation = ReaderDecodeResources.reserve(bytes)
            var bitmap: Bitmap? = null
            var completed = false
            try {
                // ARGB_8888 is supported for all AVIF depths. The reader's tile config is applied on output.
                bitmap = createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
                if (!AvifDecoder.decode(encoded, encoded.remaining(), bitmap, 1)) {
                    throw IOException("Cannot decode reader AVIF")
                }
                ReaderDecodeLimits.checkInterrupted()
                completed = true
                ReaderCompatibilityBitmap(bitmap, reservation)
            } finally {
                if (!completed) {
                    bitmap?.recycle()
                    reservation.close()
                }
            }
        }
    }

    private fun decodeJpeg(
        context: Context,
        input: ReaderImageInput,
        config: Bitmap.Config,
        tryBitmapFactory: Boolean,
    ): ReaderCompatibilityBitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.read { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outMimeType != "image/jpeg") throw IOException("Invalid reader JPEG header")
        val bytes = ReaderDecodeLimits.workingBytes(bounds.outWidth, bounds.outHeight, context.isLowRamDevice())
        val reservation = ReaderDecodeResources.reserve(bytes)
        var bitmap: Bitmap? = null
        var completed = false
        try {
            if (tryBitmapFactory) {
                bitmap = input.read {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inPreferredConfig = config
                        inScaled = false
                    })
                }
            }
            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ImageDecoder applies EXIF orientation; skip it unless raw and decoded coordinates are identical.
                val orientation = input.read {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
                if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                    throw IOException("Alternate JPEG decoder cannot preserve this EXIF orientation")
                }
                bitmap = decodePlatformJpeg(input, bounds.outWidth, bounds.outHeight)
            }
            val decoded = bitmap ?: throw IOException("Cannot decode reader JPEG")
            if (decoded.width != bounds.outWidth || decoded.height != bounds.outHeight) {
                throw IOException("Alternate JPEG decoder changed source dimensions")
            }
            ReaderDecodeLimits.checkInterrupted()
            completed = true
            return ReaderCompatibilityBitmap(decoded, reservation)
        } finally {
            if (!completed) {
                bitmap?.recycle()
                reservation.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodePlatformJpeg(input: ReaderImageInput, width: Int, height: Int): Bitmap =
        withEncoded(input) { encoded ->
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(encoded)) { decoder, info, _ ->
                if (info.size.width != width || info.size.height != height) {
                    throw IOException("Alternate JPEG decoder changed source dimensions")
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            }
        }

    private fun <T> withEncoded(input: ReaderImageInput, block: (ByteBuffer) -> T): T {
        val limit = minOf(ReaderDecodeLimits.MAX_ENCODED_BYTES.toLong(), ReaderDecodeResources.budget.capacity / 8).toInt()
        val encoded = encodedBuffer ?: run {
            val reservation = ReaderDecodeResources.reserve(limit.toLong())
            var retained = false
            try {
                ByteBuffer.allocateDirect(limit).also {
                    encodedBuffer = it
                    retained = true // Process-owned buffer and its budget reservation have the same lifetime.
                }
            } finally {
                if (!retained) reservation.close()
            }
        }
        val transfer = ByteArray(8192)
        try {
            encoded.clear()
            input.read { stream ->
                while (encoded.hasRemaining()) {
                    ReaderDecodeLimits.checkInterrupted()
                    val count = stream.read(transfer, 0, minOf(transfer.size, encoded.remaining()))
                    if (count < 0) break
                    if (count == 0) throw IOException("Reader image stream made no progress")
                    encoded.put(transfer, 0, count)
                }
                if (!encoded.hasRemaining() && stream.read() != -1) {
                    throw IOException("Image exceeds reader compatibility encoded-byte budget")
                }
            }
            ReaderDecodeLimits.checkInterrupted()
            encoded.flip()
            return block(encoded)
        } finally {
            transfer.fill(0)
            encoded.clear()
            while (encoded.hasRemaining()) {
                encoded.put(transfer, 0, minOf(transfer.size, encoded.remaining()))
            }
            encoded.clear()
        }
    }
}
