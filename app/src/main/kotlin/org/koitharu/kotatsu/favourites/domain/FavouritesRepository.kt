package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.toFavouriteCategory
import org.koitharu.kotatsu.favourites.data.toMangaList
import org.koitharu.kotatsu.favourites.domain.model.Cover
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.search.domain.SearchKind
import javax.inject.Inject
import java.time.Instant

@Reusable
class FavouritesRepository @Inject constructor(
    private val db: MangaDatabase,
    private val localObserver: LocalFavoritesObserver,
    private val settings: AppSettings,
    private val metadataRefreshSettings: MetadataRefreshSettings,
    private val libraryContent: org.koitharu.kotatsu.remote.library.LibraryListContentPolicies,
) {
    fun observeMembershipChanges(): Flow<Unit> = db.invalidationTracker.createFlow(TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES)
        .map { Unit }

    suspend fun getVisibleCategories(ids: Collection<Long>): Map<Long, List<FavouriteCategory>> =
        ids.distinct().chunked(400).flatMap { db.getFavouritesDao().searchCategories(it) }
            .groupBy { it.mangaId }.mapValues { (_, rows) -> rows.map { it.category.toFavouriteCategory() } }

    suspend fun getAllManga(): List<Manga> {
        val entities = db.getFavouritesDao().findAll()
        return entities.toMangaList()
    }

    suspend fun getLastManga(limit: Int): List<Manga> {
        val entities = db.getFavouritesDao().findLast(limit)
        return entities.toMangaList()
    }

    suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
        val dao = db.getFavouritesDao()
        val q = "%$query%"
        val entities = when (kind) {
            SearchKind.SIMPLE,
            SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

            SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
            SearchKind.TAG -> dao.searchByTag(q, limit)
        }
        return entities.toMangaList()
    }

    fun observeAll(order: ListSortOrder, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Manga>> =
        libraryContent.observe().flatMapLatest { policy ->
            if (ListFilterOption.Downloaded in filterOptions) localObserver.observeAll(order, filterOptions, limit, policy)
            else db.getFavouritesDao().observeAll(order, filterOptions, limit, policy).map { it.toMangaList() }
        }

    suspend fun getManga(categoryId: Long): List<Manga> {
        val entities = db.getFavouritesDao().findAll(categoryId)
        return entities.toMangaList()
    }

    fun observeAll(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        limit: Int
    ): Flow<List<Manga>> = libraryContent.observe().flatMapLatest { policy ->
        if (ListFilterOption.Downloaded in filterOptions) localObserver.observeAll(categoryId, order, filterOptions, limit, policy)
        else db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit, policy).map { it.toMangaList() }
    }

    fun observeAll(categoryId: Long, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Manga>> {
        return observeOrder(categoryId)
            .flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit) }
    }

    fun observeMangaCount(): Flow<Int> {
        return db.getFavouritesDao().observeMangaCount()
            .distinctUntilChanged()
    }

    fun observeCategories(): Flow<List<FavouriteCategory>> {
        return db.getFavouriteCategoriesDao().observeAll().mapItems {
            it.toFavouriteCategory()
        }.distinctUntilChanged()
    }

    fun observeCategoriesForLibrary(): Flow<List<FavouriteCategory>> {
        return db.getFavouriteCategoriesDao().observeAllVisible().mapItems {
            it.toFavouriteCategory()
        }.distinctUntilChanged()
    }

    fun observeCategoryOverview(): Flow<List<CategoryOverview>> = combine(
        db.invalidationTracker.createFlow(TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES, "manga", "preferences"),
        settings.observeChanges().onStart { emit(null) },
        libraryContent.observe(),
    ) { _, _, policy -> policy }.mapLatest { policy ->
        db.withTransaction {
            val categories = db.getFavouriteCategoriesDao().findAll().filter { it.isVisibleInLibrary }
            val dao = db.getFavouritesDao()
            val counts = dao.categoryCounts(settings.isNsfwContentDisabled, policy).associate { it.category_id to it.count }
            val sorted = sortFavoriteCategories(categories.map {
                CategorySortKey(it.categoryId.toLong(), it.title, it.sortKey, it.createdAt, counts[it.categoryId.toLong()] ?: 0)
            }, settings.favoritesCategorySort, settings.isFavoritesCategorySortAscending)
            buildList {
                if (categories.isNotEmpty() && settings.isAllFavouritesVisible) {
                    add(CategoryOverview(0, null, dao.visibleFavoritesCount(settings.isNsfwContentDisabled, policy),
                        if (settings.isFavoritesOverviewEnabled) dao.categoryPreviewCovers(0, settings.isNsfwContentDisabled, 4, policy) else emptyList()))
                }
                for (key in sorted) add(CategoryOverview(key.id, key.title, key.count,
                    if (settings.isFavoritesOverviewEnabled) dao.categoryPreviewCovers(key.id, settings.isNsfwContentDisabled, 4, policy) else emptyList()))
            }
        }
    }.distinctUntilChanged()

    fun observeCategoriesWithCovers(): Flow<Map<FavouriteCategory, List<Cover>>> {
        return combine(
            db.invalidationTracker.createFlow(TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES, "manga", "preferences", emitInitialState = true),
            settings.observeChanges().filter {
                it == null || it == AppSettings.KEY_FAVORITES_ORDER || it == AppSettings.KEY_FAVORITES_DEFAULT_CATEGORIES ||
                    it == AppSettings.KEY_DISABLE_NSFW
            }.onStart { emit(null) },
            libraryContent.observe(),
        ) { _, _, policy -> policy }.mapLatest { policy ->
            db.withTransaction {
                val categories = db.getFavouriteCategoriesDao().findAll()
                val res = LinkedHashMap<FavouriteCategory, List<Cover>>(categories.size)
                for (entity in categories) {
                    val cat = entity.toFavouriteCategory()
                    res[cat] = db.getFavouritesDao().findCovers(
                        categoryId = cat.id,
                        order = effectiveOrder(cat.id, cat.order),
                        contentPolicy = policy,
                        hideAdult = settings.isNsfwContentDisabled,
                    )
                }
                res
            }
        }.distinctUntilChanged()
    }

    suspend fun getAllFavoritesCovers(order: ListSortOrder, limit: Int): List<Cover> {
        return db.getFavouritesDao().findCovers(order, limit, libraryContent.observe().first(), settings.isNsfwContentDisabled)
    }

    fun observeCategory(id: Long): Flow<FavouriteCategory?> {
        return db.getFavouriteCategoriesDao().observe(id)
            .map { it?.toFavouriteCategory() }
    }

    fun observeCategoriesIds(mangaId: Long): Flow<Set<Long>> {
        return db.getFavouritesDao().observeIds(mangaId).map { it.toSet() }
    }

    fun observeCategories(mangaId: Long): Flow<Set<FavouriteCategory>> {
        return db.getFavouritesDao().observeCategories(mangaId).map {
            it.mapTo(LinkedHashSet(it.size)) { x -> x.toFavouriteCategory() }
        }
    }

    fun observeFavoriteDate(mangaId: Long): Flow<Long?> {
        return db.getFavouritesDao().observeFavoriteDate(mangaId).distinctUntilChanged()
    }

    suspend fun getCategory(id: Long): FavouriteCategory {
        return db.getFavouriteCategoriesDao().find(id.toInt()).toFavouriteCategory()
    }

    suspend fun isFavorite(mangaId: Long): Boolean {
        return db.getFavouritesDao().findCategoriesCount(mangaId) != 0
    }

    suspend fun getFavoriteIds(mangaIds: Collection<Long>): Set<Long> {
        if (mangaIds.isEmpty()) {
            return emptySet()
        }
        return db.getFavouritesDao().findExistingMangaIds(mangaIds.toLongArray()).toSet()
    }

    suspend fun getCategoriesIds(mangaId: Long): Set<Long> {
        return db.getFavouritesDao().findCategoriesIds(mangaId).toSet()
    }

    suspend fun getCategoriesAddedAt(mangaId: Long): Map<Long, Instant> {
        return db.getFavouritesDao().findAllRaw(mangaId).associate { entity ->
            entity.categoryId to Instant.ofEpochMilli(entity.createdAt)
        }
    }

    suspend fun findPopularSources(categoryId: Long, limit: Int): List<MangaSource> {
        return db.getFavouritesDao().run {
            if (categoryId == 0L) {
                findPopularSources(limit)
            } else {
                findPopularSources(categoryId, limit)
            }
        }.toMangaSources()
    }

    suspend fun findPopularTagTitles(categoryId: Long, limit: Int): List<String> {
        return db.getFavouritesDao().run {
            if (categoryId == 0L) {
                findPopularTagTitles(limit)
            } else {
                findPopularTagTitles(categoryId, limit)
            }
        }
    }

    suspend fun createCategory(
        title: String,
        sortOrder: ListSortOrder,
        isTrackerEnabled: Boolean,
        isVisibleOnShelf: Boolean,
        useDefaultOrder: Boolean = false,
    ): FavouriteCategory {
        val entity = FavouriteCategoryEntity(
            title = title,
            createdAt = System.currentTimeMillis(),
            sortKey = db.getFavouriteCategoriesDao().getNextSortKey(),
            categoryId = 0,
            order = sortOrder.name,
            track = isTrackerEnabled,
            deletedAt = 0L,
            isVisibleInLibrary = isVisibleOnShelf,
        )
        val id = db.getFavouriteCategoriesDao().insert(entity)
        val category = entity.toFavouriteCategory(id)
        settings.setUsesDefaultFavoritesOrder(id, useDefaultOrder)
        return category
    }

    suspend fun updateCategory(
        id: Long,
        title: String,
        sortOrder: ListSortOrder,
        isTrackerEnabled: Boolean,
        isVisibleOnShelf: Boolean,
    ) {
        db.getFavouriteCategoriesDao().update(id, title, sortOrder.name, isTrackerEnabled, isVisibleOnShelf)
    }

    suspend fun updateCategory(id: Long, isVisibleInLibrary: Boolean) {
        db.getFavouriteCategoriesDao().updateVisibility(id, isVisibleInLibrary)
    }

    suspend fun updateCategoryTracking(id: Long, isTrackingEnabled: Boolean) {
        db.getFavouriteCategoriesDao().updateTracking(id, isTrackingEnabled)
    }

    suspend fun removeCategories(ids: Collection<Long>) {
        db.withTransaction {
            for (id in ids) {
                db.getFavouritesDao().deleteAll(id)
                db.getFavouriteCategoriesDao().delete(id)
            }
            db.getChaptersDao().gc(metadataRefreshSettings.retainedMangaIds())
        }
        ids.forEach { settings.setUsesDefaultFavoritesOrder(it, false) }
    }

    suspend fun setCategoryOrder(id: Long, order: ListSortOrder) {
        db.getFavouriteCategoriesDao().updateOrder(id, order.name)
        settings.setUsesDefaultFavoritesOrder(id, false)
    }

    suspend fun reorderCategories(orderedIds: List<Long>) {
        val dao = db.getFavouriteCategoriesDao()
        db.withTransaction {
            for ((i, id) in orderedIds.withIndex()) {
                dao.updateSortKey(id, i)
            }
        }
    }

    suspend fun addToCategory(categoryId: Long, mangas: Collection<Manga>, preserveStoredMetadata: Boolean = false) {
        val createdAt = System.currentTimeMillis()
        db.withTransaction {
            for ((index, manga) in mangas.withIndex()) {
                if (!preserveStoredMetadata || db.getMangaDao().find(manga.id) == null) {
                    val tags = manga.tags.toEntities()
                    db.getTagsDao().upsert(tags)
                    db.getMangaDao().upsert(manga.toEntity(), tags)
                }
                val dao = db.getFavouritesDao()
                val existing = dao.findForRestore(manga.id, categoryId)
                val entity = favoriteMembership(existing, manga.id, categoryId, createdAt - index)
                if (entity != existing) dao.upsert(entity)
            }
        }
    }

    suspend fun reorderFavorites(scopeId: Long, orderedIds: List<Long>) = db.withTransaction {
        val active = if (scopeId == 0L) db.getFavouritesDao().findAll() else db.getFavouritesDao().findAll(scopeId)
        val eligible = active.mapTo(HashSet()) { it.manga.id }
        val dao = db.getFavoriteOrderDao()
        val revision = java.util.UUID.randomUUID().toString()
        val updatedAt = maxOf(System.currentTimeMillis(), dao.lastRevision(scopeId) + 1)
        orderedIds.distinct().filter { it in eligible }.forEachIndexed { rank, id ->
            dao.upsert(org.koitharu.kotatsu.favourites.data.FavoriteOrderEntity(id, scopeId, rank, updatedAt, revision))
        }
    }

    suspend fun removeFromFavourites(ids: Collection<Long>): ReversibleHandle {
        db.withTransaction {
            for (id in ids) {
                db.getFavouritesDao().delete(mangaId = id)
            }
            db.getChaptersDao().gc(metadataRefreshSettings.retainedMangaIds())
        }
        return ReversibleHandle { recoverToFavourites(ids) }
    }

    suspend fun removeFromCategory(categoryId: Long, ids: Collection<Long>): ReversibleHandle {
        db.withTransaction {
            for (id in ids) {
                db.getFavouritesDao().delete(categoryId = categoryId, mangaId = id)
            }
            db.getChaptersDao().gc(metadataRefreshSettings.retainedMangaIds())
        }
        return ReversibleHandle { recoverToCategory(categoryId, ids) }
    }

    fun observeOrder(categoryId: Long): Flow<ListSortOrder> {
        return combine(
            db.getFavouriteCategoriesDao().observe(categoryId).filterNotNull(),
            settings.observeChanges().filter {
                it == null || it == AppSettings.KEY_FAVORITES_ORDER || it == AppSettings.KEY_FAVORITES_DEFAULT_CATEGORIES
            }.onStart { emit(null) },
        ) { category, _ -> effectiveOrder(categoryId, ListSortOrder(category.order, ListSortOrder.NEWEST)) }
            .distinctUntilChanged()
    }

    private fun effectiveOrder(categoryId: Long, saved: ListSortOrder): ListSortOrder =
        if (settings.usesDefaultFavoritesOrder(categoryId)) settings.allFavoritesSortOrder else saved

    suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> {
        return db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map {
            it.toFavouriteCategory()
        }
    }

    private suspend fun recoverToFavourites(ids: Collection<Long>) {
        db.withTransaction {
            for (id in ids) {
                db.getFavouritesDao().recover(mangaId = id)
            }
        }
    }

    private suspend fun recoverToCategory(categoryId: Long, ids: Collection<Long>) {
        db.withTransaction {
            for (id in ids) {
                db.getFavouritesDao().recover(mangaId = id, categoryId = categoryId)
            }
        }
    }
}
