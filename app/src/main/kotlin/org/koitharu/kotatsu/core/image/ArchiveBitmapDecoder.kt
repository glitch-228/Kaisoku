package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.koitharu.kotatsu.core.archive.ArchiveAccessException
import org.koitharu.kotatsu.core.archive.ArchiveImageUris
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import kotlin.math.ceil
import kotlin.math.sqrt

/** Reopenable encrypted streams, bounded bitmap allocation, and no file-backed conversion. */
object ArchiveBitmapDecoder {
    fun decode(context: Context, uri: Uri, thumbnail: Boolean = false): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ArchiveImageUris.openStream(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ReaderImageCompatibility.decode(context, uri, Bitmap.Config.ARGB_8888, true).use { it.takeBitmap() }
        }
        if (bounds.outWidth > 131_072 || bounds.outHeight > 131_072) throw ArchiveAccessException.Limit("Protected image dimensions exceed reader limits")
        val budget = if (context.isLowRamDevice()) 8L * 1024 * 1024 else 16L * 1024 * 1024
        val bytes = bounds.outWidth.toLong() * bounds.outHeight * 4
        val sample = if (thumbnail) ceil(sqrt(bytes.toDouble() / minOf(budget, 4L * 1024 * 1024))).toInt().coerceAtLeast(1) else 1
        if (!thumbnail && bytes > budget) throw ArchiveAccessException.Limit("Protected image is too large for full-page processing; use tiled reading")
        return ArchiveImageUris.openStream(context, uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }) ?: throw ArchiveAccessException.Invalid("Cannot decode protected archive image")
        }
    }
}
