package org.koitharu.kotatsu.scrobbling.v2

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.scrobbling.common.data.withTrackerRequestGuard
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusAccess
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusAccount
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusBinding
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusChoice
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusGuard
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusTitle
import javax.inject.Inject
import javax.inject.Singleton

/** This bridge never creates links, library entries, favorites or progress records. */
@Singleton
class TrackerCategoryAccess @Inject constructor(private val tools: TrackerToolsRepository, private val db: MangaDatabase) :
    CategoryStatusAccess {
    override val changes: Flow<Unit> = merge(db.invalidationTracker.createFlow("manga", "favourites",
        "favourite_categories", "scrobblings", "tracker_links_v2", emitInitialState = true).map { Unit },
        tools.localChanges.map { Unit }, *ScrobblerService.entries.map {
            requireNotNull(tools.legacyRepository(it.name)).storage.sessionChanges.map { Unit }
        }.toTypedArray())

    override suspend fun accounts(): List<CategoryStatusAccount> = tools.incomingConnections().mapNotNull { connection ->
        val statuses = when (connection.owner.provider) {
            "COMICK" -> if ("library:write" in connection.scopes)
                listOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING") else emptyList()
            "ANIME_PLANET" -> if (tools.canWriteAnimePlanet(connection))
                listOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "WONT_READ") else emptyList()
            else -> LegacyStatusPatch.statuses(ScrobblerService.valueOf(connection.owner.provider)).toList()
        }
        statuses.takeIf { it.isNotEmpty() }?.let { values ->
            CategoryStatusAccount(connection, connection.nickname ?: connection.owner.accountId,
                values.map { CategoryStatusChoice(it, it.replace('_', ' ')) })
        }
    }

    override suspend fun readbackConnection(owner: TrackerOwner): TrackerConnection? =
        tools.incomingConnections().singleOrNull { it.owner == owner }

    override suspend fun categories(): List<CategoryStatusBinding> = db.withTransaction {
        db.getFavouriteCategoriesDao().findAll().filter { it.deletedAt == 0L && it.categoryId > 0 }.map {
            CategoryStatusBinding(it.categoryId.toLong(), it.createdAt, it.title)
        }.also { check(it.size <= 500) { "Too many categories for automatic tracker mapping" } }
    }

    override suspend fun titles(connection: TrackerConnection, limit: Int): List<CategoryStatusTitle> = db.withTransaction {
        tools.checkConnection(connection)
        require(limit in 1..20_001)
        val service = ScrobblerService.entries.firstOrNull { it.name == connection.owner.provider }
        val sql = if (service == null) "SELECT manga_id FROM tracker_links_v2 WHERE provider_key=? AND account_id=? AND deleted=0 AND confirmed=1 ORDER BY manga_id LIMIT ?"
        else "SELECT manga_id FROM tracker_links_v2 WHERE provider_key=? AND account_id=? AND deleted=0 AND confirmed=1 UNION SELECT manga_id FROM scrobblings WHERE scrobbler=${service.id} AND account_id=? ORDER BY manga_id LIMIT ?"
        val args = if (service == null) arrayOf<Any>(connection.owner.provider, connection.owner.accountId, limit)
            else arrayOf<Any>(connection.owner.provider, connection.owner.accountId, tools.legacyAccountId(connection), limit)
        val ids = db.openHelper.writableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        ids.mapNotNull { title(connection, it) }
    }

    override suspend fun title(connection: TrackerConnection, mangaId: Long): CategoryStatusTitle? = db.withTransaction {
        val link = tools.incomingLink(connection, mangaId) ?: return@withTransaction null
        val manga = tools.localManga(mangaId) ?: return@withTransaction null
        val memberships = db.openHelper.writableDatabase.query(SimpleSQLiteQuery(
            "SELECT c.category_id,c.created_at,c.title FROM favourite_categories c JOIN favourites f ON f.category_id=c.category_id WHERE f.manga_id=? AND f.deleted_at=0 AND c.deleted_at=0 ORDER BY c.category_id",
            arrayOf(mangaId))).use { cursor -> buildList {
                while (cursor.moveToNext()) add(CategoryStatusBinding(cursor.getLong(0), cursor.getLong(1), cursor.getString(2)))
            } }
        CategoryStatusTitle(mangaId, manga.title, manga.source.name, manga.url, manga.publicUrl,
            link.reference, link.revision, memberships)
    }

    override suspend fun readback(guard: CategoryStatusGuard, beforeRequest: suspend () -> Unit): TrackerRemoteEntry? =
        guarded(guard, beforeRequest) { tools.incomingEntry(guard.connection, guard.reference.mediaId) }

    override suspend fun updateStatusOnly(guard: CategoryStatusGuard, status: String,
        beforeSend: suspend () -> Unit): TrackerRemoteEntry = guarded(guard, beforeSend) {
        require(accounts().singleOrNull { it.connection.owner == guard.owner &&
            it.connection.revision == guard.connection.revision && it.connection.scopes == guard.connection.scopes }
            ?.statuses?.any { it.value == status } == true)
        tools.provider(guard.owner.provider).patch(guard.connection, guard.reference.mediaId,
            TrackerPatch.statusOnly(status), allowCreate = false)
    }

    private suspend fun <T> guarded(guard: CategoryStatusGuard, callback: suspend () -> Unit,
        block: suspend () -> T): T {
        validate(guard)
        return withTrackerRequestGuard({ validate(guard); callback(); tools.checkConnection(guard.connection) }) {
            block().also { tools.checkConnection(guard.connection) }
        }
    }

    private suspend fun validate(guard: CategoryStatusGuard) {
        tools.checkConnection(guard.connection)
        check(title(guard.connection, guard.mangaId)?.let(guard::matches) == true) {
            "Tracker link, source edition or category memberships changed"
        }
    }
}
