package org.koitharu.kotatsu.sync.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import org.koitharu.kotatsu.sync.drive.DriveReplicaCodec.readBounded

class DriveReplicaTest {
    @Test fun legacyBackupWriterNeverPublishesExtendedValuesOrDeletionOwnership() {
        val file = File.createTempFile("legacy-extended-exclusion-", ".zip")
        try {
            val extended = org.koitharu.kotatsu.backups.domain.BackupSection.entries.filter { it.isExtended }
            val records = extended.map { DriveReplicaRecord(it.entryName, "fixture", 100, "device",
                Json.parseToJsonElement("""{"version":1,"private":"opaque"}""")) }
            DriveReplicaBackup.write(file, records) { error("No category mapping expected") }
            java.util.zip.ZipInputStream(file.inputStream()).use { assertNull(it.nextEntry) }
            assertTrue(DriveReplicaBackup.read(file, { _, _ -> "fixture" }).isEmpty())
        } finally { file.delete() }
    }

    @Test fun deletionWinsAgainstStaleReplicasInAnyOrder() {
        val live = record(10, "a", JsonPrimitive("old"))
        val deleted = record(11, "b", null)
        assertEquals(deleted, merged(live, deleted))
        assertEquals(deleted, merged(deleted, live))
        assertEquals(deleted, merged(deleted, live, live))
    }

    @Test fun laterIntentionalRecreationWinsAgainstTombstone() {
        assertEquals(record(12, "a", JsonPrimitive("new")),
            merged(record(11, "b", null), record(12, "a", JsonPrimitive("new"))))
    }

    @Test fun equalVersionHasDeterministicTieBreak() {
        val a = record(5, "a", JsonPrimitive(1))
        val b = record(5, "b", JsonPrimitive(2))
        assertEquals(b, merged(a, b))
        assertEquals(b, merged(b, a))
        assertEquals(record(5, "b", null), merged(b, record(5, "b", null)))
    }

    @Test fun unionDoesNotDeleteMissingKeysOrConfuseSections() {
        val left = DriveReplica(device = "a", sections = setOf("history", "settings"), records = listOf(
            record(1, "a", JsonPrimitive("page")), DriveReplicaRecord("settings", "7", 1, "a", JsonPrimitive("theme"))))
        val right = DriveReplica(device = "b", sections = setOf("history"), records = emptyList())
        assertEquals(2, DriveReplicaMerge.merge(listOf(left, right)).size)
    }

    @Test fun monotonicClockSurvivesClockRollbackAndRemoteFutureEdits() {
        assertEquals(100001L, DriveReplicaMerge.nextVersion(100000, 1))
        assertEquals(200000L, DriveReplicaMerge.nextVersion(100000, 200))
        assertEquals(900001L, DriveReplicaMerge.nextVersion(900000, 200))
    }

    @Test fun metadataRefreshAndSourceUsageAreNotUserEdits() {
        val a = Json.parseToJsonElement("""{"manga":{"title":"Old"},"tags":[],"page":4,"used_at":1,"category_id":1}""")
        val b = Json.parseToJsonElement("""{"manga":{"title":"New"},"tags":[1],"page":4,"used_at":9,"category_id":99}""")
        assertEquals(DriveReplicaMerge.content(a), DriveReplicaMerge.content(b))
    }

    @Test fun softDeletionsNormalizeToPersistentTombstones() {
        val deleted = Json.parseToJsonElement("""{"deleted_at":123,"page":4}""")
        assertNotNull(DriveReplicaMerge.recordValue("history", deleted))
        assertTrue(DriveReplicaRecord("history", "7", 1, "a", deleted).isDeleted)
        assertNull(DriveReplicaMerge.recordValue("categories", deleted))
        assertNull(DriveReplicaMerge.recordValue("favourites", deleted))
        assertNotNull(DriveReplicaMerge.recordValue("manga_preferences", deleted))
    }

    @Test fun categoryBackupIdentityAliasesRemapEveryFavourite() {
        val records = listOf(
            category("restored-uid", "Reading", 10), category("original-uid", "Reading", 9),
            DriveReplicaRecord("favourites", "42/restored-uid", 12, "a", Json.parseToJsonElement("""{"sync_uid":"restored-uid","manga_id":42}""")),
            DriveReplicaRecord("favourites", "43/original-uid", 11, "b", Json.parseToJsonElement("""{"sync_uid":"original-uid","manga_id":43}""")),
        )
        val result = DriveReplicaMerge.coalesceCategories(records.associateBy { it.identity })
        assertEquals(mapOf("restored-uid" to "original-uid"), result.aliases)
        assertEquals(3, result.records.size)
        assertTrue("favourites" to "42/original-uid" in result.records)
        assertTrue("favourites" to "43/original-uid" in result.records)
        assertEquals(10L, result.records.getValue("categories" to "original-uid").version)
    }

    @Test fun differentCategoryTitlesDoNotAliasJustBecauseNumericIdsMatch() {
        val records = listOf(category("a", "Reading", 10), category("b", "Planning", 20))
        val result = DriveReplicaMerge.coalesceCategories(records.associateBy { it.identity })
        assertTrue(result.aliases.isEmpty())
        assertEquals(2, result.records.size)
    }

    @Test fun staleAliasedTombstoneCannotDeleteNewerLiveCategory() {
        val live = category("a", "Reading", 4)
        val stale = DriveReplicaRecord("categories", "b", 3, "remote", null)
        val normalized = listOf(live, stale).map { DriveReplicaMerge.normalizeCategories(it) { uid -> if (uid == "b") "a" else uid } }
        val merged = DriveReplicaMerge.merge(normalized.map {
            DriveReplica(device = it.author, sections = setOf(it.section), records = listOf(it))
        })
        assertEquals(live, merged.getValue("categories" to "a"))
        assertEquals(1, merged.size)
    }

    @Test fun deletedCategoryCannotLeaveAnOrphanLiveMembershipOnANewDevice() {
        val deleted = DriveReplicaRecord("categories", "a", 3, "remote", null)
        val favourite = DriveReplicaRecord("favourites", "42/a", 4, "local", JsonPrimitive("membership"))
        val merged = DriveReplicaMerge.cascadeCategoryDeletions(listOf(deleted, favourite).associateBy { it.identity })
        val result = merged.getValue("favourites" to "42/a")
        assertTrue(result.isDeleted)
        assertEquals(4L, result.version)
        assertEquals(deleted, merged.getValue("categories" to "a"))
    }

    @Test fun gzipReplicaRoundTripsIncludingDeletionAndMigrationCursors() {
        val original = DriveReplica(device = "device-1", sections = setOf("history"),
            records = listOf(record(1, "a", JsonPrimitive("text")), record(2, "b", null).copy(key = "8")),
            legacyVersions = mapOf("file-1" to "9"))
        val file = File.createTempFile("replica-test", ".gz")
        try {
            DriveReplicaCodec.write(file, original)
            assertEquals(original, DriveReplicaCodec.read(file))
        } finally { file.delete() }
    }

    @Test fun recordOutsideDeclaredSectionsIsRejected() {
        try {
            DriveReplicaMerge.merge(listOf(DriveReplica(device = "a", sections = emptySet(), records = listOf(record(1, "a", null)))))
            fail("Malformed section membership must fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun boundedStreamRejectsOversizeAndDoesNotRequireNewAndroidIoApis() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBounded(3))
        try {
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).readBounded(3)
            fail("Oversized input must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun manualRanksFollowCategoryAliasesButAllFavoritesNeverNeedsCategoryResolution() {
        val order = DriveReplicaRecord("favorite_order", "42/old", 2, "a", Json.parseToJsonElement("""{"rank":3,"sync_uid":"old"}"""))
        val result = DriveReplicaMerge.normalizeCategories(order) { if (it == "old") "new" else error("Unexpected UID") }
        assertEquals("42/new", result.key)
        val global = order.copy(key = "42/${DriveReplicaBackup.ALL_FAVORITES_UID}")
        assertEquals(global.key, DriveReplicaMerge.normalizeCategories(global) { error("All is synthetic") }.key)
        val deleted = DriveReplicaRecord("categories", "new", 3, "b", null)
        val values = DriveReplicaMerge.cascadeCategoryDeletions(listOf(result, global, deleted).associateBy { it.identity })
        assertTrue(values.getValue(result.identity).isDeleted)
        assertFalse(values.getValue(global.identity).isDeleted)
    }

    private fun category(uid: String, title: String, version: Long) = DriveReplicaRecord("categories", uid, version, "a",
        JsonObject(mapOf("title" to JsonPrimitive(title), "sync_uid" to JsonPrimitive(uid))))
    private fun record(version: Long, author: String, value: kotlinx.serialization.json.JsonElement?) =
        DriveReplicaRecord("history", "7", version, author, value)
    private fun merged(vararg records: DriveReplicaRecord) = DriveReplicaMerge.merge(records.map {
        DriveReplica(device = it.author, sections = setOf(it.section), records = listOf(it))
    }).getValue("history" to "7")
}
