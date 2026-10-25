package org.koitharu.kotatsu.core.parser

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.request.CachePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.MangaIdentityMerge
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.tracker.data.TrackEntity
import java.io.IOException
import java.util.UUID
import javax.inject.Provider
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex

@RunWith(AndroidJUnit4::class)
class MetadataRefreshRepositoryTest {
    private val source = MangaParserSource.MANGALIB
    private val chapter = MangaChapter(20, "Saved chapter", 1f, 0, "/chapter/1", null, 100, "branch", source)
    private val seed = Manga(10, "Title", emptySet(), "/title", "https://example.org/title", .8f,
        null, "https://example.org/cover", emptySet(), null, emptySet(), source = source)

    @Test fun automaticAndWriteOnlyCallsRespectPolicyWhileExplicitRefreshBypassesOnce() = runTest {
        fixture { settings, database, cache ->
            assertFalse(settings.isSavedMetadataOnly(seed.id))
            assertFalse(settings.useSavedMetadataForUnavailableSources)
            database.getMangaDao().upsert(seed.toEntity(), emptyList())
            database.getChaptersDao().replaceAll(seed.id, listOf(chapter).withIndex().toEntities(seed.id))
            val saved = SavedMangaMetadata(database)
            val repository = FixtureRepository(cache)
            var evidence = 0
            repository.onFreshDetails = { evidence++ }
            repository.beforeDetails = { if (settings.isSavedMetadataOnly(it.id)) saved.getDetails(it) else null }
            assertEquals("Remote", repository.getDetails(seed).title)
            settings.setSavedMetadataOnly(seed.id, true)
            assertEquals(listOf(chapter), repository.getDetails(seed).chapters)
            assertEquals(listOf(chapter), repository.getDetails(seed, CachePolicy.WRITE_ONLY).chapters)
            assertEquals(1, repository.remoteCalls)
            assertEquals("Remote", repository.refreshDetails(seed).title)
            assertEquals(2, repository.remoteCalls)
            assertTrue(settings.isSavedMetadataOnly(seed.id))
            assertEquals(2, evidence)
            repository.getPages(seed, chapter)
            assertEquals(1, repository.pageCalls)
            settings.setSavedMetadataOnly(seed.id, false)
            assertEquals("Remote", repository.getDetails(seed, CachePolicy.WRITE_ONLY).title)
            assertEquals(3, repository.remoteCalls)
        }
    }

    @Test fun delayedGuardDoesNotCacheItsSavedFallbackOrReportFreshReleaseEvidence() = runTest {
        fixture { settings, database, cache ->
            val repository = FixtureRepository(cache)
            val saved = SavedMangaMetadata(database)
            var checks = 0
            var evidence = 0
            repository.onFreshDetails = { evidence++ }
            repository.beforeDetails = {
                checks++
                if (checks == 2) settings.setSavedMetadataOnly(it.id, true)
                if (settings.isSavedMetadataOnly(it.id)) saved.getDetails(it) else null
            }
            assertEquals(seed, repository.getDetails(seed))
            assertEquals(0, repository.remoteCalls)
            assertEquals(0, evidence)
            settings.setSavedMetadataOnly(seed.id, false)
            assertEquals("Remote", repository.getDetails(seed).title)
            assertEquals(1, repository.remoteCalls)
        }
    }

    @Test fun lightweightEnrichmentRespectsSavedOnlyWithoutChaptersOrReleaseEvidence() = runTest {
        fixture { settings, database, cache ->
            val repository = FixtureRepository(cache)
            val saved = SavedMangaMetadata(database)
            var evidence = 0
            repository.onFreshDetails = { evidence++ }
            repository.beforeDetails = { if (settings.isSavedMetadataOnly(it.id)) saved.getDetails(it) else null }
            settings.setSavedMetadataOnly(seed.id, true)
            assertEquals(seed, repository.getLightweightMetadata(seed.copy(chapters = listOf(chapter))))
            assertEquals(0, repository.metadataCalls)
            settings.setSavedMetadataOnly(seed.id, false)
            assertEquals("Metadata", repository.getLightweightMetadata(seed)?.title)
            assertEquals(1, repository.metadataCalls)
            assertEquals(0, repository.remoteCalls)
            assertEquals(0, repository.pageCalls)
            assertEquals(0, evidence)
        }
    }

    @Test fun unavailableFallbackIsOptInAndExplicitRefreshStillFails() = runTest {
        fixture { settings, database, _ ->
            val saved = SavedMangaMetadata(database)
            val repository = EmptyMangaRepository(source)
            repository.beforeDetails = {
                if (settings.isSavedMetadataOnly(it.id) || settings.useSavedMetadataForUnavailableSources) saved.getDetails(it)
                else null
            }
            assertTrue(runCatching { repository.getDetails(seed) }.exceptionOrNull() is UnsupportedSourceException)
            settings.useSavedMetadataForUnavailableSources = true
            assertNull(repository.getDetails(seed).chapters)
            assertEquals(emptyList<MangaChapter>(), repository.getDetails(seed.copy(chapters = emptyList())).chapters)
            assertTrue(runCatching { repository.refreshDetails(seed) }.exceptionOrNull() is UnsupportedSourceException)
        }
    }

    @Test fun sourceRecoveryRemovesImplicitRestrictionButRetainsAnExplicitChoiceAndFailures() = runTest {
        fixture { settings, database, cache ->
            settings.useSavedMetadataForUnavailableSources = true
            var unavailable = true
            val saved = SavedMangaMetadata(database)
            val repository = FixtureRepository(cache)
            repository.beforeDetails = {
                if (settings.isSavedMetadataOnly(it.id) || (settings.useSavedMetadataForUnavailableSources && unavailable)) saved.getDetails(it)
                else null
            }
            assertEquals(seed, repository.getDetails(seed))
            assertEquals(0, repository.remoteCalls)
            unavailable = false
            assertEquals("Remote", repository.getDetails(seed).title)
            settings.setSavedMetadataOnly(seed.id, true)
            assertEquals(seed, repository.getDetails(seed))
            settings.setSavedMetadataOnly(seed.id, false)
            for (failure in listOf(IOException("network"), IllegalStateException("authentication"))) {
                repository.failure = failure
                assertSame(failure, runCatching { repository.getDetails(seed, CachePolicy.WRITE_ONLY) }.exceptionOrNull())
            }
        }
    }

    @Test fun savedResolutionRetainsChaptersOverridesHistoryAndTrackCounts() = runTest {
        fixture { _, database, _ ->
            val manga = seed.copy(chapters = listOf(chapter))
            database.getMangaDao().upsert(manga.toEntity(), emptyList())
            database.getChaptersDao().replaceAll(seed.id, listOf(chapter).withIndex().toEntities(seed.id))
            val preferences = MangaPrefsEntity(seed.id, 2, .2f, .3f, true, false, false, "Override", "cover", "SAFE")
            val history = HistoryEntity(seed.id, 1, 2, chapter.id, 8, .25f, .6f, 0, 5)
            val track = TrackEntity(seed.id, chapter.id, 4, 7, 100, 0, null)
            database.getPreferencesDao().upsert(preferences)
            database.getHistoryDao().upsert(history)
            database.getTracksDao().upsert(track)
            val beforeManga = database.getMangaDao().find(seed.id)
            val beforeChapters = database.getChaptersDao().findAll(seed.id)
            assertEquals(listOf(chapter), SavedMangaMetadata(database).getDetails(seed.copy(chapters = emptyList())).chapters)
            assertEquals(beforeManga, database.getMangaDao().find(seed.id))
            assertEquals(beforeChapters, database.getChaptersDao().findAll(seed.id))
            assertEquals(preferences, database.getPreferencesDao().find(seed.id))
            assertEquals(history, database.getHistoryDao().find(seed.id))
            assertEquals(track, database.getTracksDao().find(seed.id))
        }
    }

    @Test fun optedInTitlesSurviveMetadataAndChapterCacheCleanup() = runTest {
        fixture { settings, database, _ ->
            for (id in listOf(10L, 11L)) {
                database.getMangaDao().upsert(seed.copy(id = id).toEntity(), emptyList())
                database.getChaptersDao().replaceAll(id, listOf(chapter.copy(id = id + 100)).withIndex().toEntities(id))
            }
            settings.setSavedMetadataOnly(10, true)
            settings.setSavedMetadataOnly(11, false)
            database.getChaptersDao().gc(settings.retainedMangaIds())
            database.getMangaDao().cleanup(settings.retainedMangaIds())
            assertNotNull(database.getMangaDao().find(10))
            assertEquals(1, database.getChaptersDao().findAll(10).size)
            assertNull(database.getMangaDao().find(11))
        }
    }

    @Test fun indexedLocalLookupDoesNotInitializeOrScanTheStorageIndex() = runTest {
        fixture { settings, database, _ ->
            val data = MangaDataRepository(database, Provider { error("link resolver must remain unused") },
                Provider { error("shortcuts must remain unused") }, settings,
                org.koitharu.kotatsu.history.exact.ChapterReadRepository(database))
            var scans = 0
            val index = LocalMangaIndex(data, database, ApplicationProvider.getApplicationContext<Application>(),
                Provider { scans++; error("storage scan must not run") },
                org.koitharu.kotatsu.remote.library.RemoteLibraryPersistence(ApplicationProvider.getApplicationContext<Application>()))
            assertNull(index.getIndexed(seed.id, withDetails = true))
            assertEquals(0, scans)
            assertNull(database.getMangaDao().find(seed.id))
        }
    }

    @Test fun actualIdentityMergeMappingCopiesFlagsOnlyAfterCommitAndLeavesOldKeys() = runTest {
        fixture { settings, database, _ ->
            for (id in listOf(10L, 11L)) database.getMangaDao().upsert(seed.copy(id = id).toEntity(), emptyList())
            settings.setSavedMetadataOnly(10, true)
            val mergedIds = database.withTransaction {
                MangaIdentityMerge.mergeManga(database.openHelper.writableDatabase, 10, 11)
            }
            assertEquals(mapOf(10L to 11L), mergedIds)
            assertFalse(settings.isSavedMetadataOnly(11))
            settings.copyMergedTitleFlags(mergedIds)
            assertTrue(settings.isSavedMetadataOnly(10))
            assertTrue(settings.isSavedMetadataOnly(11))
        }
    }

    private suspend fun fixture(block: suspend (MetadataRefreshSettings, MangaDatabase, MemoryContentCache) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefix = "saved-metadata-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences(prefix + name, mode)
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try {
            block(MetadataRefreshSettings(context), database, MemoryContentCache(application))
        } finally {
            database.close()
            preferences.edit().clear().commit()
        }
    }

    private inner class FixtureRepository(cache: MemoryContentCache) : CachingMangaRepository(cache) {
        override val source = this@MetadataRefreshRepositoryTest.source
        override val sortOrders = setOf(SortOrder.NEWEST)
        override var defaultSortOrder = SortOrder.NEWEST
        override val filterCapabilities = MangaListFilterCapabilities()
        var remoteCalls = 0
        var metadataCalls = 0
        var pageCalls = 0
        var failure: Throwable? = null
        override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?) = emptyList<Manga>()
        override suspend fun getDetailsImpl(manga: Manga): Manga {
            remoteCalls++
            failure?.let { throw it }
            return manga.copy(title = "Remote", chapters = listOf(chapter))
        }
        override suspend fun getLightweightMetadataImpl(manga: Manga): Manga {
            metadataCalls++
            return manga.copy(title = "Metadata", chapters = null)
        }
        override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> { pageCalls++; return emptyList() }
        override suspend fun getRelatedMangaImpl(seed: Manga) = emptyList<Manga>()
        override suspend fun getPageUrl(page: MangaPage) = page.url
        override suspend fun getFilterOptions() = MangaListFilterOptions()
    }
}
