package org.koitharu.kotatsu.backups.data

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.backups.data.model.MangaPreferencesBackup
import org.koitharu.kotatsu.backups.data.model.TrackBackup
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.reader.data.TapGridSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Uses only in-memory databases and a unique test cover directory; never restores into the app DB. */
@HiltAndroidTest
class BackupRoundTripTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var tapGrid: TapGridSettings
    @Inject lateinit var sources: MangaSourcesRepository
    @Inject lateinit var filters: SavedFiltersRepository

    @Before fun inject() = hilt.inject()

    @Test fun tracksOnlyOldAndCompactArchivesRestoreIntoIsolatedDatabases() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manga = MangaBackup(id = 731, title = "Fixture", url = "/fixture", publicUrl = "https://example.org/fixture",
            coverUrl = "https://example.org/cover", source = "lnreader:fixture")
        val track = TrackBackup(manga, 57, 4, 1234567, 765432, -1, "Fixture error\n".repeat(500))
        val old = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        for (merge in listOf(false, true)) {
            Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().use { first ->
                val repository = repository(context, first)
                val input = archive(BackupSection.TRACKS.entryName, old.encodeToString(listOf(track)))
                val restored = repository.restoreBackup(ZipInputStream(ByteArrayInputStream(input)), setOf(BackupSection.TRACKS), null, isMerge = merge)
                assertTrue(restored.failures.toString(), restored.isAllSuccess)
                assertEquals(track.toEntity(), first.getTracksDao().find(manga.id))
                assertNotNull(first.getMangaDao().find(manga.id))
                val output = ByteArrayOutputStream()
                ZipOutputStream(output).use { repository.createBackup(it, null, setOf(BackupSection.INDEX, BackupSection.TRACKS)) }
                ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && entry.name != BackupSection.TRACKS.entryName) entry = zip.nextEntry
                    assertNotNull(entry)
                    val decoded = old.decodeFromString<List<TrackBackup>>(zip.readBytes().decodeToString())
                    assertEquals(track.toEntity(), decoded.single().toEntity())
                }
                Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().use { second ->
                    val roundTrip = repository(context, second).restoreBackup(ZipInputStream(ByteArrayInputStream(output.toByteArray())), setOf(BackupSection.TRACKS), null, isMerge = merge)
                    assertTrue(roundTrip.failures.toString(), roundTrip.isAllSuccess)
                    assertEquals(track.toEntity(), second.getTracksDao().find(manga.id))
                }
            }
        }
    }

    @Test fun coverAndOverridesRestoreWithoutUsingAppCoverDirectory() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "backup-test-" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getExternalFilesDir(type: String?): File = directory
        }
        val manga = MangaBackup(id = 732, title = "Fixture", url = "/other", publicUrl = "https://example.org/other",
            coverUrl = "https://example.org/cover", source = "lnreader:fixture")
        val prefs = MangaPreferencesBackup(manga, 2, .2f, .4f, true, true, true,
            "Override", "file:///old-cover.webp", "AQIDBA==", "webp", "SAFE")
        val old = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        try {
            Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().use { db ->
                val repository = repository(context, db)
                for (merge in listOf(false, true)) {
                    val input = archive(BackupSection.MANGA_PREFERENCES.entryName, old.encodeToString(listOf(prefs)))
                    val result = repository.restoreBackup(ZipInputStream(ByteArrayInputStream(input)), setOf(BackupSection.MANGA_PREFERENCES), null,
                        isMerge = merge, replaceSections = setOf(BackupSection.MANGA_PREFERENCES))
                    assertTrue(result.failures.toString(), result.isAllSuccess)
                    val actual = db.getPreferencesDao().dump().single()
                    assertEquals(prefs.toEntity(actual.coverUrlOverride), actual)
                    val restoredCover = File(requireNotNull(requireNotNull(actual.coverUrlOverride).toUri().path))
                    assertEquals(directory.canonicalFile, restoredCover.canonicalFile.parentFile)
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), restoredCover.readBytes())
                    val output = ByteArrayOutputStream()
                    ZipOutputStream(output).use { repository.createBackup(it, null, setOf(BackupSection.MANGA_PREFERENCES)) }
                    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                        assertEquals(BackupSection.MANGA_PREFERENCES.entryName, zip.nextEntry.name)
                        val exported = old.decodeFromString<List<MangaPreferencesBackup>>(zip.readBytes().decodeToString()).single()
                        assertEquals(old.encodeToString(prefs.withoutCustomCover().copy(metadataVersion = 1)),
                            old.encodeToString(exported))
                    }
                    // Export must not remove or change the active custom cover.
                    assertEquals(actual, db.getPreferencesDao().dump().single())
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), restoredCover.readBytes())
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun independentManualOrdersRoundTripAndRemapCategoriesWithoutChangingReaderHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val category = org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity(1, 10, 0, "Reading", "MANUAL", false, true, 0)
        val manga = MangaBackup(900, "Order fixture", url = "/order", publicUrl = "https://example.org/order", coverUrl = "", source = "MANGADEX")
        val ranks = listOf(
            org.koitharu.kotatsu.favourites.data.FavoriteOrderEntity(900, 0, 3, 100, "global"),
            org.koitharu.kotatsu.favourites.data.FavoriteOrderEntity(900, 1, 8, 101, "category"),
        )
        Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().use { first ->
            first.getMangaDao().upsert(manga.toEntity(), emptyList())
            first.getFavouriteCategoriesDao().upsert(category)
            ranks.forEach { first.getFavoriteOrderDao().upsert(it) }
            val sections = setOf(BackupSection.CATEGORIES, BackupSection.FAVORITE_ORDER)
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { repository(context, first).createBackup(it, null, sections) }
            Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().use { second ->
                second.getFavouriteCategoriesDao().upsert(category.copy(title = "Other"))
                val repo = repository(context, second)
                val result = repo.restoreBackup(ZipInputStream(ByteArrayInputStream(output.toByteArray())), sections, null, isMerge = true)
                assertTrue(result.failures.toString(), result.isAllSuccess)
                val restoredCategory = second.getFavouriteCategoriesDao().findAll().single { it.title == "Reading" }
                assertNotEquals(1, restoredCategory.categoryId)
                assertEquals(ranks[0], second.getFavoriteOrderDao().find(900, 0))
                assertEquals(ranks[1].copy(scopeId = restoredCategory.categoryId.toLong()),
                    second.getFavoriteOrderDao().find(900, restoredCategory.categoryId.toLong()))
                val snapshot = second.getFavoriteOrderDao().dump()
                // A backup without the optional section is not a reset instruction.
                repo.restoreBackup(ZipInputStream(ByteArrayInputStream(archive("index", "[]"))), setOf(BackupSection.INDEX), null, isMerge = true)
                assertEquals(snapshot, second.getFavoriteOrderDao().dump())
                val older = org.koitharu.kotatsu.backups.data.model.FavoriteOrderBackup(900, 0, 99, 1, "older", manga)
                repo.restoreBackup(ZipInputStream(ByteArrayInputStream(archive("favorite_order", Json.encodeToString(listOf(older))))),
                    setOf(BackupSection.FAVORITE_ORDER), null, isMerge = true)
                assertEquals(ranks[0], second.getFavoriteOrderDao().find(900, 0))
                assertTrue(second.getHistoryDao().findAllForBackup(0, 100).isEmpty())
                assertTrue(second.getFavouritesDao().findAll().isEmpty())
            }
        }
    }

    @Test fun sanitizedForeignBackupsRestoreAllRecordsWithoutTouchingCurrentSettingsOrMissingSections() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val prefix = "foreign-backup-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        val isolatedSettings = AppSettings(context)
        val decoder = Json { ignoreUnknownKeys = true; coerceInputValues = true; allowSpecialFloatingPointValues = true }
        val selected = setOf(BackupSection.HISTORY, BackupSection.CATEGORIES, BackupSection.FAVOURITES,
            BackupSection.SOURCES)
        val settingsBefore = isolatedSettings.getAllValues().toMap()
        for (name in listOf("kotatsu.bk.zip", "futon.bk.zip")) {
            val bytes = instrumentation.context.assets.open("foreign-backups/$name").use { it.readBytes() }
            val sections = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                buildMap {
                    var entry = zip.nextEntry
                    while (entry != null) {
                        put(entry.name, zip.readBytes().decodeToString())
                        entry = zip.nextEntry
                    }
                }
            }
            val expectedHistory = decoder.decodeFromString<List<org.koitharu.kotatsu.backups.data.model.HistoryBackup>>(
                sections.getValue("history")).map { it.toEntity() }.toSet()
            val expectedFavorites = decoder.decodeFromString<List<org.koitharu.kotatsu.backups.data.model.FavouriteBackup>>(
                sections.getValue("favourites")).map { it.toEntity() }.toSet()
            for (merge in listOf(false, true)) Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().use { db ->
                val repo = repository(context, db, isolatedSettings)
                val sentinel = MangaBackup(99001, "Existing", url = "/existing", publicUrl = "https://fixture.invalid/existing",
                    coverUrl = "", source = "lnreader:missing-fixture")
                db.getMangaDao().upsert(sentinel.toEntity(), emptyList())
                val order = org.koitharu.kotatsu.favourites.data.FavoriteOrderEntity(99001, 0, 17, 100, "keep")
                db.getFavoriteOrderDao().upsert(order)
                val track = TrackBackup(sentinel, 5, 1, 123, 456, -1, null).toEntity()
                db.getTracksDao().upsert(track)
                repeat(2) {
                    val result = repo.restoreBackup(ZipInputStream(ByteArrayInputStream(bytes)), selected, null, isMerge = merge)
                    assertTrue(result.failures.toString(), result.isAllSuccess)
                    assertEquals(expectedHistory, db.getHistoryDao().findAllForBackup(0, 1000).map { it.history }.toSet())
                    assertEquals(expectedFavorites, db.getFavouritesDao().findAllForBackup(0, 1000).map { it.favourite }.toSet())
                    assertEquals(2, db.getFavouriteCategoriesDao().findAll().size)
                    assertEquals(order, db.getFavoriteOrderDao().find(99001, 0))
                    assertEquals(track, db.getTracksDao().find(99001))
                    assertEquals(settingsBefore, isolatedSettings.getAllValues())
                }
                // Current adult filtering changes visibility, never imported record counts or flags.
                val adultCondition = checkNotNull(db.getFavouritesDao().getCondition(
                    org.koitharu.kotatsu.list.domain.ListFilterOption.Macro.NSFW))
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM favourites JOIN manga USING(manga_id) " +
                    "WHERE favourites.deleted_at=0 AND NOT($adultCondition)").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(14, cursor.getInt(0))
                }
                assertEquals(97, db.getFavouritesDao().findAllForBackup(0, 1000).size)
                val output = ByteArrayOutputStream()
                ZipOutputStream(output).use { repo.createBackup(it, null, selected) }
                Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().use { restored ->
                    val result = repository(context, restored, isolatedSettings).restoreBackup(
                        ZipInputStream(ByteArrayInputStream(output.toByteArray())), selected, null)
                    assertTrue(result.failures.toString(), result.isAllSuccess)
                    assertEquals(expectedHistory, restored.getHistoryDao().findAllForBackup(0, 1000).map { it.history }.toSet())
                    assertEquals(expectedFavorites, restored.getFavouritesDao().findAllForBackup(0, 1000).map { it.favourite }.toSet())
                }
            }
        }
    }

    private fun repository(context: Context, db: MangaDatabase, scopedSettings: AppSettings = settings): BackupRepository {
        // Production's DatabasePrePopulateCallback installs this journal. Isolated test DBs omit prepopulation.
        org.koitharu.kotatsu.sync.drive.DriveReplicaJournal.install(db.openHelper.writableDatabase)
        return BackupRepository(context, db, scopedSettings, tapGrid, sources, filters)
    }

    private inline fun <T> MangaDatabase.use(block: (MangaDatabase) -> T): T =
        try { block(this) } finally { close() }

    private fun archive(name: String, json: String): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
