package org.koitharu.kotatsu.local.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.exceptions.UnsupportedFileException
import org.koitharu.kotatsu.core.util.ext.openSource
import org.koitharu.kotatsu.core.util.ext.resolveName
import org.koitharu.kotatsu.core.util.ext.writeAllCancellable
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.hasRarComicExtension
import org.koitharu.kotatsu.local.data.hasPdfExtension
import org.koitharu.kotatsu.local.data.hasZipExtension
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.isPdfUri
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import java.io.IOException
import javax.inject.Inject

@Reusable
class SingleMangaImporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val storageManager: LocalStorageManager,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {

	private val contentResolver = context.contentResolver

	suspend fun import(uri: Uri): List<LocalManga> {
		val result = if (isDirectory(uri) || isPdfUri(contentResolver, uri)) {
			importDirectory(uri)
		} else {
			listOf(importFile(uri))
		}
		for (manga in result) {
			localStorageChanges.emit(manga)
		}
		return result
	}

	private suspend fun importFile(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val contentResolver = storageManager.contentResolver
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		val dest = when {
			hasZipExtension(name) -> File(getOutputDir(), name).also { copyFile(uri, it) }
			hasRarComicExtension(name) -> importCbr(uri, name)
			else -> throw UnsupportedFileException("Unsupported file $name on $uri")
		}
		parseManga(dest)
	}

	private suspend fun importCbr(uri: Uri, sourceName: String): File {
		return CbrImportTransaction.import(
			sourceName = sourceName,
			outputDir = getOutputDir(),
			copyInput = { inputTemp -> copyFile(uri, inputTemp) },
		)
	}

	private suspend fun copyFile(uri: Uri, dest: File) {
		runInterruptible {
			storageManager.contentResolver.openSource(uri)
		}.use { source ->
			dest.sink().buffer().use { output ->
				output.writeAllCancellable(source)
			}
		}
	}

	private suspend fun importDirectory(uri: Uri): List<LocalManga> = withContext(Dispatchers.IO) {
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		val dirs = when {
			hasPdfExtension(name) || isPdfUri(contentResolver, uri) -> listOf(importPdf(uri, name))
			else -> importDocumentTree(uri)
		}
		dirs.map { parseManga(it) }
	}

	private suspend fun importPdf(uri: Uri, name: String): File {
		val outDir = File(getOutputDir(), name.substringBeforeLast('.')).also { it.mkdirs() }
		runInterruptible(Dispatchers.IO) {
			contentResolver.openFileDescriptor(uri, "r")?.use { pdf ->
				PdfRenderer(pdf).use { renderer ->
					repeat(renderer.pageCount) { i ->
						renderer.openPage(i).use { page -> renderPdfPage(page, i, outDir) }
					}
				}
			} ?: throw IOException("Cannot open descriptor: $uri")
		}
		return outDir
	}

	private fun renderPdfPage(page: PdfRenderer.Page, index: Int, outDir: File) {
		val scale = (TARGET_PAGE_WIDTH / page.width.toFloat()).coerceIn(1f, MAX_SCALE)
		val bitmap = createBitmap((page.width * scale).toInt(), (page.height * scale).toInt())
			.also { it.eraseColor(Color.WHITE) }
		try {
			val matrix = Matrix().apply { setScale(scale, scale) }
			page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
			File(outDir, "%04d.jpg".format(index + 1)).outputStream().use { out ->
				bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
			}
		} finally {
			bitmap.recycle()
		}
	}

	private suspend fun importDocumentTree(uri: Uri): List<File> {
		val root = DocumentFile.fromTreeUri(context, uri)
			?: throw IllegalArgumentException("Provided uri $uri is not a tree")
		// A Mihon downloads root or source folder holds many titles, each of which becomes its own manga
		val mangaDirs = root.findMangaDirs(contentResolver)
		if (mangaDirs.isNotEmpty()) {
			// Two sources can hold the same title, so each one needs a folder of its own here
			return mangaDirs.map { importMangaDirectory(it, unique = true) }
		}
		return listOf(importMangaDirectory(root, unique = false))
	}

	private suspend fun importMangaDirectory(root: DocumentFile, unique: Boolean): File {
		val dest = destinationDir(root.requireName(), unique)
		dest.mkdir()
		for (docFile in root.listFiles()) {
			docFile.copyTo(dest)
		}
		// No-op unless the folder came from Mihon and carries ComicInfo.xml
		runInterruptible(Dispatchers.IO) { writeMihonIndex(dest) }
		return dest
	}

	/**
	 * Re-importing the same folder keeps merging into it, as it always has. Only a batch import
	 * sidesteps a folder that is already taken, so two same-named titles do not end up merged.
	 */
	private suspend fun destinationDir(name: String, unique: Boolean): File {
		val outputDir = getOutputDir()
		val dest = File(outputDir, name)
		if (!unique || !dest.exists()) {
			return dest
		}
		var i = 1
		while (true) {
			val candidate = File(outputDir, "${name}_$i")
			if (!candidate.exists()) {
				return candidate
			}
			i++
		}
	}

	private suspend fun parseManga(file: File): LocalManga =
		LocalMangaParser(file).getManga(withDetails = false)

	private suspend fun DocumentFile.copyTo(destDir: File) {
		if (isImportJunk(name.orEmpty())) {
			return
		}
		if (isDirectory) {
			val subDir = File(destDir, requireName())
			subDir.mkdir()
			for (docFile in listFiles()) {
				docFile.copyTo(subDir)
			}
		} else {
			source().use { input ->
				File(destDir, requireName()).sink().buffer().use { output ->
					output.writeAllCancellable(input)
				}
			}
		}
	}

	private suspend fun getOutputDir(): File {
		return storageManager.getDefaultWriteableDir() ?: throw IOException("External files dir unavailable")
	}

	private suspend fun DocumentFile.source() = runInterruptible(Dispatchers.IO) {
		contentResolver.openSource(uri)
	}

	private fun DocumentFile.requireName(): String {
		return name ?: throw IOException("Cannot fetch name from uri: $uri")
	}

	private fun isDirectory(uri: Uri): Boolean {
		return runCatching {
			DocumentFile.fromTreeUri(context, uri)
		}.isSuccess
	}

	companion object {
		private const val TARGET_PAGE_WIDTH = 1200
		private const val MAX_SCALE = 2f
		private const val JPEG_QUALITY = 85
	}
}
