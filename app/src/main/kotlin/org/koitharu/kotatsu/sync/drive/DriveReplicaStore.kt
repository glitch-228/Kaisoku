package org.koitharu.kotatsu.sync.drive

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.data.SensitiveBackupKeys
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveReplicaStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    private val backupRepository: BackupRepository,
    private val sourceSettings: DriveSourceSettingsStore,
    private val appSettings: AppSettings,
    private val preferenceVersions: DrivePreferenceVersions,
    private val preferenceApplier: DriveReplicaPreferences,
    private val metadataRefreshSettings: org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val metadata = context.getSharedPreferences("google_drive_replica_metadata", Context.MODE_PRIVATE)

    /** Capture and apply under one SQLite transaction, so reader/library writes cannot be lost between them. */
    suspend fun merge(
        account: String,
        device: String,
        requestedSections: Set<BackupSection>,
        remote: List<DriveReplica>,
        legacy: List<Legacy>,
    ): DriveReplica {
        currentCoroutineContext().ensureActive()
        val sections = requestedSections.filterNotTo(hashSetOf()) { it.isExtended }
        val mergedMangaIds = linkedMapOf<Long, Long>()
        // Preferences cannot roll back with SQLite. Once the local transaction starts, complete its
        // local merge even if the worker is stopped; authentication/transfers stay cancellable.
        return withContext(NonCancellable) {
        val mergedReplica = database.withTransaction {
        val db = database.openHelper.writableDatabase
        if (context.getSharedPreferences("google_drive_sync", Context.MODE_PRIVATE)
                .getString(SyncBackendSettings.KEY_BACKEND, null) != SyncBackend.GOOGLE_DRIVE.name) {
            throw kotlinx.coroutines.CancellationException("Google Drive synchronization was disabled")
        }
        val selected = sections.filter { it != BackupSection.INDEX }.mapTo(hashSetOf()) { it.entryName }
        if (BackupSection.SETTINGS in sections) selected += DriveReplicaBackup.SOURCE_SETTINGS
        val previous = load(db, account)
        val localValues = snapshot(db, sections)
        val changes = changes(db)
        var clock = clock(db)
        val local = previous.toMutableMap()
        for ((identity, value) in localValues) {
            val old = previous[identity]
            val rawKey = DriveReplicaBackup.rawKey(identity.first, identity.second) { categoryId(db, it) }
            val event = changes[identity.first to rawKey]?.first ?: 0L
            val prefVersion = preferenceVersion(identity.first, identity.second)
            val actualValue = DriveReplicaMerge.recordValue(identity.first, value)
            val same = old != null && DriveReplicaMerge.content(old.value) == DriveReplicaMerge.content(actualValue)
            val eventVersion = maxOf(event, prefVersion)
            val sameRevision = same && eventVersion <= old!!.version
            val version = if (sameRevision) old!!.version else if (old == null) {
                maxOf(eventVersion, DriveReplicaMerge.eventTime(value) * 1000)
            } else if (eventVersion > old.version) eventVersion else {
                clock = DriveReplicaMerge.nextVersion(clock, System.currentTimeMillis())
                clock
            }
            local[identity] = DriveReplicaRecord(identity.first, identity.second, version,
                if (sameRevision) old!!.author else device, actualValue)
        }
        for ((identity, old) in previous) if (identity.first in selected && identity !in localValues && !old.isDeleted) {
            val rawKey = DriveReplicaBackup.rawKey(identity.first, identity.second) { categoryId(db, it) }
            val event = maxOf(changes[identity.first to rawKey]?.first ?: 0L,
                preferenceVersion(identity.first, identity.second))
            val version = if (event > old.version) event else {
                clock = DriveReplicaMerge.nextVersion(clock, System.currentTimeMillis()); clock
            }
            local[identity] = old.copy(version = version, author = device, value = null)
        }
        // Physical deletes still propagate if the row was deleted before its first synchronization.
        for ((identity, event) in changes) if (identity.first in selected && event.second) {
            val key = stableKey(db, identity.first, identity.second)
            val stable = identity.first to key
            if (stable !in localValues && (local[stable]?.version ?: -1) < event.first) {
                local[stable] = DriveReplicaRecord(identity.first, key, event.first, device)
            }
        }
        for ((pref, version) in preferenceVersions.deletions()) {
            val (scope, key) = pref
            val identity = when {
                scope == "settings" || scope == "reader_grid" -> scope to key
                scope.startsWith("source_settings/") -> "source_settings" to kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive(scope.substringAfter('/')), kotlinx.serialization.json.JsonPrimitive(key),
                )).toString()
                else -> continue
            }
            if (identity.first in selected && identity !in localValues && version > (local[identity]?.version ?: -1)) {
                local[identity] = DriveReplicaRecord(identity.first, identity.second, version, device)
            }
        }
        val imported = legacy.map { item -> importLegacy(db, account, item, selected) }
        val localReplica = DriveReplica(device = device, sections = local.keys.mapTo(hashSetOf()) { it.first },
            records = local.values.toList())
        val normalized = (remote + imported + localReplica).map { replica ->
            replica.copy(records = replica.records.map { DriveReplicaMerge.normalizeCategories(it) { uid -> resolvedUid(db, uid) } })
        }
        val categoryMerge = DriveReplicaMerge.coalesceCategories(DriveReplicaMerge.merge(normalized))
        storeAliases(db, categoryMerge.aliases)
        val merged = DriveReplicaMerge.cascadeCategoryDeletions(categoryMerge.records).toMutableMap()
        // Network selections are local device choices. A remote snapshot cannot revert an explicit local value.
        for (key in existingNetworkSettingsKeys(appSettings.getAllValues())) {
            val identity = BackupSection.SETTINGS.entryName to key
            local[identity]?.let { merged[identity] = it }
        }
        var selectedRecords = merged.values.filter { it.section in selected &&
            (it.section != "settings" || !SensitiveBackupKeys.isSensitive(it.key)) }
        clock = maxOf(clock, selectedRecords.maxOfOrNull { it.version } ?: 0L)
        setClock(db, clock)
        db.execSQL("UPDATE ${DriveReplicaJournal.STATE} SET muted=1 WHERE id=1")
        try {
            DriveReplicaValidation.validate(selectedRecords)
            ensureRemoteCategories(db, selectedRecords)
            val backup = temp("drive-merged", ".zip")
            try {
                DriveReplicaBackup.write(backup, selectedRecords) { categoryId(db, it) }
                ZipInputStream(backup.inputStream().buffered()).use { input ->
                    val databaseSections = sections.filterTo(hashSetOf()) { !preferenceApplier.isPreference(it.entryName) }
                    val result = backupRepository.restoreBackup(input, databaseSections, null, isMerge = true,
                        authoritativeRecords = true,
                        preserveSettingsKeys = existingNetworkSettingsKeys(appSettings.getAllValues()),
                        mergedMangaIds = mergedMangaIds)
                    check(result.isAllSuccess) { result.failures.firstOrNull()?.message ?: "Could not apply Google Drive replica" }
                }
            } finally {
                backup.delete()
            }
            for (record in selectedRecords.filter { it.isDeleted && !preferenceApplier.isPreference(it.section) }) delete(db, record)
            val updatedPrefs = preferenceApplier.apply(selectedRecords, localValues, device, clock)
            selectedRecords = selectedRecords.filterNot { preferenceApplier.isPreference(it.section) } + updatedPrefs
            clock = maxOf(clock, selectedRecords.maxOfOrNull { it.version } ?: 0L)
            setClock(db, clock)
            // Save precisely the applied state, including tombstones, before releasing the write transaction.
            save(db, account, selectedRecords)
            for ((index, item) in legacy.withIndex()) save(db, "$account/legacy/${item.fileId}", imported[index].records)
        } finally {
            db.execSQL("UPDATE ${DriveReplicaJournal.STATE} SET muted=0 WHERE id=1")
        }
        val priorVersions = remote.flatMap { it.legacyVersions.entries }.associate { it.toPair() }
        DriveReplica(device = device, sections = selected, records = selectedRecords,
            legacyVersions = priorVersions + legacy.associate { it.fileId to it.fileVersion })
        }
        metadataRefreshSettings.copyMergedTitleFlags(mergedMangaIds)
        mergedReplica
        }
    }

    suspend fun writeCompatibilityBackup(file: File, replica: DriveReplica) = database.withTransaction {
        val sections = BackupSection.entries.filterTo(hashSetOf()) { !it.isExtended && it.entryName in replica.sections }
        ZipOutputStream(file.outputStream().buffered()).use {
            backupRepository.createBackup(it, null, sections, includeCustomCovers = true)
        }
    }

    fun replicaSourceSettings(replica: DriveReplica): Map<String, Map<String, String>> = replica.records
        .filter { it.section == DriveReplicaBackup.SOURCE_SETTINGS && !it.isDeleted }
        .groupBy { Json.parseToJsonElement(it.key).jsonArray[0].jsonPrimitive.content }
        .mapValues { (_, records) -> records.associate {
            Json.parseToJsonElement(it.key).jsonArray[1].jsonPrimitive.content to checkNotNull(it.value).jsonPrimitive.content
        } }

    data class Legacy(val fileId: String, val fileVersion: String, val backup: File, val metadata: DriveSnapshotCodec.Metadata)

    fun hasLegacyVersion(account: String, fileId: String, version: String): Boolean =
        metadata.getString("$account/$fileId", null) == version

    fun acknowledgeLegacy(account: String, replica: DriveReplica) {
        val edit = metadata.edit()
        replica.legacyVersions.forEach { (id, version) -> edit.putString("$account/$id", version) }
        edit.apply()
    }

    private suspend fun snapshot(db: SupportSQLiteDatabase, sections: Set<BackupSection>): Map<Pair<String, String>, kotlinx.serialization.json.JsonElement> {
        val backup = temp("drive-local", ".zip")
        try {
            ZipOutputStream(backup.outputStream().buffered()).use {
                backupRepository.createBackup(it, null, sections, includeCustomCovers = true)
            }
            val sourceValues = if (BackupSection.SETTINGS in sections) sourceSettings.dump() else emptyMap()
            sourceValues.keys.forEach { preferenceApplier.watch("source_settings", it) }
            val result = DriveReplicaBackup.read(backup, { id, title -> categoryUid(db, id, title) }, sourceValues)
            result.keys.filter { it.first == "saved_filters" }.forEach {
                val source = Json.parseToJsonElement(it.second).jsonArray[0].jsonPrimitive.content
                preferenceApplier.watch("saved_filters", source)
            }
            return result
        } finally {
            backup.delete()
        }
    }

    private fun importLegacy(db: SupportSQLiteDatabase, account: String, legacy: Legacy, selected: Set<String>): DriveReplica {
        val before = load(db, "$account/legacy/${legacy.fileId}")
        val values = DriveReplicaBackup.read(legacy.backup, { id, title ->
            legacyCategoryUid(db, account, legacy.fileId, id, title)
        }, legacy.metadata.sourceSettings)
        val records = values.filterKeys { it.first in selected }.map { (identity, value) ->
            val old = before[identity]
            val actual = DriveReplicaMerge.recordValue(identity.first, value)
            val same = old != null && DriveReplicaMerge.content(old.value) == DriveReplicaMerge.content(actual)
            val time = DriveReplicaMerge.eventTime(value).takeIf { it > 0 } ?: legacy.metadata.syncedAt
            if (same) old!!.copy(value = actual) else DriveReplicaRecord(identity.first, identity.second,
                time.coerceAtLeast(0) * 1000, "legacy/${legacy.fileId}", actual)
        }
        return DriveReplica(device = "legacy/${legacy.fileId}", sections = selected, records = records)
    }

    private fun ensureRemoteCategories(db: SupportSQLiteDatabase, records: List<DriveReplicaRecord>) {
        for (record in records.filter { it.section == "categories" }) {
            val exists = db.query("SELECT category_id FROM ${DriveReplicaJournal.CATEGORIES} WHERE uid=?", arrayOf(record.key)).use {
                it.moveToFirst()
            }
            if (exists) continue
            val title = record.value?.jsonObject?.getValue("title")?.jsonPrimitive?.content
            val id = db.query("SELECT MAX(category_id) FROM (SELECT category_id FROM favourite_categories UNION SELECT category_id FROM ${DriveReplicaJournal.CATEGORIES})").use {
                it.moveToFirst(); it.getLong(0) + 1
            }
            db.execSQL("INSERT INTO ${DriveReplicaJournal.CATEGORIES}(category_id,uid) VALUES(?,?)", arrayOf<Any?>(id, record.key))
            // The ordinary backup restore inserts the actual category with this mapped identity.
            check(title == null || title.isNotEmpty()) { "Invalid Google Drive category title" }
        }
    }

    private fun legacyCategoryUid(db: SupportSQLiteDatabase, account: String, fileId: String, id: Long, title: String?): String {
        db.query("SELECT uid FROM ${DriveReplicaJournal.LEGACY_CATEGORIES} WHERE account=? AND file_id=? AND category_id=?",
            arrayOf<Any?>(account, fileId, id)).use { if (it.moveToFirst()) return it.getString(0) }
        val localId = title?.let { name ->
            db.query("SELECT category_id FROM favourite_categories WHERE title=? ORDER BY deleted_at LIMIT 1", arrayOf(name))
                .use { if (it.moveToFirst()) it.getLong(0) else null }
        }
        val uid = if (localId != null) categoryUid(db, localId, title) else if (title != null) {
            "legacy-" + title.toByteArray().joinToString("") { "%02X".format(it) }
        } else error("Google Drive favourite references an unknown legacy category")
        db.execSQL("INSERT INTO ${DriveReplicaJournal.LEGACY_CATEGORIES} VALUES(?,?,?,?)", arrayOf<Any?>(account, fileId, id, uid))
        return uid
    }

    private fun storeAliases(db: SupportSQLiteDatabase, aliases: Map<String, String>) {
        for ((old, target) in aliases) {
            db.execSQL("INSERT OR REPLACE INTO ${DriveReplicaJournal.ALIASES} VALUES(?,?)", arrayOf(old, target))
            db.execSQL("UPDATE ${DriveReplicaJournal.CATEGORIES} SET uid=? WHERE uid=?", arrayOf(target, old))
        }
    }

    private fun resolvedUid(db: SupportSQLiteDatabase, uid: String): String {
        var result = uid
        val seen = hashSetOf<String>()
        while (seen.add(result)) {
            val next = db.query("SELECT uid FROM ${DriveReplicaJournal.ALIASES} WHERE old_uid=?", arrayOf(result)).use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return result
            result = next
        }
        error("Invalid Google Drive category alias")
    }

    private fun categoryUid(db: SupportSQLiteDatabase, id: Long, title: String?): String {
        db.query("SELECT uid FROM ${DriveReplicaJournal.CATEGORIES} WHERE category_id=?", arrayOf(id)).use {
            if (it.moveToFirst()) return resolvedUid(db, it.getString(0))
        }
        var uid = if (title != null) "legacy-" + title.toByteArray().joinToString("") { "%02X".format(it) }
            else "category-${UUID.randomUUID()}"
        db.query("SELECT 1 FROM ${DriveReplicaJournal.CATEGORIES} WHERE uid=?", arrayOf(uid)).use {
            if (it.moveToFirst()) uid = "category-${UUID.randomUUID()}"
        }
        db.execSQL("INSERT INTO ${DriveReplicaJournal.CATEGORIES}(category_id,uid) VALUES(?,?)", arrayOf<Any?>(id, uid))
        return uid
    }

    private fun categoryId(db: SupportSQLiteDatabase, uid: String): Long =
        db.query("SELECT c.category_id FROM ${DriveReplicaJournal.CATEGORIES} c LEFT JOIN favourite_categories f ON f.category_id=c.category_id WHERE c.uid=? ORDER BY (f.category_id IS NULL), f.deleted_at, c.category_id LIMIT 1", arrayOf(resolvedUid(db, uid))).use {
            check(it.moveToFirst()) { "Google Drive favourite references an unknown category" }
            it.getLong(0)
        }

    private fun stableKey(db: SupportSQLiteDatabase, section: String, key: String): String = when (section) {
        "categories" -> categoryUid(db, key.toLong(), null)
        "favourites" -> "${key.substringBefore(':')}/${categoryUid(db, key.substringAfter(':').toLong(), null)}"
        "favorite_order" -> "${key.substringBefore(':')}/${if (key.substringAfter(':') == "0") DriveReplicaBackup.ALL_FAVORITES_UID else categoryUid(db, key.substringAfter(':').toLong(), null)}"
        else -> key
    }

    private fun delete(db: SupportSQLiteDatabase, record: DriveReplicaRecord) {
        val table = DriveReplicaJournal.tables.find { it.section == record.section } ?: return
        val key = DriveReplicaBackup.rawKey(record.section, record.key) { categoryId(db, it) }
        val parts = if (table.columns.size == 1) listOf(key) else key.split(':')
        require(parts.size == table.columns.size) { "Invalid Google Drive deletion key" }
        val where = table.columns.joinToString(" AND ") { "`$it`=?" }
        if (record.section == "sources") {
            db.execSQL("UPDATE sources SET enabled=0 WHERE $where", parts.toTypedArray())
        } else if (record.section in setOf("history", "categories", "favourites")) {
            db.execSQL("UPDATE `${table.table}` SET deleted_at=? WHERE $where",
                (listOf<Any?>(record.version / 1000) + parts).toTypedArray())
        } else db.execSQL("DELETE FROM `${table.table}` WHERE $where", parts.toTypedArray())
    }


    private fun preferenceVersion(section: String, key: String): Long = if (section == DriveReplicaBackup.SOURCE_SETTINGS) {
        val parts = Json.parseToJsonElement(key).jsonArray
        preferenceVersions.version("source_settings/${parts[0].jsonPrimitive.content}", parts[1].jsonPrimitive.content)
    } else if (section == "saved_filters") {
        val parts = Json.parseToJsonElement(key).jsonArray
        preferenceVersions.version("saved_filters/${parts[0].jsonPrimitive.content}",
            "__pf_${parts[1].jsonPrimitive.content.hashCode()}")
    } else preferenceVersions.version(section, key)

    private fun load(db: SupportSQLiteDatabase, account: String): Map<Pair<String, String>, DriveReplicaRecord> {
        val values = linkedMapOf<Pair<String, String>, DriveReplicaRecord>()
        db.query("SELECT json FROM ${DriveReplicaJournal.RECORDS} WHERE account=?", arrayOf(account)).use { cursor ->
            while (cursor.moveToNext()) {
                val saved = json.decodeFromString(DriveReplicaRecord.serializer(), cursor.getString(0))
                val record = when (saved.section) {
                    "categories" -> saved.copy(key = resolvedUid(db, saved.key))
                    "favourites", "favorite_order" -> saved.copy(key = "${saved.key.substringBefore('/')}/${if (saved.key.substringAfter('/') == DriveReplicaBackup.ALL_FAVORITES_UID) DriveReplicaBackup.ALL_FAVORITES_UID else resolvedUid(db, saved.key.substringAfter('/'))}")
                    else -> saved
                }
                val normalized = if (record.section in setOf("categories", "favourites", "favorite_order")) {
                    val uid = if (record.section == "categories") record.key else record.key.substringAfter('/')
                    record.copy(value = (record.value as? JsonObject)?.let {
                        JsonObject(it + ("sync_uid" to kotlinx.serialization.json.JsonPrimitive(uid)))
                    })
                } else record
                val existing = values[normalized.identity]
                if (existing == null || DriveReplicaMerge.compare(normalized, existing) > 0) values[normalized.identity] = normalized
            }
        }
        return values
    }

    private fun save(db: SupportSQLiteDatabase, account: String, records: Collection<DriveReplicaRecord>) {
        for (record in records) db.execSQL("INSERT OR REPLACE INTO ${DriveReplicaJournal.RECORDS} VALUES(?,?,?,?)",
            arrayOf(account, record.section, record.key, json.encodeToString(DriveReplicaRecord.serializer(), record)))
    }

    private fun changes(db: SupportSQLiteDatabase): Map<Pair<String, String>, Pair<Long, Boolean>> {
        val result = hashMapOf<Pair<String, String>, Pair<Long, Boolean>>()
        db.query("SELECT section,record_key,version,deleted FROM ${DriveReplicaJournal.CHANGES}").use {
            while (it.moveToNext()) result[it.getString(0) to it.getString(1)] = it.getLong(2) to (it.getInt(3) != 0)
        }
        return result
    }

    private fun clock(db: SupportSQLiteDatabase): Long = db.query("SELECT clock FROM ${DriveReplicaJournal.STATE} WHERE id=1").use {
        it.moveToFirst(); it.getLong(0)
    }

    private fun setClock(db: SupportSQLiteDatabase, value: Long) =
        db.execSQL("UPDATE ${DriveReplicaJournal.STATE} SET clock=MAX(clock,?) WHERE id=1", arrayOf(value))

    private fun temp(prefix: String, suffix: String): File = File.createTempFile(prefix, suffix,
        File(context.noBackupFilesDir, "drive-sync").apply { mkdirs() })
}
