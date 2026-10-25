package org.koitharu.kotatsu.core.parser

import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES
import org.koitharu.kotatsu.core.db.entity.ContentRating
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class MangaDataRepository @Inject constructor(
    private val db: MangaDatabase,
    private val resolverProvider: Provider<MangaLinkResolver>,
    private val appShortcutManagerProvider: Provider<AppShortcutManager>,
    private val metadataRefreshSettings: MetadataRefreshSettings,
    private val chapterReads: org.koitharu.kotatsu.history.exact.ChapterReadRepository,
    private val metadataJournal: org.koitharu.kotatsu.sync.extended.MetadataOverrideJournal =
        org.koitharu.kotatsu.sync.extended.MetadataOverrideJournal(db),
) {

    suspend fun saveReaderMode(manga: Manga, mode: ReaderMode) {
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
            db.getPreferencesDao().upsert(entity.copy(mode = mode.id))
        }
    }

    suspend fun saveColorFilter(manga: Manga, colorFilter: ReaderColorFilter?) {
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
            db.getPreferencesDao().upsert(
                entity.copy(
                    cfBrightness = colorFilter?.brightness ?: 0f,
                    cfContrast = colorFilter?.contrast ?: 0f,
                    cfInvert = colorFilter?.isInverted == true,
                    cfGrayscale = colorFilter?.isGrayscale == true,
                ),
            )
        }
    }

    suspend fun resetColorFilters() {
        db.getPreferencesDao().resetColorFilters()
    }

    suspend fun getReaderMode(mangaId: Long): ReaderMode? {
        return db.getPreferencesDao().find(mangaId)?.let { ReaderMode.valueOf(it.mode) }
    }

    suspend fun getColorFilter(mangaId: Long): ReaderColorFilter? {
        return db.getPreferencesDao().find(mangaId)?.getColorFilterOrNull()
    }

    suspend fun getOverride(mangaId: Long): MangaOverride? {
        return db.getPreferencesDao().find(mangaId)?.getOverrideOrNull()
    }

    suspend fun getOverrides(): LongObjectMap<MangaOverride> {
        val entities = db.getPreferencesDao().getOverrides()
        val map = MutableLongObjectMap<MangaOverride>(entities.size)
        for (entity in entities) {
            map[entity.mangaId] = entity.getOverrideOrNull() ?: continue
        }
        return map
    }

    suspend fun getOverrides(mangaIds: Collection<Long>): LongObjectMap<MangaOverride> {
        if (mangaIds.isEmpty()) {
            return MutableLongObjectMap(0)
        }
        val entities = db.getPreferencesDao().getOverrides(mangaIds.toLongArray())
        val map = MutableLongObjectMap<MangaOverride>(entities.size)
        for (entity in entities) {
            map[entity.mangaId] = entity.getOverrideOrNull() ?: continue
        }
        return map
    }

    suspend fun setOverride(manga: Manga, override: MangaOverride?) {
        require(!withContext(Dispatchers.IO) { org.koitharu.kotatsu.core.archive.ArchivePrivacy.isSensitive(manga) }) {
            "Protected archive metadata cannot be saved as a plaintext override"
        }
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val dao = db.getPreferencesDao()
            val entity = dao.find(manga.id) ?: newEntity(manga.id)
            val next = entity.copy(
                titleOverride = override?.title?.nullIfEmpty(),
                coverUrlOverride = override?.coverUrl?.nullIfEmpty(),
                contentRatingOverride = override?.contentRating?.name,
                descriptionOverride = override?.description?.nullIfEmpty(),
            )
            dao.upsert(next)
            metadataJournal.record(requireNotNull(db.getMangaDao().find(manga.id)).manga, next, explicitReset = true)
        }
    }

    fun observeColorFilter(mangaId: Long): Flow<ReaderColorFilter?> {
        return db.getPreferencesDao().observe(mangaId)
            .map { it?.getColorFilterOrNull() }
            .distinctUntilChanged()
    }

    suspend fun findMangaById(mangaId: Long, withChapters: Boolean): Manga? {
        val chapters = if (withChapters) {
            db.getChaptersDao().findAll(mangaId).takeUnless { it.isEmpty() }
        } else {
            null
        }
        return db.getMangaDao().find(mangaId)?.toManga(chapters)
    }

    suspend fun findMangaByIds(mangaIds: LongArray): List<Manga> =
        db.getMangaDao().findAll(mangaIds).toMangaList()

    suspend fun findMangaByPublicUrl(publicUrl: String): Manga? {
        return db.getMangaDao().findByPublicUrl(publicUrl)?.toManga()
    }

    suspend fun findAllMangaBySource(source: MangaSource): List<Manga> =
        db.getMangaDao().findAllBySource(source.name).toMangaList()

    suspend fun findLibraryMangaBySource(source: MangaSource): List<Manga> =
        db.getMangaDao().findLibraryBySource(source.name).toMangaList()

    suspend fun resolveIntent(intent: MangaIntent, withChapters: Boolean): Manga? = when {
        intent.manga != null -> intent.manga.withStoredIdIfNeeded().withCachedChaptersIfNeeded(withChapters)
        intent.mangaId != 0L -> findMangaById(intent.mangaId, withChapters)
        intent.uri != null -> resolverProvider.get().resolve(intent.uri).withCachedChaptersIfNeeded(withChapters)
        else -> null
    }

    suspend fun storeManga(manga: Manga, replaceExisting: Boolean) {
        if (!replaceExisting && db.getMangaDao().find(manga.id) != null) {
            return
        }
        val stored = withContext(Dispatchers.IO) { org.koitharu.kotatsu.core.archive.ArchivePrivacy.sanitize(manga) }
        db.withTransaction {
            // avoid storing local manga if remote one is already stored
            val existing = if (manga.isLocal) {
                db.getMangaDao().find(manga.id)?.manga
            } else {
                null
            }
            if (existing == null || existing.source == manga.source.name) {
                val tags = stored.tags.toEntities()
                db.getTagsDao().upsert(tags)
                db.getMangaDao().upsert(stored.toEntity(), tags)
                if (!manga.isLocal) {
                    manga.chapters?.let { chapters ->
                        db.getChaptersDao().replaceAll(manga.id, chapters.withIndex().toEntities(manga.id))
                    }
                }
            }
            chapterReads.refreshCounts(manga)
        }
    }

    suspend fun updateChapters(manga: Manga) {
        val chapters = manga.chapters
        if (!chapters.isNullOrEmpty() && manga.id in db.getMangaDao()) {
            db.getChaptersDao().replaceAll(manga.id, chapters.withIndex().toEntities(manga.id))
            chapterReads.refreshCounts(manga)
        }
    }

    suspend fun gcChaptersCache() {
        db.getChaptersDao().gc(metadataRefreshSettings.retainedMangaIds() + db.getChapterReadDao().retainedMangaIds())
    }

    suspend fun findTags(source: MangaSource): Set<MangaTag> {
        return db.getTagsDao().findTags(source.name).toMangaTags()
    }

    suspend fun cleanupLocalManga() {
        val dao = db.getMangaDao()
        val broken = dao.findAllBySource(LocalMangaSource.name)
            .filter { x -> x.manga.url.toUri().toFileOrNull()?.exists() == false }
        if (broken.isNotEmpty()) {
            dao.delete(broken.map { it.manga })
        }
    }

    suspend fun cleanupDatabase() {
        db.withTransaction {
            gcChaptersCache()
            val idsFromShortcuts = appShortcutManagerProvider.get().getMangaShortcuts()
            db.getMangaDao().cleanup(idsFromShortcuts + metadataRefreshSettings.retainedMangaIds() + db.getChapterReadDao().retainedMangaIds())
        }
    }

    fun observeOverridesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
        tables = arrayOf(TABLE_PREFERENCES),
        emitInitialState = emitInitialState,
    )

    fun observeFavoritesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
        tables = arrayOf(TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES),
        emitInitialState = emitInitialState,
    )

    private suspend fun Manga.withStoredIdIfNeeded(): Manga {
        return if (!isLocal && id != 0L) {
            db.getMangaDao().find(id)?.toManga() ?: this
        } else {
            this
        }
    }

    private suspend fun Manga.withStoredIdentityIfNeeded(): Manga {
        if (isLocal) {
            return this
        }
        if (id != 0L) {
            db.getMangaDao().find(id)?.toManga()?.let {
                return it
            }
        }
        return db.getMangaDao().findByIdentity(source.name, url, publicUrl)?.toManga() ?: this
    }

    private suspend fun Manga.withCachedChaptersIfNeeded(flag: Boolean): Manga = if (flag && !isLocal && chapters.isNullOrEmpty()) {
        val cachedChapters = db.getChaptersDao().findAll(id)
        if (cachedChapters.isEmpty()) {
            this
        } else {
            copy(chapters = cachedChapters.toMangaChapters())
        }
    } else {
        this
    }

    private fun MangaPrefsEntity.getColorFilterOrNull(): ReaderColorFilter? {
        return if (cfBrightness != 0f || cfContrast != 0f || cfInvert || cfGrayscale || cfBookEffect) {
            ReaderColorFilter(
                brightness = cfBrightness,
                contrast = cfContrast,
                isInverted = cfInvert,
                isGrayscale = cfGrayscale,
                isBookBackground = cfBookEffect
            )
        } else {
            null
        }
    }

    private fun MangaPrefsEntity.getOverrideOrNull(): MangaOverride? {
        return if (titleOverride.isNullOrEmpty() && coverUrlOverride.isNullOrEmpty() && contentRatingOverride.isNullOrEmpty() && descriptionOverride.isNullOrEmpty()) {
            null
        } else {
            MangaOverride(
                coverUrl = coverUrlOverride?.nullIfEmpty(),
                title = titleOverride?.nullIfEmpty(),
                contentRating = ContentRating(contentRatingOverride),
                description = descriptionOverride?.nullIfEmpty(),
            )
        }
    }

    private fun newEntity(mangaId: Long) = MangaPrefsEntity(
        mangaId = mangaId,
        mode = -1,
        cfBrightness = ReaderColorFilter.EMPTY.brightness,
        cfContrast = ReaderColorFilter.EMPTY.contrast,
        cfInvert = ReaderColorFilter.EMPTY.isInverted,
        cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
        cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
        titleOverride = null,
        coverUrlOverride = null,
        contentRatingOverride = null,
    )
}
