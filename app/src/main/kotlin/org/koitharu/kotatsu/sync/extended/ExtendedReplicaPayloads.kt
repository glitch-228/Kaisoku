package org.koitharu.kotatsu.sync.extended

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backups.mihon.ForeignImportEnvelope
import org.koitharu.kotatsu.backups.mihon.ForeignImportTransfer
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.BoundedJson
import org.koitharu.kotatsu.history.exact.ChapterReadEnvelope
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import org.koitharu.kotatsu.remote.library.RemoteLibraryPersistence
import org.koitharu.kotatsu.scrobbling.v2.TrackerLinkEnvelope
import org.koitharu.kotatsu.scrobbling.v2.TrackerLinkRepository
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusCodec
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusStore
import org.koitharu.kotatsu.sync.drive.DriveReplicaJournal
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class ExtendedCategoryIdentity(val id: Long, val createdAt: Long, val uid: String?)

@Serializable
internal data class ExtendedTrackerLinks(
    val version: Int = 1,
    val links: TrackerLinkEnvelope,
    val categories: String?,
    val categoryIdentities: List<ExtendedCategoryIdentity> = emptyList(),
)

/** One causal reducer boundary for native-compatible data in separate Drive/server replicas. */
@Singleton
class ExtendedReplicaPayloads @Inject constructor(
    private val db: MangaDatabase,
    private val chapterReads: ChapterReadRepository,
    private val trackerLinks: TrackerLinkRepository,
    private val metadata: MetadataOverrideTransfer,
    private val libraries: RemoteLibraryPersistence,
    private val categories: CategoryStatusStore,
) {
    val namespaces: Set<String> = linkedSetOf(CHAPTERS, LINKS, METADATA, LIBRARIES, FOREIGN)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Omission is not a deletion: immutable transport cleanup needs an explicit state proof. */
    fun validateSuccessor(namespace: String, predecessor: ByteArray, candidate: ByteArray) =
        ExtendedReplicaSuccession.validate(namespace, predecessor, candidate)

    suspend fun export(namespace: String, checkCurrent: () -> Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        require(namespace in namespaces) { "Unsupported extended namespace" }
        checkCurrent()
        currentCoroutineContext().ensureActive()
        val result = when (namespace) {
            CHAPTERS -> guardedRoom(checkCurrent) { json.encodeToString(chapterReads.export()).toByteArray(Charsets.UTF_8) }
            LINKS -> {
                val intent = categories.exportTransfer()
                val configurations = CategoryStatusCodec.decodeTransfer(intent)
                val witnesses = guardedRoom(checkCurrent) {
                    ensureCategoryIdentityTables()
                    configurations.flatMap { it.mappings }.map { it.categoryId to it.categoryCreatedAt }.distinct()
                        .map { (id, created) ->
                            val category = id.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                                ?.let { db.getFavouriteCategoriesDao().findForRestore(it.toInt()) }
                            var uid = categoryUid(id)
                            if (category?.createdAt == created && uid == null) {
                                uid = "category-v2-${java.util.UUID.randomUUID()}"
                                db.openHelper.writableDatabase.execSQL(
                                    "INSERT INTO ${DriveReplicaJournal.CATEGORIES}(category_id,uid) VALUES(?,?)", arrayOf<Any>(id, uid))
                            }
                            ExtendedCategoryIdentity(id, created, uid.takeIf { category?.createdAt == created })
                        }
                }
                guardedRoom(checkCurrent) { json.encodeToString(ExtendedTrackerLinks(links = trackerLinks.export(),
                    categories = intent, categoryIdentities = witnesses)).toByteArray(Charsets.UTF_8) }
            }
            METADATA -> metadata.export(checkCurrent)
            LIBRARIES -> libraries.exportTransfer().toByteArray(Charsets.UTF_8)
            FOREIGN -> guardedRoom(checkCurrent) {
                json.encodeToString(ForeignImportTransfer(db).export()).toByteArray(Charsets.UTF_8)
            }
            else -> error("Unsupported extended namespace")
        }
        checkCurrent()
        currentCoroutineContext().ensureActive()
        BoundedJson.validateObject(result, MAX_BYTES)
        result
    }

    suspend fun merge(namespace: String, bytes: ByteArray, checkCurrent: () -> Unit = {}) = withContext(Dispatchers.IO) {
        require(namespace in namespaces) { "Unsupported extended namespace" }
        val text = BoundedJson.validateObject(bytes, MAX_BYTES)
        checkCurrent()
        currentCoroutineContext().ensureActive()
        when (namespace) {
            CHAPTERS -> {
                val value = json.decodeFromString<ChapterReadEnvelope>(text).also { it.validate() }
                guardedRoom(checkCurrent) { chapterReads.merge(value) }
            }
            LINKS -> {
                val value = json.decodeFromString<ExtendedTrackerLinks>(text)
                require(value.version == 1 && value.categoryIdentities.size <= 2_000) { "Invalid tracker replica" }
                value.links.validate()
                val mappings = value.categories?.let { CategoryStatusCodec.decodeTransfer(it) }.orEmpty()
                val required = mappings.flatMap { it.mappings }.map { it.categoryId to it.categoryCreatedAt }.toSet()
                val identities = value.categoryIdentities.associateBy { it.id to it.createdAt }
                require(identities.size == value.categoryIdentities.size && required == identities.keys &&
                    identities.values.all { it.id > 0 && it.createdAt >= 0 && (it.uid == null ||
                        it.uid.isNotBlank() && it.uid.length <= 256) }) { "Invalid tracker category identity witnesses" }
                val categoryPreview = value.categories?.let { categories.previewTransfer(it) }
                require(categoryPreview?.conflicts.isNullOrEmpty()) { "Conflicting tracker category mapping revision" }
                val changed = categoryPreview?.changedOwners.orEmpty()
                val bindings = guardedRoom(checkCurrent) {
                    ensureCategoryIdentityTables()
                    mappings.filter { it.owner in changed }.flatMap { it.mappings }.map {
                        val identity = identities.getValue(it.categoryId to it.categoryCreatedAt)
                        val uid = requireNotNull(identity.uid) { "Restore or review the missing category before syncing tracker mappings" }
                        require(!uid.startsWith("legacy-")) {
                            "Legacy category names are ambiguous; restore or review their tracker mappings first"
                        }
                        val matches = resolveCategoryUid(uid)
                        require(matches.size == 1) { "Restore or review the category identity before syncing tracker mappings" }
                        (identity.id to identity.createdAt) to matches.single()
                    }.toMap()
                }
                // Preflight both formats first. The file journal is a separate idempotent commit;
                // the transport acknowledges only after both commits succeed.
                guardedRoom(checkCurrent) { trackerLinks.merge(value.links) }
                if (value.categories != null) categories.mergeTransfer(value.categories, bindings, checkCurrent)
            }
            METADATA -> metadata.merge(bytes, checkCurrent)
            LIBRARIES -> {
                val preview = libraries.previewTransfer(text)
                require(preview.conflicts.isEmpty()) { "Conflicting remote-library connection or acquisition identity" }
                libraries.mergeTransfer(text, checkCurrent)
            }
            FOREIGN -> {
                val value = json.decodeFromString<ForeignImportEnvelope>(text).also { it.validate() }
                guardedRoom(checkCurrent) { ForeignImportTransfer(db).merge(value) }
            }
        }
        checkCurrent()
        currentCoroutineContext().ensureActive()
    }

    private suspend fun <T> guardedRoom(checkCurrent: () -> Unit, block: suspend () -> T): T = db.withTransaction {
        checkCurrent()
        currentCoroutineContext().ensureActive()
        block().also { checkCurrent(); currentCoroutineContext().ensureActive() }
    }

    private fun ensureCategoryIdentityTables() {
        // Do not call DriveReplicaJournal.install: that would change the selected backend's trigger state.
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS ${DriveReplicaJournal.CATEGORIES} (category_id INTEGER PRIMARY KEY, uid TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS ${DriveReplicaJournal.ALIASES} (old_uid TEXT PRIMARY KEY, uid TEXT NOT NULL)")
    }

    private fun categoryUid(id: Long): String? = db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
        "SELECT uid FROM ${DriveReplicaJournal.CATEGORIES} WHERE category_id=?", arrayOf(id),
    )).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun resolveCategoryUid(initial: String): List<Pair<Long, Long>> {
        var uid = initial
        val seen = hashSetOf<String>()
        repeat(64) {
            require(seen.add(uid)) { "Category identity alias cycle" }
            val next = db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "SELECT uid FROM ${DriveReplicaJournal.ALIASES} WHERE old_uid=?", arrayOf(uid),
            )).use { if (it.moveToFirst()) it.getString(0) else null }
            if (next == null || next == uid) return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "SELECT c.category_id,c.created_at FROM favourite_categories c JOIN ${DriveReplicaJournal.CATEGORIES} m " +
                    "ON m.category_id=c.category_id WHERE m.uid=? AND c.deleted_at=0", arrayOf(uid),
            )).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) } }
            uid = next
        }
        error("Category identity alias chain is too long")
    }

    companion object {
        const val CHAPTERS = "chapter_reads_v1"
        const val LINKS = "tracker_links_v1"
        const val METADATA = "metadata_overrides_v1"
        const val LIBRARIES = "remote_libraries_v1"
        const val FOREIGN = "foreign_import_state_v1"
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
