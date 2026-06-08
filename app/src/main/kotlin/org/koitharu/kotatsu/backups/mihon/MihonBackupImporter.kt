package org.koitharu.kotatsu.backups.mihon

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.backups.data.model.HistoryBackup
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import java.io.File
import java.io.InputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Converts a Mihon `.tachibk` into a temporary Kaisoku backup ZIP, then hands it back to the normal
 * restore flow (so the source-remap UX, merge and identity-dedup all apply). Manga whose Mihon
 * source can't be resolved to an installed Kaisoku source (or matched by domain) are skipped.
 */
@Reusable
class MihonBackupImporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val sourceMatcher: MihonSourceMatcher,
	private val backupRepository: BackupRepository,
) {

	suspend fun import(input: InputStream): Uri = withContext(Dispatchers.Default) {
		val mihon = MihonBackupCodec.decode(input)
		val now = System.currentTimeMillis()
		// Some forks (e.g. Tadami) don't set the Mihon `favorite` flag on library entries, so a
		// whole-library export can have zero favorites. When nothing is flagged favorite, treat
		// every entry as a library item; otherwise honour the flag (standard Mihon behaviour).
		val treatAllAsLibrary = mihon.backupManga.none { it.favorite }
		val categories = ArrayList<CategoryBackup>(mihon.backupCategories.size + 1)
		val categoryIdByOrder = HashMap<Long, Long>()
		for (c in mihon.backupCategories) {
			val catId = c.name.hashCode() and 0x7fffffff
			categories += CategoryBackup(
				categoryId = catId,
				createdAt = now,
				sortKey = c.order.toInt(),
				title = c.name,
				order = ListSortOrder.NEWEST.name,
			)
			categoryIdByOrder[c.order] = catId.toLong()
		}
		var fallbackCategoryId = 0L
		val favourites = ArrayList<FavouriteBackup>()
		val history = ArrayList<HistoryBackup>()
		for (m in mihon.backupManga) {
			val inLibrary = m.favorite || treatAllAsLibrary
			val hasProgress = m.history.isNotEmpty() || m.chapters.any { it.read }
			if (!inLibrary && !hasProgress) continue
			val sourceName = sourceMatcher.mihonIdToSourceName(m.source) ?: continue
			val mangaId = kaisokuUid(sourceName, m.url)
			val mangaBackup = buildManga(m, sourceName, mangaId)
			if (inLibrary) {
				val categoryId = m.categories.firstNotNullOfOrNull { categoryIdByOrder[it] }
					?: ensureFallbackCategory(categories, now).also { fallbackCategoryId = it }
				favourites += FavouriteBackup(
					mangaId = mangaId,
					categoryId = categoryId,
					createdAt = now,
					manga = mangaBackup,
				)
			}
			buildHistory(m, sourceName, mangaId, mangaBackup)?.let { history += it }
		}
		writeTempZip(categories, favourites, history)
	}

	private fun buildManga(m: MihonBackupManga, sourceName: String, mangaId: Long): MangaBackup {
		val baseUrl = sourceMatcher.baseUrlOf(sourceName)
		val publicUrl = when {
			m.url.startsWith("http", ignoreCase = true) -> m.url
			baseUrl != null -> baseUrl.trimEnd('/') + "/" + m.url.trimStart('/')
			else -> m.url
		}
		val authors = listOfNotNull(m.author, m.artist).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }
		return MangaBackup(
			id = mangaId,
			title = m.title,
			url = m.url,
			publicUrl = publicUrl,
			rating = RATING_UNKNOWN,
			coverUrl = m.thumbnailUrl.orEmpty(),
			largeCoverUrl = m.thumbnailUrl,
			state = m.status.toMangaStateName(),
			authors = authors,
			source = sourceName,
		)
	}

	private fun buildHistory(
		m: MihonBackupManga,
		sourceName: String,
		mangaId: Long,
		manga: MangaBackup,
	): HistoryBackup? {
		val total = m.chapters.size
		val lastReadUrl = m.history.maxByOrNull { it.lastRead }?.url?.takeIf { it.isNotBlank() }
			?: m.chapters.filter { it.read }.maxByOrNull { it.sourceOrder }?.url?.takeIf { it.isNotBlank() }
			?: return null
		val chapter = m.chapters.firstOrNull { it.url == lastReadUrl }
		val readCount = m.chapters.count { it.read }
		val ts = m.history.maxOfOrNull { it.lastRead }?.takeIf { it > 0 } ?: System.currentTimeMillis()
		return HistoryBackup(
			mangaId = mangaId,
			createdAt = ts,
			updatedAt = ts,
			chapterId = kaisokuUid(sourceName, lastReadUrl),
			page = chapter?.lastPageRead?.toInt() ?: 0,
			scroll = 0f,
			percent = if (total > 0) readCount.toFloat() / total else PROGRESS_NONE,
			chaptersCount = total,
			manga = manga,
		)
	}

	private fun ensureFallbackCategory(categories: MutableList<CategoryBackup>, now: Long): Long {
		val id = FALLBACK_CATEGORY_ID
		if (categories.none { it.categoryId == id.toInt() }) {
			categories += CategoryBackup(
				categoryId = id.toInt(),
				createdAt = now,
				sortKey = 0,
				title = context.getString(R.string.mihon_import_category),
				order = ListSortOrder.NEWEST.name,
			)
		}
		return id
	}

	private suspend fun writeTempZip(
		categories: List<CategoryBackup>,
		favourites: List<FavouriteBackup>,
		history: List<HistoryBackup>,
	): Uri = withContext(Dispatchers.IO) {
		val file = File.createTempFile("mihon_import", ".zip", context.cacheDir)
		ZipOutputStream(file.outputStream().buffered()).use { zip ->
			backupRepository.writeBackup(zip, categories, favourites, history)
		}
		file.toUri()
	}

	private fun Int.toMangaStateName(): String? = when (this) {
		MIHON_ONGOING -> MangaState.ONGOING
		MIHON_COMPLETED, MIHON_PUBLISHING_FINISHED -> MangaState.FINISHED
		MIHON_CANCELLED -> MangaState.ABANDONED
		MIHON_ON_HIATUS -> MangaState.PAUSED
		else -> null
	}?.name

	private companion object {
		const val FALLBACK_CATEGORY_ID = 0x4D49484FL // "MIHO"
		const val MIHON_ONGOING = 1
		const val MIHON_COMPLETED = 2
		const val MIHON_PUBLISHING_FINISHED = 4
		const val MIHON_CANCELLED = 5
		const val MIHON_ON_HIATUS = 6
	}
}
