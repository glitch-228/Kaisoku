package org.koitharu.kotatsu.backups.mihon

import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.model.MangaState
import java.io.OutputStream
import javax.inject.Inject

/**
 * Exports the Kaisoku library to a Mihon `.tachibk`. A manga is included when its source maps to a
 * Mihon source — `mihon:pkg/id` directly, or a built-in/plugin source matched by site domain to an
 * installed Mihon extension; others are skipped. Chapters/reading-progress are not exported
 * (Kaisoku stores chapter ids, not urls, so they can't be reconstructed offline — Mihon refetches).
 */
@Reusable
class MihonBackupExporter @Inject constructor(
	private val database: MangaDatabase,
	private val sourceMatcher: MihonSourceMatcher,
) {

	suspend fun export(output: OutputStream) = withContext(Dispatchers.Default) {
		val favourites: List<FavouriteBackup> = database.getFavouritesDao().dump()
			.map { FavouriteBackup(it) }
			.toList()
		val categories: List<CategoryBackup> = database.getFavouriteCategoriesDao().findAll().map { CategoryBackup(it) }

		val categoryOrderById = HashMap<Long, Long>()
		val mihonCategories = categories.mapIndexed { index, c ->
			categoryOrderById[c.categoryId.toLong()] = index.toLong()
			MihonBackupCategory(name = c.title, order = index.toLong())
		}

		val usedSourceIds = LinkedHashSet<Long>()
		val mihonManga = ArrayList<MihonBackupManga>(favourites.size)
		for (f in favourites) {
			val mb = f.manga
			val mihonId = sourceMatcher.sourceNameToMihonId(mb.source, mb.publicUrl) ?: continue
			usedSourceIds += mihonId
			mihonManga += MihonBackupManga(
				source = mihonId,
				url = mb.url,
				title = mb.title,
				author = mb.authors,
				thumbnailUrl = mb.coverUrl,
				genre = mb.tags.map { it.title },
				status = mb.state.toMihonStatus(),
				favorite = true,
				categories = listOfNotNull(categoryOrderById[f.categoryId]),
			)
		}
		val mihonSources = usedSourceIds.map { id ->
			MihonBackupSource(name = sourceMatcher.mihonDisplayName(id).orEmpty(), sourceId = id)
		}
		MihonBackupCodec.encode(
			MihonBackup(
				backupManga = mihonManga,
				backupCategories = mihonCategories,
				backupSources = mihonSources,
			),
			output,
		)
	}

	private fun String?.toMihonStatus(): Int = when (this) {
		MangaState.ONGOING.name -> MIHON_ONGOING
		MangaState.FINISHED.name -> MIHON_COMPLETED
		MangaState.ABANDONED.name -> MIHON_CANCELLED
		MangaState.PAUSED.name -> MIHON_ON_HIATUS
		else -> MIHON_UNKNOWN
	}

	private companion object {
		const val MIHON_UNKNOWN = 0
		const val MIHON_ONGOING = 1
		const val MIHON_COMPLETED = 2
		const val MIHON_CANCELLED = 5
		const val MIHON_ON_HIATUS = 6
	}
}
