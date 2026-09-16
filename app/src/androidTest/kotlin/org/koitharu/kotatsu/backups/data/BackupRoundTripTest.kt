package org.koitharu.kotatsu.backups.data

import android.content.Context
import android.content.ContextWrapper
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
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(directory, "sync-732.webp").readBytes())
                    val output = ByteArrayOutputStream()
                    ZipOutputStream(output).use { repository.createBackup(it, null, setOf(BackupSection.MANGA_PREFERENCES)) }
                    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                        assertEquals(BackupSection.MANGA_PREFERENCES.entryName, zip.nextEntry.name)
                        val exported = old.decodeFromString<List<MangaPreferencesBackup>>(zip.readBytes().decodeToString()).single()
                        assertEquals(old.encodeToString(prefs.withoutCustomCover()), old.encodeToString(exported))
                    }
                    // Export must not remove or change the active custom cover.
                    assertEquals(actual, db.getPreferencesDao().dump().single())
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(directory, "sync-732.webp").readBytes())
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun repository(context: Context, db: MangaDatabase) = BackupRepository(context, db, settings, tapGrid, sources, filters)

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
