package org.koitharu.kotatsu.core.parser.mihon

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.UUID

class MihonInjektBridgeTest {
    @Test
    fun actualBridgeRegistersProtoBufBeforeSourceInitializationAndKeepsItOnRepeatedInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = MihonInjektBridge(context, OkHttpClient(), CookieJar.NO_COOKIES) {
            error("Serialization initialization must not load a source or make requests")
        }
        bridge.initialize()
        assertSame(ProtoBuf, Injekt.get<ProtoBuf>())
        bridge.initialize()
        assertSame(ProtoBuf, Injekt.get<ProtoBuf>())
    }

    @Test
    fun repositoryContainsExtensionErrorsOutsideNetworkingAndPreservesCancellation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefix = "mihon-runtime-fixture-${UUID.randomUUID()}-"
        val names = HashSet<String>()
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences((prefix + name).also(names::add), mode)
        }
        val cache = MemoryContentCache(app)
        val source = FailingSource()
        val wrapper = MihonMangaSource("fixture.protobuf", source.id)
        try {
            val repository = MihonMangaRepository(
                MihonExtensionManager.LoadedSource(wrapper, source), cache, AppSettings(isolated),
            )
            val chapter = MangaChapter(7L, "Fixture", 1f, 0, "/chapter", null, 0, null, wrapper)
            val requests: List<suspend () -> Any> = listOf(
                { repository.getList(0, SortOrder.POPULARITY, null) },
                { repository.discover(SortOrder.POPULARITY, MangaListFilter()) },
                { repository.getPages(chapter) },
            )
            for (request in requests) {
                val caught = runCatching { request() }.exceptionOrNull()
                assertTrue(caught is IOException)
                // Coroutine stack recovery may insert an IOException copy; retain the original root cause.
                assertTrue(generateSequence(caught) { it.cause }.take(8).any { it === source.failure })
            }
            source.failure = CancellationException("fixture cancelled")
            val cancelled = runCatching { repository.getList(0, null, null) }.exceptionOrNull()
            assertTrue(cancelled is CancellationException)
            assertTrue(generateSequence(cancelled) { it.cause }.take(8).any { it === source.failure })
        } finally {
            app.unregisterComponentCallbacks(cache)
            names.forEach(app::deleteSharedPreferences)
        }
    }

    private class FailingSource : CatalogueSource {
        override val id = 121L
        override val name = "Protobuf initializer fixture"
        override val lang = "en"
        override val supportsLatest = false
        override fun getFilterList() = FilterList()
        var failure: Throwable = ExceptionInInitializerError(IllegalStateException("Missing extension binding"))
        override suspend fun getPopularManga(page: Int): MangasPage = throw failure
        override suspend fun getMangaDetails(manga: SManga): SManga = throw failure
        override suspend fun getChapterList(manga: SManga): List<SChapter> = throw failure
        override suspend fun getPageList(chapter: SChapter): List<Page> = throw failure
    }
}
