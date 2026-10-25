package org.koitharu.kotatsu.sync.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koitharu.kotatsu.backups.data.SensitiveBackupKeys
import org.koitharu.kotatsu.backups.domain.BackupSection
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bridge to Kaisoku's existing, typed and backwards-compatible backup serializers. */
object DriveReplicaBackup {
    const val SOURCE_SETTINGS = "source_settings"
    const val ALL_FAVORITES_UID = "__kaisoku_all_favorites__"

    fun read(
        backup: File,
        categoryUid: (Long, String?) -> String,
        sourceSettings: Map<String, Map<String, String>> = emptyMap(),
    ): Map<Pair<String, String>, JsonElement> {
        val values = linkedMapOf<Pair<String, String>, JsonElement>()
        val categories = hashMapOf<Long, String>()
        ZipInputStream(backup.inputStream().buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val section = BackupSection.of(entry)
                // Extended state travels in separate versioned replicas; legacy clients cannot own its tombstones.
                if (section != null && section != BackupSection.INDEX && !section.isExtended) {
                    val rows = Json.parseToJsonElement(input.readBytes().decodeToString()).jsonArray
                    if (section == BackupSection.SETTINGS || section == BackupSection.SETTINGS_READER_GRID) {
                        for ((key, value) in rows.single().jsonObject) {
                            if (section != BackupSection.SETTINGS || !SensitiveBackupKeys.isSensitive(key)) {
                                values[section.entryName to key] = canonical(value)
                            }
                        }
                    } else for (row in rows) {
                        val item = row.jsonObject
                        if (section == BackupSection.CATEGORIES) {
                            val id = item.number("category_id")
                            val uid = item["sync_uid"]?.jsonPrimitive?.content?.takeIf { it != "null" }
                                ?: categoryUid(id, item.string("title"))
                            categories[id] = uid
                            values[section.entryName to uid] = JsonObject(item + ("sync_uid" to JsonPrimitive(uid)))
                        } else if (section == BackupSection.FAVOURITES || section == BackupSection.FAVORITE_ORDER) {
                            val id = item.number("category_id")
                            val uid = if (section == BackupSection.FAVORITE_ORDER && id == 0L) ALL_FAVORITES_UID
                                else categories[id] ?: categoryUid(id, null)
                            values[section.entryName to "${item.number("manga_id")}/$uid"] =
                                JsonObject(item + ("sync_uid" to JsonPrimitive(uid)))
                        } else if (section == BackupSection.BOOKMARKS) {
                            for (bookmark in item.getValue("bookmarks").jsonArray) {
                                val data = bookmark.jsonObject
                                val key = "${data.number("manga_id")}:${data.number("page_id")}"
                                values[section.entryName to key] = JsonObject(item +
                                    ("bookmarks" to JsonArray(listOf(bookmark))))
                            }
                        } else values[section.entryName to key(section, item)] = canonical(row)
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
        for ((source, prefs) in sourceSettings) for ((key, value) in prefs) {
            if (key in DriveSourceSettingsStore.ALLOWED_KEYS) {
                val identity = JsonArray(listOf(JsonPrimitive(source), JsonPrimitive(key))).toString()
                values[SOURCE_SETTINGS to identity] = JsonPrimitive(value)
            }
        }
        return values
    }

    fun write(file: File, records: Collection<DriveReplicaRecord>, categoryId: (String) -> Long) {
        // Soft-deleted history is a hidden parent of retained reading statistics on a new device.
        val grouped = records.filter { !it.isDeleted || (it.section == "history" && it.value != null) }.groupBy(DriveReplicaRecord::section)
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            output.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
            for (section in BackupSection.entries) {
                if (section.isExtended) continue
                val rows = grouped[section.entryName] ?: continue
                val value = if (section == BackupSection.SETTINGS || section == BackupSection.SETTINGS_READER_GRID) {
                    JsonArray(listOf(JsonObject(rows.associate { it.key to checkNotNull(it.value) })))
                } else JsonArray(rows.map { record ->
                    val data = checkNotNull(record.value).jsonObject
                    if (section == BackupSection.CATEGORIES || section == BackupSection.FAVOURITES || section == BackupSection.FAVORITE_ORDER) {
                        val uid = data.string("sync_uid")
                        val id = if (section == BackupSection.FAVORITE_ORDER && uid == ALL_FAVORITES_UID) 0L else categoryId(uid)
                        JsonObject(data + ("category_id" to JsonPrimitive(id)))
                    } else data
                })
                output.putNextEntry(ZipEntry(section.entryName))
                output.write(value.toString().toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
    }

    fun rawKey(section: String, key: String, categoryId: (String) -> Long): String = when (section) {
        "categories" -> categoryId(key).toString()
        "favourites" -> "${key.substringBefore('/')}:${categoryId(key.substringAfter('/'))}"
        "favorite_order" -> "${key.substringBefore('/')}:${if (key.substringAfter('/') == ALL_FAVORITES_UID) 0L else categoryId(key.substringAfter('/'))}"
        else -> key
    }

    fun key(section: BackupSection, value: JsonObject): String = when (section) {
        BackupSection.HISTORY -> value.number("manga_id").toString()
        BackupSection.SOURCES -> value.string("source")
        BackupSection.SCROBBLING -> "${value.number("scrobbler")}:${value.number("id")}:${value.number("manga_id")}"
        BackupSection.STATS -> "${value.number("manga_id")}:${value.number("started_at")}"
        BackupSection.SAVED_FILTERS -> JsonArray(listOf(value.getValue("source"), value.getValue("name"))).toString()
        BackupSection.MANGA_PREFERENCES, BackupSection.TRACKS -> value.getValue("manga").jsonObject.number("id").toString()
        else -> error("Unsupported Google Drive section $section")
    }

    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        // Preference sets are encoded as arrays. Sorting them also stabilizes metadata tags.
        is JsonArray -> JsonArray(value.map(::canonical).sortedBy(JsonElement::toString))
        else -> value
    }

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.content.toLong()
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
