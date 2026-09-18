package org.koitharu.kotatsu.local.data.importer

import android.annotation.SuppressLint
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.BadRarArchiveException
import com.github.junrar.exception.CorruptHeaderException
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.HeaderNotInArchiveException
import com.github.junrar.exception.MainHeaderNullException
import com.github.junrar.exception.MissingNextVolumeException
import com.github.junrar.exception.MissingPreviousVolumeException
import com.github.junrar.exception.NotRarArchiveException
import com.github.junrar.exception.UnsafeLinkException
import com.github.junrar.exception.UnsupportedDictionarySizeException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarMethodException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.exception.WrongPasswordException
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.rarfile.HostSystem
import com.github.junrar.rarfile.rar5.Rar5RedirType
import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.core.exceptions.CbrImportException
import org.koitharu.kotatsu.core.util.AlphanumComparator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object RarToCbzConverter {

	private const val MAX_DICTIONARY_SIZE = 64L * 1024L * 1024L
	private const val FREE_SPACE_RESERVE = 16L * 1024L * 1024L
	private const val MAX_IMAGE_ENTRIES = 50_000
	private const val COPY_BUFFER_SIZE = 64 * 1024
	private const val UNIX_FILE_TYPE_MASK = 0xF000
	private const val UNIX_SYMLINK = 0xA000

	fun convert(
		input: File,
		output: File,
		isImage: (String) -> Boolean = ::isSupportedImagePath,
		isReadableImage: (File, String) -> Boolean = ::looksLikeReadableImage,
	): ConversionResult {
		require(input.isFile) { "Input is not a file: $input" }
		require(input != output) { "Input and output files must differ" }
		return try {
			convertChecked(input, output, isImage, isReadableImage)
		} catch (e: CancellationException) {
			throw e
		} catch (e: InterruptedIOException) {
			throw e
		} catch (e: CbrImportException) {
			throw e
		} catch (e: Exception) {
			throw CbrImportException(e.toCbrFailureReason(), e)
		}
	}

	private fun convertChecked(
		input: File,
		output: File,
		isImage: (String) -> Boolean,
		isReadableImage: (File, String) -> Boolean,
	): ConversionResult {
		val options = ArchiveOptions.builder().maxDictionarySize(MAX_DICTIONARY_SIZE).build()
		Archive(input, options).use { archive ->
			if (archive.isPasswordProtected) throw CbrImportException(CbrImportException.Reason.ENCRYPTED)
			if (archive.mainHeader?.isMultiVolume == true) {
				throw CbrImportException(CbrImportException.Reason.MULTIPART)
			}
			if (archive.hasBrokenHeaders()) throw CbrImportException(CbrImportException.Reason.CORRUPTED)

			val entries = collectImageEntries(archive.fileHeaders, isImage)
			ensureEnoughSpace(output, entries)
			output.parentFile?.mkdirs()
			ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
				zip.setLevel(Deflater.BEST_SPEED)
				for (entry in entries) writeEntry(archive, entry, output, zip, isReadableImage)
			}
			return ConversionResult(entries.size, output.length())
		}
	}

	private fun collectImageEntries(
		headers: List<FileHeader>,
		isImage: (String) -> Boolean,
	): List<ArchiveImageEntry> {
		val result = ArrayList<ArchiveImageEntry>()
		val pathValidator = ArchiveEntryPathValidator()
		for (header in headers) {
			checkInterrupted()
			if (header.isSplitBefore || header.isSplitAfter) {
				throw CbrImportException(CbrImportException.Reason.MULTIPART)
			}
			if (header.isEncrypted) throw CbrImportException(CbrImportException.Reason.ENCRYPTED)
			if (header.isDirectory) continue
			if (header.isLink()) throw CbrImportException(CbrImportException.Reason.UNSAFE)
			val path = pathValidator.validate(header.fileName)
			if (!isImage(path)) continue
			if (result.size == MAX_IMAGE_ENTRIES) {
				throw CbrImportException(CbrImportException.Reason.UNSUPPORTED)
			}
			result += ArchiveImageEntry(header, path)
		}
		if (result.isEmpty()) throw CbrImportException(CbrImportException.Reason.EMPTY)
		result.sortWith(compareBy(AlphanumComparator()) { it.path })
		return result
	}

	private fun writeEntry(
		archive: Archive,
		entry: ArchiveImageEntry,
		output: File,
		zip: ZipOutputStream,
		isReadableImage: (File, String) -> Boolean,
	) {
		val parent = requireNotNull(output.absoluteFile.parentFile)
		val pageTemp = File(parent, ".cbr-page-${UUID.randomUUID()}.part")
		try {
			BufferedOutputStream(FileOutputStream(pageTemp)).use { pageOutput ->
				archive.extractFile(entry.header, InterruptibleOutputStream(pageOutput))
			}
			checkInterrupted()
			if (!isReadableImage(pageTemp, entry.path)) {
				throw CbrImportException(CbrImportException.Reason.CORRUPTED)
			}
			zip.putNextEntry(ZipEntry(entry.path).apply { time = 0L })
			try {
				val buffer = ByteArray(COPY_BUFFER_SIZE)
				BufferedInputStream(FileInputStream(pageTemp)).use { input ->
					while (true) {
						checkInterrupted()
						val count = input.read(buffer)
						if (count < 0) break
						zip.write(buffer, 0, count)
					}
				}
			} finally {
				zip.closeEntry()
			}
		} finally {
			pageTemp.delete()
		}
	}

	@SuppressLint("UsableSpace") // Check the selected volume conservatively; importing must not evict other apps' caches.
	private fun ensureEnoughSpace(output: File, entries: List<ArchiveImageEntry>) {
		val unpacked = entries.fold(0L) { total, entry ->
			val size = entry.header.fullUnpackSize.coerceAtLeast(0L)
			if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
		}
		val required = if (unpacked > Long.MAX_VALUE / 2L) Long.MAX_VALUE else unpacked * 2L
		val available = output.parentFile?.usableSpace ?: output.usableSpace
		if (!hasEnoughSpace(required, available)) throw CbrImportException(CbrImportException.Reason.NO_SPACE)
	}

	internal fun hasEnoughSpace(required: Long, available: Long): Boolean =
		required >= 0L && required != Long.MAX_VALUE &&
			available >= FREE_SPACE_RESERVE && required <= available - FREE_SPACE_RESERVE

	private fun FileHeader.isLink(): Boolean {
		val redirectionType = redirection?.type
		if (redirectionType != null && redirectionType != Rar5RedirType.NONE) return true
		return hostOS == HostSystem.unix && fileAttr and UNIX_FILE_TYPE_MASK == UNIX_SYMLINK
	}

	private fun Exception.toCbrFailureReason(): CbrImportException.Reason = when (this) {
		is WrongPasswordException, is UnsupportedRarEncryptedException -> CbrImportException.Reason.ENCRYPTED
		is MissingNextVolumeException, is MissingPreviousVolumeException -> CbrImportException.Reason.MULTIPART
		is UnsupportedDictionarySizeException -> CbrImportException.Reason.TOO_LARGE_DICTIONARY
		is UnsafeLinkException -> CbrImportException.Reason.UNSAFE
		is UnsupportedRarMethodException, is UnsupportedRarVersionException -> CbrImportException.Reason.UNSUPPORTED
		is NotRarArchiveException, is BadRarArchiveException, is CorruptHeaderException, is CrcErrorException,
		is HeaderNotInArchiveException, is MainHeaderNullException, is IOException -> CbrImportException.Reason.CORRUPTED
		else -> CbrImportException.Reason.CORRUPTED
	}

	private fun looksLikeReadableImage(file: File, path: String): Boolean {
		val header = ByteArray(512)
		val length = FileInputStream(file).use { it.read(header) }
		if (length <= 0) return false
		fun startsWith(vararg bytes: Int): Boolean =
			length >= bytes.size && bytes.indices.all { header[it].toInt() and 0xFF == bytes[it] }
		return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
			"png" -> startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
			"jpg", "jpeg", "jpe", "jif", "jfif" -> startsWith(0xFF, 0xD8, 0xFF)
			"gif" -> length >= 6 && (String(header, 0, 6, StandardCharsets.US_ASCII) == "GIF87a" ||
				String(header, 0, 6, StandardCharsets.US_ASCII) == "GIF89a")
			"bmp" -> startsWith(0x42, 0x4D)
			"webp" -> length >= 12 && String(header, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
				String(header, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
			"avif", "heic", "heif" -> length >= 12 && String(header, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
			"svg" -> String(header, 0, length, StandardCharsets.UTF_8)
				.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
				.let { it.startsWith("<svg", ignoreCase = true) ||
					(it.startsWith("<?xml", ignoreCase = true) && it.contains("<svg", ignoreCase = true)) }
			else -> false
		}
	}

	private fun checkInterrupted() {
		if (Thread.currentThread().isInterrupted) throw InterruptedIOException("CBR import cancelled")
	}

	internal data class ConversionResult(val imageCount: Int, val outputSize: Long)

	private data class ArchiveImageEntry(val header: FileHeader, val path: String)

	internal fun isSupportedImagePath(path: String): Boolean =
		path.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT) in SUPPORTED_IMAGE_EXTENSIONS

	private class InterruptibleOutputStream(private val delegate: OutputStream) : OutputStream() {
		override fun write(value: Int) {
			checkInterrupted()
			delegate.write(value)
		}

		override fun write(buffer: ByteArray, offset: Int, length: Int) {
			checkInterrupted()
			delegate.write(buffer, offset, length)
		}

		private fun checkInterrupted() {
			if (Thread.currentThread().isInterrupted) throw InterruptedIOException("CBR import cancelled")
		}
	}

	private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
		"avif", "bmp", "gif", "heic", "heif", "jif", "jfif", "jpe", "jpeg", "jpg", "png", "svg", "webp",
	)
}

internal class ArchiveEntryPathValidator {

	private val normalizedPaths = HashSet<String>()

	fun validate(rawPath: String): String {
		if (rawPath.isBlank() || rawPath.indexOf('\u0000') >= 0) {
			throw CbrImportException(CbrImportException.Reason.UNSAFE)
		}
		val path = rawPath.replace('\\', '/')
		if (path.startsWith('/') || WINDOWS_ABSOLUTE_PATH.matches(path)) {
			throw CbrImportException(CbrImportException.Reason.UNSAFE)
		}
		val segments = path.split('/')
		if (segments.any(::isUnsafeComponent)) throw CbrImportException(CbrImportException.Reason.UNSAFE)
		val normalized = segments.joinToString("/")
		if (!normalizedPaths.add(normalized.lowercase(Locale.ROOT))) {
			throw CbrImportException(CbrImportException.Reason.UNSAFE)
		}
		return normalized
	}

	private fun isUnsafeComponent(component: String): Boolean {
		if (component.isBlank() || component == "." || component == ".." || component != component.trim() ||
			component.endsWith('.') || ILLEGAL_CHARS.containsMatchIn(component)
		) return true
		return component.substringBeforeLast('.').uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES
	}

	private companion object {
		val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/.*")
		val ILLEGAL_CHARS = Regex("[<>:\"|?*\\u0000-\\u001F]")
		val WINDOWS_RESERVED_NAMES = buildSet {
			addAll(listOf("CON", "PRN", "AUX", "NUL"))
			for (index in 1..9) {
				add("COM$index")
				add("LPT$index")
			}
		}
	}
}
