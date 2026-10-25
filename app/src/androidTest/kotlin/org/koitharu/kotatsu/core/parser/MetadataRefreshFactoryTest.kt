package org.koitharu.kotatsu.core.parser

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.LnReaderSourceEntity
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceManager
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceRegistry
import org.koitharu.kotatsu.core.parser.lnreader.UnresolvedLnReaderSource
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.suggestions.local.RecommendationStore
import java.util.UUID
import javax.inject.Inject

@HiltAndroidTest
class MetadataRefreshFactoryTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var local: LocalMangaRepository
    @Inject lateinit var loader: MangaLoaderContext
    @Inject lateinit var mirrors: MirrorSwitcher
    @Inject lateinit var mihon: MihonExtensionManager
    @Inject lateinit var evidence: RecommendationStore
    @Inject lateinit var remoteLibraries: org.koitharu.kotatsu.remote.library.RemoteLibraryManager

    @Before fun inject() = hilt.inject()

    private val seed = Manga(10, "Fixture", emptySet(), "/fixture", "https://example.org/fixture", -1f,
        null, null, emptySet(), null, emptySet(), source = MangaParserSource.MANGALIB)

    @Test fun factoryAttachesTheNativeAndWriteOnlyGuardAndDoesNotTreatDisabledSourcesAsMissing() = runBlocking {
        fixture { factory, settings, _, _ ->
            assertFalse(factory.isMetadataRefreshPaused(seed))
            settings.useSavedMetadataForUnavailableSources = true
            assertFalse(factory.isMetadataRefreshPaused(seed.copy(source = MangaSourceInfo(seed.source, false, false))))
            MangaParserSource.entries.firstOrNull { it.isBroken }?.let { broken ->
                assertTrue(factory.isMetadataRefreshPaused(seed.copy(source = broken)))
            }
            settings.setSavedMetadataOnly(seed.id, true)
            val repository = factory.create(seed.source) as CachingMangaRepository
            assertEquals(seed, repository.getDetails(seed))
            assertEquals(seed, repository.getDetails(seed, coil3.request.CachePolicy.WRITE_ONLY))
            assertTrue(settings.isSavedMetadataOnly(seed.id))
        }
    }

    @Test fun restoringAnUnavailableLnReaderAdapterReplacesOnlyItsEmptyRepositoryAndExplicitChoiceRemains() = runBlocking {
        fixture { factory, settings, database, manager ->
            val native = factory.create(seed.source)
            val source = LnReaderMangaSource("isolated-fixture", "Fixture")
            val missing = seed.copy(source = source)
            val empty = factory.create(source)
            assertTrue(empty is EmptyMangaRepository)
            assertFalse(factory.isMetadataRefreshPaused(missing))
            settings.useSavedMetadataForUnavailableSources = true
            assertTrue(factory.isMetadataRefreshPaused(missing))
            assertEquals(missing, empty.getDetails(missing))
            database.getLnReaderSourcesDao().upsert(LnReaderSourceEntity(
                pluginId = source.pluginId, name = "Fixture", lang = "en", site = "https://example.org",
                version = "1", iconUrl = null, jsCode = "module.exports = {};", createdAt = 1, updatedAt = 1,
            ))
            manager.reload()
            val restored = factory.create(source)
            assertTrue(restored is LnReaderMangaRepository)
            assertNotSame(empty, restored)
            assertFalse(factory.isMetadataRefreshPaused(missing))
            assertSame(native, factory.create(seed.source))
            assertSame(restored, factory.create(UnresolvedLnReaderSource(source.pluginId)))
            settings.setSavedMetadataOnly(seed.id, true)
            assertTrue(factory.isMetadataRefreshPaused(missing))
        }
    }

    private suspend fun fixture(
        block: suspend (MangaRepository.Factory, MetadataRefreshSettings, MangaDatabase, LnReaderSourceManager) -> Unit,
    ) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefix = "saved-metadata-factory-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences(prefix + name, mode)
        }
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = MetadataRefreshSettings(context)
        val cache = MemoryContentCache(application)
        val manager = LnReaderSourceManager(context, OkHttpClient(), database)
        val previousSources = LnReaderSourceRegistry.sources.toList()
        try {
            val factory = MangaRepository.Factory(context, local, loader, cache, mirrors, mihon, manager,
                AppSettings(context), evidence, settings, SavedMangaMetadata(database),
                dagger.Lazy { error("Tsuki is not used by this fixture") }, remoteLibraries)
            block(factory, settings, database, manager)
        } finally {
            LnReaderSourceRegistry.replaceAll(previousSources)
            application.unregisterComponentCallbacks(cache)
            database.close()
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        }
    }
}
