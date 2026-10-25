package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.parser.hasChapterText
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.UnresolvedLnReaderSource
import java.io.IOException

class MihonNovelSupportTest {
    @Test fun chapterDiscoveryTransformsTheSlugBeforeRequestingText() = runTest {
        val chapter = chapter("/chapter/fixture-42").also {
            it.name = "Original chapter"
            it.chapter_number = 42f
            it.date_upload = 1710000000000L
            it.scanlator = "Original edition"
        }
        var received: Page? = null
        val source = TextFixture(
            pageList = { original ->
                assertSame(chapter, original)
                assertEquals("/chapter/fixture-42", original.url)
                assertEquals("Original chapter", original.name)
                assertEquals(42f, original.chapter_number)
                assertEquals(1710000000000L, original.date_upload)
                assertEquals("Original edition", original.scanlator)
                listOf(Page(7, "https://fixture.invalid/api/getchapter/fixture-42/"))
            },
            text = { page ->
                received = page
                "<p>Chapter text</p><img src='../images/1.png'>"
            },
        )
        val html = fetchMihonChapterHtml(source, chapter, "https://fixture.invalid/chapter/fixture-42")
        assertEquals(7, received!!.index)
        assertEquals("https://fixture.invalid/api/getchapter/fixture-42/", received!!.url)
        assertNull(received!!.imageUrl)
        assertTrue(html.contains("Chapter text"))
        assertTrue(html.contains("https://fixture.invalid/api/getchapter/images/1.png"))
        assertEquals("/chapter/fixture-42", chapter.url)
    }

    @Test fun textPagesRetainSourceOrderAndResolveTheirOwnIllustrations() = runTest {
        val pages = listOf(Page(9, "/text/one/"), Page(2, "https://images.invalid/text/two/"))
        val requested = ArrayList<Page>()
        val source = TextFixture(pageList = { pages }, text = { page ->
            requested.add(page)
            "<p>${page.index}</p><img src='figure.jpg'>"
        })
        val html = fetchMihonChapterHtml(source, chapter(), "https://fixture.invalid/book/chapter")
        assertEquals(pages, requested)
        assertTrue(html.indexOf("<p>9</p>") < html.indexOf("<p>2</p>"))
        assertTrue(html.contains("https://fixture.invalid/text/one/figure.jpg"))
        assertTrue(html.contains("https://images.invalid/text/two/figure.jpg"))
    }

    @Test fun discoveryErrorsNeverFallBackToTheRawChapterUrl() = runTest {
        for (failure in listOf(IOException("Offline"), UnsupportedOperationException("No parser"))) {
            var textCalls = 0
            val source = TextFixture(pageList = { throw failure }, text = { textCalls++; "<p>Wrong path</p>" })
            assertSame(failure, runCatching { fetchMihonChapterHtml(source, chapter(), "") }.exceptionOrNull())
            assertEquals(0, textCalls)
        }
    }

    @Test fun initializerErrorsInDiscoveryAndTextBecomeCopyableSourceFailures() = runTest {
        for (duringDiscovery in listOf(true, false)) {
            val failure = ExceptionInInitializerError(IllegalStateException("Missing extension binding"))
            val source = TextFixture(
                pageList = { if (duringDiscovery) throw failure else listOf(Page(0)) },
                text = { throw failure },
            )
            val caught = runCatching { fetchMihonChapterHtml(source, chapter(), "") }.exceptionOrNull()
            assertTrue(caught is IOException)
            assertSame(failure, caught!!.cause)
        }
    }

    @Test fun emptyAndExcessivePageListsFailBeforeTextRequests() = runTest {
        for (pages in listOf(emptyList(), List(65) { Page(it, "/$it") })) {
            var calls = 0
            val source = TextFixture(pageList = { pages }, text = { calls++; "<p>Text</p>" })
            assertTrue(runCatching { fetchMihonChapterHtml(source, chapter(), "") }.exceptionOrNull() is IOException)
            assertEquals(0, calls)
        }
    }

    @Test fun aggregateTextLimitAndLaterPageFailureRejectPartialChapters() = runTest {
        var calls = 0
        val source = TextFixture(pageList = { List(3) { Page(it) } }, text = {
            calls++
            "x".repeat(4 * 1024 * 1024)
        })
        assertTrue(runCatching { fetchMihonChapterHtml(source, chapter(), "") }.exceptionOrNull() is IOException)
        assertEquals(2, calls)
        val failure = IOException("Second page failed")
        val failed = TextFixture(pageList = { listOf(Page(0), Page(1)) }, text = { page ->
            if (page.index == 1) throw failure
            "<p>First page</p>"
        })
        assertSame(failure, runCatching { fetchMihonChapterHtml(failed, chapter(), "") }.exceptionOrNull())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellationAfterATextResponseStopsBeforeTheNextPage() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = TextFixture(pageList = { listOf(Page(0), Page(1)) }, text = {
            calls++
            withContext(NonCancellable) { release.await() }
            "<p>Text</p>"
        })
        val request = async { fetchMihonChapterHtml(source, chapter(), "") }
        runCurrent()
        assertEquals(1, calls)
        request.cancel()
        release.complete(Unit)
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun blankTextFailureAndCancellationAreNotHidden() = runTest {
        for (raw in listOf("", " \n\t")) {
            assertThrows(IOException::class.java) { normalizeMihonChapterHtml(raw, "https://fixture.invalid/") }
        }
        val failure = IOException("Fixture failure")
        var cancellation = false
        val source = TextFixture(text = {
            if (cancellation) throw CancellationException("Cancelled fixture")
            throw failure
        })
        assertSame(failure, runCatching { fetchMihonChapterHtml(source, chapter(), "") }.exceptionOrNull())
        cancellation = true
        assertTrue(runCatching { fetchMihonChapterHtml(source, chapter(), "") }.exceptionOrNull() is CancellationException)
    }

    @Test fun onlyExplicitlyInspectedWrappersSkipChapterDiscovery() {
        assertTrue(usesDirectMihonNovelText("eu.kanade.tachiyomi.novelextension.all.shosetsu.ShosetsuExtensionAdapter"))
        assertTrue(usesDirectMihonNovelText("eu.kanade.tachiyomi.novelextension.en.tsundoku.TsundokuDocs"))
        for (name in listOf("eu.kanade.tachiyomi.novelextension.en.wuxiaclick.WuxiaClick",
            "eu.kanade.tachiyomi.novelextension.all.ireader.HttpSourceAdapter",
            "eu.kanade.tachiyomi.source.online.ParsedNovelSource",
            "eu.kanade.tachiyomi.novelextension.all.shosetsu.UninspectedAdapter")) {
            assertFalse(name, usesDirectMihonNovelText(name))
        }
    }

    private fun chapter(url: String = "/chapter") = SChapter.create().also {
        it.url = url
        it.name = "Fixture"
    }

    private class TextFixture(
        val pageList: suspend (SChapter) -> List<Page> = { listOf(Page(0, it.url)) },
        val text: suspend (Page) -> String,
    ) : Source, NovelSource {
        override val id = 77L
        override val name = "Novel fixture"
        override suspend fun getPageList(chapter: SChapter) = pageList(chapter)
        override suspend fun fetchPageText(page: Page) = text(page)
    }

    @Test fun sourceIdentityIsIndependentOfTextAndSettingsCapabilities() {
        val manga = MihonMangaSource("fixture.novel", 42)
        val novel = MihonMangaSource("fixture.novel", 42, isNovelSource = true, isConfigurable = true)
        val settings = MihonMangaSource("fixture.novel", 43, isNovelSource = true, isConfigurable = true, isCatalogue = false)
        assertEquals(manga, novel)
        assertEquals(manga.name, novel.name)
        assertEquals(manga.hashCode(), novel.hashCode())
        assertTrue(novel.hasChapterText())
        assertFalse(manga.hasChapterText())
        assertFalse(settings.hasChapterText())
        assertTrue(LnReaderMangaSource("fixture", "Fixture").hasChapterText())
        assertTrue(UnresolvedLnReaderSource("missing-fixture").hasChapterText())
    }

    @Test fun textOnlyMihonAndLnReaderDetailsNeverRequestImagePageThumbnails() {
        fun supports(source: org.koitharu.kotatsu.parsers.model.MangaSource): Boolean {
            val manga = org.koitharu.kotatsu.parsers.model.Manga(id = 431, title = "Fixture", altTitles = emptySet(),
                url = "/fixture", publicUrl = "https://fixture.invalid/fixture", rating = -1f, contentRating = null,
                coverUrl = null, tags = emptySet(), state = null, authors = emptySet(), source = source)
            return org.koitharu.kotatsu.details.data.MangaDetails(manga).supportsPageThumbnails
        }
        assertFalse(supports(MihonMangaSource("fixture.thumbnail", 731, isNovelSource = true)))
        assertTrue(supports(MihonMangaSource("fixture.thumbnail", 732)))
        assertFalse(supports(LnReaderMangaSource("fixture-thumbnail", "Fixture")))
        assertFalse(supports(UnresolvedLnReaderSource("missing-thumbnail")))
        assertTrue(supports(org.koitharu.kotatsu.parsers.model.MangaParserSource.MANGADEX))
    }

    @Test fun onlyInspectedOptionalRuntimeNamespacesCanFallBackToTheirApk() {
        assertTrue(allowsOptionalExtensionClass("eu.kanade.tachiyomi.source.online.ParsedNovelSource"))
        assertTrue(allowsOptionalExtensionClass("kotlinx.datetime.LocalDate"))
        assertTrue(allowsOptionalExtensionClass("kotlinx.io.Buffer"))
        for (shared in listOf("eu.kanade.tachiyomi.source.Source", "eu.kanade.tachiyomi.source.model.Page",
            "eu.kanade.tachiyomi.network.NetworkHelper", "kotlinx.coroutines.Job", "kotlinx.serialization.KSerializer",
            "androidx.preference.Preference", "kotlin.collections.List")) {
            assertFalse(shared, allowsOptionalExtensionClass(shared))
        }
    }
}
