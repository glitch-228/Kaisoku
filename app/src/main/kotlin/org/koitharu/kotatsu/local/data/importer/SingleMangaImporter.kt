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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlin.math.sqrt
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
import org.koitharu.kotatsu.local.data.hasEpubExtension
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

    internal suspend fun import(uri: Uri): MangaImportResult<LocalManga> = withContext(Dispatchers.IO) {
        if (isDirectory(uri) && !isPdfUri(contentResolver, uri)) {
            val root = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Invalid document tree")
            val roots = root.findMangaDirs(contentResolver).ifEmpty { listOf(root) }
            importBatch(roots, { it.name.orEmpty() }, ::importMangaDirectory) { localStorageChanges.emit(it) }
        } else {
            importBatch(listOf(uri), { contentResolver.resolveName(it).orEmpty() }, ::importFile) { localStorageChanges.emit(it) }
        }
    }

    private suspend fun importFile(uri: Uri): LocalManga {
        val name = (contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch import name")).safeName()
        val pdf = hasPdfExtension(name) || isPdfUri(contentResolver, uri)
        val finalName = when {
            pdf -> name.substringBeforeLast('.').ifBlank { "PDF" }
            hasRarComicExtension(name) -> name.substringBeforeLast('.') + ".cbz"
            hasZipExtension(name) || hasEpubExtension(name) -> name
            else -> throw UnsupportedFileException("Unsupported file $name")
        }
        return ImportTransaction.run(getOutputDir(), finalName, { staging ->
            when {
                pdf -> File(staging, finalName).also { dir ->
                    if (!dir.mkdir()) throw IOException("Cannot create PDF directory")
                    renderPdf(uri) { index, bitmap ->
                        File(dir, "%04d.jpg".format(java.util.Locale.ROOT, index + 1)).outputStream().use { out ->
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("Cannot encode PDF page")
                        }
                    }
                }
                hasRarComicExtension(name) -> CbrImportTransaction.import(name, staging, copyInput = { copyFile(uri, it) })
                else -> File(staging, name).also { copyFile(uri, it) }
            }
        }, ::parseManga)
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

    private suspend fun renderPdf(uri: Uri, write: (Int, Bitmap) -> Unit) {
        val coroutineContext = currentCoroutineContext()
        runInterruptible(Dispatchers.IO) {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) throw IOException("PDF has no pages")
                    repeat(renderer.pageCount) { index ->
                        coroutineContext.ensureActive()
                        renderer.openPage(index).use { page ->
                            val size = pdfPageSize(page.width, page.height)
                            val bitmap = createBitmap(size.first, size.second)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                val matrix = Matrix().apply { setScale(size.first.toFloat() / page.width, size.second.toFloat() / page.height) }
                                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                coroutineContext.ensureActive()
                                write(index, bitmap)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            } ?: throw IOException("Cannot open PDF")
        }
    }

    private suspend fun importMangaDirectory(root: DocumentFile): LocalManga {
        val name = root.requireName()
        return ImportTransaction.run(getOutputDir(), name, { staging ->
            File(staging, name).also { dest ->
                if (!dest.mkdir()) throw IOException("Cannot create title directory")
                for (file in root.listFiles()) file.copyTo(dest)
            }
        }, ::parseManga, preparePublish = { prepared, target ->
            runInterruptible(Dispatchers.IO) { writeMihonIndex(prepared, target) }
        })
    }

    private suspend fun parseManga(file: File): LocalManga {
        val archives = org.koitharu.kotatsu.core.archive.ArchiveAccess.get(context)
        if (file.isFile && archives.isSensitive(file)) {
            // Import keeps ciphertext intact; a background service must never request/retain passwords.
            val entries = archives.entries(file)
            if (hasEpubExtension(file.name)) {
                if (entries.none { it.name == "mimetype" } || entries.none { it.name == "META-INF/container.xml" }) {
                    throw IOException("Encrypted EPUB package metadata is missing")
                }
            } else if (entries.none { !it.isDirectory && org.koitharu.kotatsu.local.data.hasImageExtension(it.name.substringAfterLast('/')) } &&
                !(entries.any { it.name == "index.json" } && entries.any { it.name.endsWith(".html", true) || it.name.endsWith(".htm", true) })) {
                throw IOException("Encrypted archive has no supported pages")
            }
            return LocalMangaParser(file).getManga(withDetails = false)
        }
        val parsed = LocalMangaParser(file).getManga(withDetails = true)
        if (parsed.manga.chapters.isNullOrEmpty()) throw IOException("Imported title has no readable chapters")
        return parsed.copy(manga = parsed.manga.copy(chapters = null))
    }

    private suspend fun DocumentFile.copyTo(destDir: File) {
        if (isImportJunk(name.orEmpty())) {
            return
        }
        currentCoroutineContext().ensureActive()
        if (!isDirectory && (hasPdfExtension(requireName()) || isPdfUri(contentResolver, uri))) {
            val name = requireName().substringBeforeLast('.') + ".cbz"
            val target = File(destDir, name)
            if (target.exists()) throw IOException("Conflicting PDF chapter name: $name")
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                renderPdf(uri) { index, bitmap ->
                    zip.putNextEntry(ZipEntry("%04d.jpg".format(java.util.Locale.ROOT, index + 1)))
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, zip)) throw IOException("Cannot encode PDF page")
                    zip.closeEntry()
                }
            }
        } else if (!isDirectory && hasRarComicExtension(requireName())) {
            val chapterName = requireName()
            val convertedName = chapterName.substringBeforeLast('.') + ".cbz"
            if (File(destDir, convertedName).exists()) throw IOException("Conflicting CBR chapter name: $convertedName")
            CbrImportTransaction.import(chapterName, destDir, copyInput = { copyFile(uri, it) })
        } else if (isDirectory) {
            val subDir = File(destDir, requireName())
            if (!subDir.mkdir()) throw IOException("Conflicting chapter directory")
            for (docFile in listFiles()) {
                docFile.copyTo(subDir)
            }
        } else {
            if (File(destDir, requireName()).exists()) throw IOException("Conflicting chapter name")
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
        return (name ?: throw IOException("Cannot fetch import name")).safeName()
    }

    private fun isDirectory(uri: Uri): Boolean {
        return runCatching { DocumentFile.fromTreeUri(context, uri) != null }.getOrDefault(false)
    }

    private fun String.safeName(): String = also {
        if (isBlank() || this == "." || this == ".." || '/' in this || '\\' in this) throw IOException("Invalid import name")
    }

    companion object { private const val JPEG_QUALITY = 85 }
}

/** Bounds a hostile PDF's dimensions to at most 4M pixels / 16MB for one temporary bitmap. */
internal fun pdfPageSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf((1200f / width).coerceAtMost(2f), sqrt(4_000_000.0 / width / height).toFloat(),
        16_384f / width, 16_384f / height)
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
