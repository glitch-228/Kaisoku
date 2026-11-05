package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import okio.BufferedSource
import okio.IOException
import okio.buffer
import okio.source
import org.aomedia.avif.android.AvifDecoder
import org.aomedia.avif.android.AvifDecoder.Info
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.readByteBuffer
import org.koitharu.kotatsu.core.util.ext.toByteBuffer
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

object BitmapDecoderCompat {

	private const val FORMAT_AVIF = "avif"
	private const val MIME_HEADER_SIZE = 32
	private const val AVIF_HEADER_SIZE = 64

	/**
	 * Whether [source] starts with an AVIF `ftyp` box, without consuming it. Sites serve AVIF under JPEG or WebP
	 * names and types, and HEIC shares the container, so only the brands tell them apart.
	 */
	fun isAvif(source: BufferedSource): Boolean {
		val peek = source.peek()
		peek.request(AVIF_HEADER_SIZE.toLong())
		val header = peek.readByteArray(minOf(AVIF_HEADER_SIZE.toLong(), peek.buffer.size))
		return ReaderImageHeader.detect(header, header.size) == ReaderImageHeader.Format.AVIF
	}

	@Blocking
	fun isAvif(file: File): Boolean = runCatchingCancellable {
		file.source().buffer().use { isAvif(it) }
	}.getOrDefault(false)

	@Blocking
	fun decode(file: File): Bitmap = when (val format = if (isAvif(file)) FORMAT_AVIF else probeMimeType(file)?.subtype) {
		FORMAT_AVIF -> file.source().buffer().use { decodeAvif(it.readByteBuffer()) }
		else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
		} else {
			checkBitmapNotNull(BitmapFactory.decodeFile(file.absolutePath), format)
		}
	}

	@Blocking
	fun decode(stream: InputStream, type: MimeType?, isMutable: Boolean = false): Bitmap {
		val format = type?.subtype
		if (format == FORMAT_AVIF) {
			return decodeAvif(stream.toByteBuffer())
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			val encoded = stream.toByteBuffer()
			if (AvifDecoder.isAvifImage(encoded)) {
				return decodeAvif(encoded)
			}
			val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
			val opts = BitmapFactory.Options()
			opts.inMutable = isMutable
			return checkBitmapNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts), format)
		}
		val byteBuffer = stream.toByteBuffer()
		return if (AvifDecoder.isAvifImage(byteBuffer)) {
			decodeAvif(byteBuffer)
		} else {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(byteBuffer), DecoderConfigListener(isMutable))
		}
	}

	@Blocking
	fun createRegionDecoder(inoutStream: InputStream): BitmapRegionDecoder? = try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			BitmapRegionDecoder.newInstance(inoutStream)
		} else {
			@Suppress("DEPRECATION")
			BitmapRegionDecoder.newInstance(inoutStream, false)
		}
	} catch (e: IOException) {
		e.printStackTraceDebug()
		null
	}

	@Blocking
	fun probeMimeType(file: File): MimeType? {
		return detectBitmapType(file) ?: if (isAvif(file)) MimeTypes.AVIF else MimeTypes.probeMimeType(file)
	}

	@Blocking
	fun probeMimeType(stream: InputStream, fallback: MimeType? = null): MimeType? {
		val bufferedStream = if (stream.markSupported()) stream else stream.buffered(MIME_HEADER_SIZE)
		bufferedStream.mark(MIME_HEADER_SIZE)
		return try {
			val header = ByteArray(MIME_HEADER_SIZE)
			val size = bufferedStream.read(header)
			if (size <= 0) {
				fallback
			} else {
				detectBitmapType(header.copyOf(size)) ?: fallback
			}
		} finally {
			bufferedStream.reset()
		}
	}

	@Blocking
	fun probeMimeType(bytes: ByteArray): MimeType? = detectBitmapType(bytes)

	@Blocking
	private fun detectBitmapType(file: File): MimeType? = runCatchingCancellable {
		val options = BitmapFactory.Options().apply {
			inJustDecodeBounds = true
		}
		BitmapFactory.decodeFile(file.path, options)?.recycle()
		options.outMimeType?.toMimeTypeOrNull()
	}.getOrNull()

	private fun checkBitmapNotNull(bitmap: Bitmap?, format: String?): Bitmap =
		bitmap ?: throw ImageDecodeException(null, format)

	private fun detectBitmapType(bytes: ByteArray): MimeType? {
		if (ReaderImageHeader.detect(bytes, minOf(bytes.size, AVIF_HEADER_SIZE)) == ReaderImageHeader.Format.AVIF) {
			return MimeTypes.AVIF
		}
		if (bytes.size >= 12
			&& bytes[0] == 'R'.code.toByte()
			&& bytes[1] == 'I'.code.toByte()
			&& bytes[2] == 'F'.code.toByte()
			&& bytes[3] == 'F'.code.toByte()
			&& bytes[8] == 'W'.code.toByte()
			&& bytes[9] == 'E'.code.toByte()
			&& bytes[10] == 'B'.code.toByte()
			&& bytes[11] == 'P'.code.toByte()
		) {
			return MimeType("image/webp")
		}
		if (bytes.size >= 3
			&& bytes[0] == 0xff.toByte()
			&& bytes[1] == 0xd8.toByte()
			&& bytes[2] == 0xff.toByte()
		) {
			return MimeType("image/jpeg")
		}
		if (bytes.size >= 8
			&& bytes[0] == 0x89.toByte()
			&& bytes[1] == 0x50.toByte()
			&& bytes[2] == 0x4e.toByte()
			&& bytes[3] == 0x47.toByte()
			&& bytes[4] == 0x0d.toByte()
			&& bytes[5] == 0x0a.toByte()
			&& bytes[6] == 0x1a.toByte()
			&& bytes[7] == 0x0a.toByte()
		) {
			return MimeType("image/png")
		}
		if (bytes.size >= 6
			&& bytes[0] == 'G'.code.toByte()
			&& bytes[1] == 'I'.code.toByte()
			&& bytes[2] == 'F'.code.toByte()
			&& bytes[3] == '8'.code.toByte()
			&& (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte())
			&& bytes[5] == 'a'.code.toByte()
		) {
			return MimeType("image/gif")
		}
		return null
	}

	private fun decodeAvif(bytes: ByteBuffer): Bitmap {
		val info = Info()
		if (!AvifDecoder.getInfo(bytes, bytes.remaining(), info)) {
			throw ImageDecodeException(
				null,
				FORMAT_AVIF,
				"Requested to decode byte buffer which cannot be handled by AvifDecoder",
			)
		}
		val config = if (info.depth == 8 || info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
		val bitmap = createBitmap(info.width, info.height, config)
		if (!AvifDecoder.decode(bytes, bytes.remaining(), bitmap)) {
			bitmap.recycle()
			throw ImageDecodeException(null, FORMAT_AVIF)
		}
		return bitmap
	}

	@RequiresApi(Build.VERSION_CODES.P)
	private class DecoderConfigListener(
		private val isMutable: Boolean,
	) : ImageDecoder.OnHeaderDecodedListener {

		override fun onHeaderDecoded(
			decoder: ImageDecoder,
			info: ImageDecoder.ImageInfo,
			source: ImageDecoder.Source
		) {
			decoder.isMutableRequired = isMutable
		}
	}
}
