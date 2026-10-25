package org.koitharu.kotatsu.local.data.output

import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.deleteAwait
import org.koitharu.kotatsu.core.util.ext.readText
import org.koitharu.kotatsu.core.zip.ZipOutput
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class LocalMangaZipOutput(
	rootFile: File,
	manga: Manga,
) : LocalMangaOutput(rootFile) {

    init {
        if (rootFile.exists() && org.koitharu.kotatsu.core.archive.ArchiveRepository.inspectEntries(rootFile).any { it.isEncrypted }) {
            throw IOException("Protected archives are read-only; export a separate encrypted copy")
        }
    }

	private val output = ZipOutput(File(rootFile.path + ".tmp"))
	private val index = MangaIndex(null)
	private val mutex = Mutex()
	private val pendingChapters = HashMap<Long, IndexedValue<MangaChapter>>()
	private var completedChapters = 0
	private var isFinished = false

	init {
		if (!manga.isLocal) {
			index.setMangaInfo(manga)
		}
	}

	override suspend fun mergeWithExisting() = mutex.withLock {
		if (rootFile.exists()) {
			runInterruptible(Dispatchers.IO) {
				mergeWith(rootFile)
			}
		}
	}

	override suspend fun addCover(file: File, type: MimeType?) = mutex.withLock {
		val name = buildString {
			append(FILENAME_PATTERN.format(0, 0, 0))
			MimeTypes.getExtension(type)?.let { ext ->
				append('.')
				append(ext)
			}
		}
		runInterruptible(Dispatchers.IO) {
			output.put(name, file)
		}
		index.setCoverEntry(name)
	}

	override suspend fun addPage(chapter: IndexedValue<MangaChapter>, file: File, pageNumber: Int, type: MimeType?) =
		mutex.withLock {
			val name = buildString {
				append(FILENAME_PATTERN.format(chapter.value.branch.hashCode(), chapter.index + 1, pageNumber))
				MimeTypes.getExtension(type)?.let { ext ->
					append('.')
					append(ext)
				}
			}
			runInterruptible(Dispatchers.IO) {
				output.put(name, file)
			}
			pendingChapters[chapter.value.id] = chapter
		}

	override suspend fun flushChapter(chapter: MangaChapter): Boolean {
		mutex.withLock {
			pendingChapters.remove(chapter.id)?.let {
				index.addChapter(it, null)
				completedChapters++
			}
		}
		return false
	}

	override suspend fun finish() = mutex.withLock {
		finishImpl()
	}

	override suspend fun cleanup() = mutex.withLock {
		if (isFinished || completedChapters == 0) {
			output.file.deleteAwait()
			return@withLock
		}
		if (rootFile.exists()) {
			runInterruptible(Dispatchers.IO) { mergeWith(rootFile) }
		}
		finishImpl()
	}

	private suspend fun finishImpl() {
		isFinished = true
		runInterruptible(Dispatchers.IO) {
			output.use { output ->
				output.put(ENTRY_NAME_INDEX, index.toString())
				output.finish()
			}
		}
		rootFile.deleteAwait()
		output.file.renameTo(rootFile)
	}

	override fun close() {
		output.close()
	}

	@WorkerThread
	private fun mergeWith(other: File) {
		var otherIndex: MangaIndex? = null
		ZipFile(other).use { zip ->
			for (entry in zip.entries()) {
				if (entry.name == ENTRY_NAME_INDEX) {
					otherIndex = MangaIndex(
						zip.getInputStream(entry).use {
							it.reader().readText()
						},
					)
				} else {
					output.copyEntryFrom(zip, entry)
				}
			}
		}
		otherIndex?.getMangaInfo()?.chapters?.withIndex()?.let { chapters ->
			for (chapter in chapters) {
				index.addChapter(chapter, null)
			}
		}
	}

	companion object {

		private const val FILENAME_PATTERN = "%08d_%04d%04d"

		suspend fun filterChapters(file: File, manga: Manga, idsToRemove: Set<Long>) =
			runInterruptible(Dispatchers.IO) {
				val subject = LocalMangaZipOutput(file, manga)
				try {
					ZipFile(subject.rootFile).use { zip ->
						val indexEntry = zip.getEntry(ENTRY_NAME_INDEX)
							?: throw IOException("Archive does not contain $ENTRY_NAME_INDEX")
						val index = MangaIndex(zip.readText(indexEntry))
						idsToRemove.forEach { id -> index.removeChapter(id) }
						val chapters = index.getMangaInfo()?.chapters
							?: throw IOException("No chapters found in index")
						val patterns = chapters.map {
							index.getChapterNamesPattern(it)
						}
						val coverEntryName = index.getCoverEntry()
						for (entry in zip.entries()) {
							when {
								entry.name == ENTRY_NAME_INDEX -> {
									subject.output.put(ENTRY_NAME_INDEX, index.toString())
								}

								entry.isDirectory -> {
									subject.output.addDirectory(entry.name)
								}

								entry.name == coverEntryName -> {
									subject.output.copyEntryFrom(zip, entry)
								}

								else -> {
									val name = entry.name.substringBefore('.')
									if (patterns.any { it.matches(name) }) {
										subject.output.copyEntryFrom(zip, entry)
									}
								}
							}
						}
						subject.output.finish()
						subject.output.close()
						subject.rootFile.delete()
						subject.output.file.renameTo(subject.rootFile)
					}
				} catch (e: Throwable) {
					subject.closeQuietly()
					try {
						subject.output.file.delete()
					} catch (e2: Throwable) {
						e.addSuppressed(e2)
					}
					throw e
				}
			}
	}
}
