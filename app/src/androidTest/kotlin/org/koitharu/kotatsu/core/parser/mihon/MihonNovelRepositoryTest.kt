package org.koitharu.kotatsu.core.parser.mihon

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.ChapterTextRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaChapter
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Page
import java.io.IOException
import java.util.UUID

class MihonNovelRepositoryTest {
    @Test fun concurrentTextRequestsCoalesceAndUseTransformedTextPages() = fixture { repository, source, chapter ->
        val texts = coroutineScope { List(8) { async { repository.getChapterHtml(chapter) } }.awaitAll() }
        assertEquals(1, source.calls)
        assertTrue(texts.all { it.contains("Fixture chapter") })
        assertEquals("https://fixture.invalid/api/chapter/42/", source.requested!!.url)
        assertEquals(0, source.requested!!.index)
        assertEquals(emptyMap<String, String>(), repository.getImageHeaders())
        assertEquals(1, source.pageListCalls)
        assertEquals(texts.first(), repository.getChapterHtml(chapter))
        assertEquals(1, source.calls)
    }

    @Test fun failedAndCancelledTextIsNotCachedAndRetriesKeepIdentity() = fixture { repository, source, chapter ->
        source.failure = IOException("Fixture failure")
        assertSame(source.failure, runCatching { repository.getChapterHtml(chapter) }.exceptionOrNull())
        source.failure = CancellationException("Fixture cancellation")
        assertTrue(runCatching { repository.getChapterHtml(chapter) }.exceptionOrNull() is CancellationException)
        source.failure = null
        assertTrue(repository.getChapterHtml(chapter).contains("Fixture chapter"))
        assertEquals(3, source.calls)
        assertEquals("mihon:novel.fixture/77", chapter.source.name)
        assertEquals(22L, chapter.id)
    }

    private fun fixture(block: suspend (MihonNovelRepository, Fixture, MangaChapter) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefix = "novel-wrapper-fixture-${UUID.randomUUID()}"
        val names = HashSet<String>()
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$prefix-$name".also(names::add), mode)
        }
        val cache = MemoryContentCache(app)
        val source = Fixture()
        val wrapper = MihonMangaSource("novel.fixture", source.id, isNovelSource = true)
        val loaded = MihonExtensionManager.LoadedSource(wrapper, source, source)
        try {
            val repository = MihonNovelRepository(loaded, cache, AppSettings(isolated))
            assertTrue(repository is ChapterTextRepository)
            val chapter = MangaChapter(22L, "Fixture", 1f, 0, "/book/chapter", null, 0, null, wrapper)
            block(repository, source, chapter)
        } finally {
            app.unregisterComponentCallbacks(cache)
            names.forEach(app::deleteSharedPreferences)
        }
    }

    private class Fixture : CatalogueSource, NovelSource {
        override val id = 77L
        override val name = "Novel fixture"
        override val lang = "en"
        override val supportsLatest = false
        override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()
        var calls = 0
        var pageListCalls = 0
        var failure: Throwable? = null
        var requested: Page? = null
        override suspend fun fetchPageText(page: Page): String {
            calls++
            requested = page
            failure?.let { throw it }
            delay(10)
            return "<p>Fixture chapter</p>"
        }
        override suspend fun getPageList(chapter: eu.kanade.tachiyomi.source.model.SChapter): List<Page> {
            pageListCalls++
            assertEquals("/book/chapter", chapter.url)
            return listOf(Page(0, "https://fixture.invalid/api/chapter/42/"))
        }
    }
}
