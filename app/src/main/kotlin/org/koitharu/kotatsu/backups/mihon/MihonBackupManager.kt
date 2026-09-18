package org.koitharu.kotatsu.backups.mihon

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.exceptions.BadBackupFormatException
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.mihonStableId
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Imports the library portions of a Mihon/Tachiyomi .tachibk backup. */
@Singleton
class MihonBackupManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val extensionManager: MihonExtensionManager,
) {

	data class RestoreReport(
		val restoredManga: Int,
		val restoredChapters: Int,
		val missingSources: List<String>,
		val restoredTracking: Int,
		val skippedItems: Int,
	)

	suspend fun restore(uri: Uri): RestoreReport = withContext(Dispatchers.IO) {
		val backup = decode(uri)
		val installedSources = extensionManager.getInstalledSources()
		val sourceNames = backup.sources.associate { source ->
			source.sourceId to (installedSources.firstOrNull { it.sourceId == source.sourceId }?.name
				?: "mihon:${source.sourceId}")
		}
		val missingSources = backup.sources
			.filter { source -> installedSources.none { it.sourceId == source.sourceId } }
			.map { it.name.ifBlank { "mihon:${it.sourceId}" } }
			.distinct()

		val categoryResolver = CategoryResolver(backup.categories)
		var restoredManga = 0
		var restoredChapters = 0
		var restoredTracking = 0
		var skipped = 0
		database.withTransaction {
			categoryResolver.prepare()
			for (item in backup.manga) {
				if (item.url.isBlank()) {
					skipped++
					continue
				}
				val source = sourceNames[item.source] ?: "mihon:${item.source}"
				val mangaId = mihonStableId(source, item.url)
				val chaptersFromBackup = item.chapters
					.sortedWith(compareByDescending<MihonBackupChapter> { it.sourceOrder }.thenBy { it.chapterNumber })
				val chapters = chaptersFromBackup.mapIndexed { index, chapter ->
					ChapterEntity(
						chapterId = mihonStableId(source, chapter.url.ifBlank { chapter.name.ifBlank { chapter.chapterNumber.toString() } }),
						mangaId = mangaId,
						title = chapter.name,
						number = chapter.chapterNumber,
						volume = 0,
						url = chapter.url,
						scanlator = chapter.scanlator,
						uploadDate = chapter.dateUpload,
						branch = null,
						source = source,
						index = index,
					)
				}
				val chapterByUrl = chapters.associateBy { it.url }
				val tags = item.genre.mapNotNull { raw ->
					val title = raw.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
					TagEntity(
						id = "$source:${title.lowercase(Locale.ROOT)}".longHashCode(),
						title = title,
						key = title.lowercase(Locale.ROOT),
						source = source,
						isPinned = false,
					)
				}
				database.getTagsDao().upsert(tags)
				database.getMangaDao().upsert(
					MangaEntity(
						id = mangaId,
						title = item.title.ifBlank { item.url },
						altTitles = null,
						url = item.url,
						publicUrl = item.url,
						rating = -1f,
						isNsfw = false,
						contentRating = null,
						coverUrl = item.thumbnailUrl.orEmpty(),
						largeCoverUrl = null,
						state = null,
						authors = listOfNotNull(item.author, item.artist).distinct().joinToString().ifBlank { null },
						source = source,
					),
					tags,
				)
				if (item.favorite) {
					categoryResolver.resolve(item.categories).forEachIndexed { index, categoryId ->
						database.getFavouritesDao().upsert(
							FavouriteEntity(
								mangaId = mangaId,
								categoryId = categoryId,
								sortKey = index,
								isPinned = false,
								createdAt = item.dateAdded.takeIf { it > 0 } ?: System.currentTimeMillis(),
								deletedAt = 0,
							),
						)
					}
				}
				database.getChaptersDao().replaceAll(mangaId, chapters)
				val latestHistory = item.history.maxByOrNull { it.lastRead }
				val currentChapter = latestHistory?.url?.let(chapterByUrl::get)
					?: chapters.lastOrNull { chapter ->
						chaptersFromBackup.firstOrNull { it.url == chapter.url }?.let { it.read || it.lastPageRead > 0 } == true
					}
				if (currentChapter != null) {
					val sourceChapter = chaptersFromBackup.firstOrNull { it.url == currentChapter.url }
					val timestamp = latestHistory?.lastRead?.takeIf { it > 0 }
						?: item.lastModifiedAt.takeIf { it > 0 }
						?: System.currentTimeMillis()
					database.getHistoryDao().upsert(
						HistoryEntity(
							mangaId = mangaId,
							createdAt = timestamp,
							updatedAt = timestamp,
							chapterId = currentChapter.chapterId,
							page = sourceChapter?.lastPageRead?.toInt()?.coerceAtLeast(0) ?: 0,
							scroll = 0f,
							percent = ((currentChapter.index + 1).toFloat() / chapters.size.coerceAtLeast(1)).coerceIn(0f, 1f),
							deletedAt = 0,
							chaptersCount = chapters.size,
						),
					)
				}
				val bookmarks = chaptersFromBackup.mapNotNull { sourceChapter ->
					if (!sourceChapter.bookmark) return@mapNotNull null
					val chapter = chapterByUrl[sourceChapter.url] ?: return@mapNotNull null
					val page = sourceChapter.lastPageRead.toInt().coerceAtLeast(0)
					BookmarkEntity(
						mangaId = mangaId,
						pageId = "$mangaId:${sourceChapter.url}:$page".longHashCode(),
						chapterId = chapter.chapterId,
						page = page,
						scroll = 0,
						imageUrl = "",
						createdAt = System.currentTimeMillis(),
						percent = 0f,
					)
				}
				if (bookmarks.isNotEmpty()) database.getBookmarksDao().upsert(bookmarks)
				item.tracking.forEach { tracking ->
					val service = trackerId(tracking.syncId) ?: return@forEach
					val remoteId = tracking.mediaId.takeIf { it > 0 } ?: tracking.libraryId.takeIf { it > 0 } ?: return@forEach
					database.getScrobblingDao().upsert(
						ScrobblingEntity(
							scrobbler = service,
							id = (tracking.libraryId.takeIf { it > 0 } ?: remoteId).toInt(),
							mangaId = mangaId,
							targetId = remoteId,
							status = mihonTrackingStatus(tracking.syncId, tracking.status),
							chapter = tracking.lastChapterRead.toInt().coerceAtLeast(0),
							comment = null,
							rating = (tracking.score / 10f).coerceIn(0f, 1f),
						),
					)
					restoredTracking++
				}
				restoredManga++
				restoredChapters += chapters.size
			}
		}
		RestoreReport(restoredManga, restoredChapters, missingSources, restoredTracking, skipped)
	}

	private fun decode(uri: Uri): MihonBackup {
		val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
			?: throw BadBackupFormatException(null)
		val payload = if (bytes.size >= 2 && bytes[0].toInt() and 0xff == 0x1f && bytes[1].toInt() and 0xff == 0x8b) {
			GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
				ByteArrayOutputStream().use { output -> input.copyTo(output); output.toByteArray() }
			}
		} else {
			bytes
		}
		return try {
			ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), payload)
		} catch (error: SerializationException) {
			throw BadBackupFormatException(error)
		}
	}

	private inner class CategoryResolver(private val categories: List<MihonBackupCategory>) {
		private val idsByOrder = mutableMapOf<Long, Long>()
		private var defaultCategory: Long? = null

		suspend fun prepare() {
			val dao = database.getFavouriteCategoriesDao()
			val existing = dao.findAll().associateBy { it.title }
			for (category in categories.sortedBy { it.order }) {
				val title = category.name.trim().takeIf { it.isNotEmpty() } ?: continue
				idsByOrder[category.order] = existing[title]?.categoryId?.toLong() ?: dao.insert(
					FavouriteCategoryEntity(
						categoryId = 0,
						createdAt = System.currentTimeMillis(),
						sortKey = dao.getNextSortKey(),
						title = title,
						order = "NEWEST",
						track = true,
						isVisibleInLibrary = true,
						deletedAt = 0,
					),
				)
			}
		}

		suspend fun resolve(orders: List<Long>): List<Long> {
			val ids = orders.mapNotNull(idsByOrder::get).distinct()
			if (ids.isNotEmpty()) return ids
			if (defaultCategory == null) {
				val dao = database.getFavouriteCategoriesDao()
				defaultCategory = dao.findAll().firstOrNull { it.title == DEFAULT_CATEGORY }?.categoryId?.toLong()
					?: dao.insert(
						FavouriteCategoryEntity(
							categoryId = 0,
							createdAt = System.currentTimeMillis(),
							sortKey = dao.getNextSortKey(),
							title = DEFAULT_CATEGORY,
							order = "NEWEST",
							track = true,
							isVisibleInLibrary = true,
							deletedAt = 0,
						),
					)
			}
			return listOfNotNull(defaultCategory)
		}
	}

	private fun trackerId(syncId: Int): Int? = when (syncId) {
		1 -> 3 // Mihon MAL -> Kaisoku MAL
		2 -> 2 // AniList
		3 -> 4 // Kitsu
		4 -> 1 // Shikimori
		else -> null
	}


	private companion object {
		const val DEFAULT_CATEGORY = "Default"
	}
}
