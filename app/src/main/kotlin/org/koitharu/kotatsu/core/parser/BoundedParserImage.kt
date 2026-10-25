package org.koitharu.kotatsu.core.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.ResponseBody
import org.aomedia.avif.android.AvifDecoder
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.image.ReaderDecodeLimits
import org.koitharu.kotatsu.core.image.ReaderDecodeResources
import org.koitharu.kotatsu.core.util.ext.toMimeType
import java.io.IOException
import java.nio.ByteBuffer

/** Input-side guard for the newly added stitched-image adapter; never sample or change its coordinate system. */
internal fun <T> withBoundedParserImage(body: ResponseBody, lowRam: Boolean, block: (Bitmap) -> T): T =
    ReaderDecodeResources.withAllocationPermit {
        val limit = ReaderDecodeLimits.MAX_ENCODED_BYTES.toLong()
        if (body.contentLength() > limit || body.source().request(limit + 1)) {
            throw IOException("Parser image exceeds its encoded size limit")
        }
        val bytes = body.bytes()
        ReaderDecodeLimits.checkInterrupted()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)?.recycle()
        var width = bounds.outWidth
        var height = bounds.outHeight
        if ((width <= 0 || height <= 0) && body.contentType()?.subtype == "avif") {
            val info = AvifDecoder.Info()
            val buffer = ByteBuffer.wrap(bytes)
            if (!AvifDecoder.getInfo(buffer, bytes.size, info)) throw IOException("Invalid AVIF piece dimensions")
            width = info.width
            height = info.height
        }
        val working = ReaderDecodeLimits.workingBytes(width, height, lowRam)
        ReaderDecodeResources.reserve(working).use {
            ReaderDecodeLimits.checkInterrupted()
            val bitmap = BitmapDecoderCompat.decode(bytes.inputStream(), body.contentType()?.toMimeType(), isMutable = true)
            try {
                if (bitmap.width != width || bitmap.height != height) throw IOException("Parser image geometry changed during decode")
                block(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }
