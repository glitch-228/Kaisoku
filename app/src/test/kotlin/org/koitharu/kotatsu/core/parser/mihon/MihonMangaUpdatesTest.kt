package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import rx.Observable
import java.io.IOException

class MihonMangaUpdatesTest {

    @Test
    fun inheritedCombinedOverrideLoadsBothAndPreservesOriginalChapterMetadata() = runTest {
        val originalChapter = chapter().apply {
            memo = JsonObject(mapOf("imageToken" to JsonPrimitive("fixture-token")))
        }
        var calls = 0
        val source = object : CombinedSource() {
            override suspend fun update(manga: SManga, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
                calls++
                assertTrue(fetchDetails)
                assertTrue(fetchChapters)
                return SMangaUpdate(manga, listOf(originalChapter))
            }
        }
        val seed = manga()
        val result = updates(source).load(seed)
        assertSame(seed, result.manga)
        assertSame(originalChapter, result.chapters.single())
        assertEquals("fixture-token", result.chapters.single().memo["imageToken"]?.let { (it as JsonPrimitive).content })
        assertEquals(1, calls)
    }

    @Test
    fun combinedUpdateRepairsMissingUrlTitleAndCoverWithoutReplacingItsMetadata() = runTest {
        val details = SManga.create().apply {
            author = "Source author"
            memo = JsonObject(mapOf("work" to JsonPrimitive(42)))
        }
        val source = combined { SMangaUpdate(details, emptyList()) }
        val seed = manga()
        val result = updates(source).load(seed)
        assertSame(details, result.manga)
        assertEquals(seed.url, result.manga.url)
        assertEquals(seed.title, result.manga.title)
        assertEquals(seed.thumbnail_url, result.manga.thumbnail_url)
        assertEquals("Source author", result.manga.author)
        assertEquals(details.memo, result.manga.memo)
    }

    @Test
    fun combinedFailureIsMappedWithoutCallingUnsupportedLegacyMethods() = runTest {
        val original = IOException("Temporary failure")
        val mapped = IOException("Actionable source error", original)
        var calls = 0
        val source = combined { calls++; throw original }
        val loader = MihonMangaUpdates(source) { assertSame(original, it); mapped }
        assertSame(mapped, runCatching { loader.load(manga()) }.exceptionOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun combinedCancellationIsNotMappedOrRetried() = runTest {
        val cancellation = CancellationException("Title changed")
        val source = combined { throw cancellation }
        val loader = MihonMangaUpdates(source) { error("Cancellation must not become a source error") }
        assertSame(cancellation, runCatching { loader.load(manga()) }.exceptionOrNull())
    }

    @Test
    fun concurrentUpdatesForTheSameTitleWaitInsteadOfEnteringTheSourceTogether() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = combined { seed ->
            if (++calls == 1) {
                started.complete(Unit)
                release.await()
            }
            SMangaUpdate(seed, emptyList())
        }
        val loader = updates(source)
        val first = async { loader.load(manga()) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { loader.load(manga()) }
        assertEquals(1, calls)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, calls)
    }

    @Test
    fun differentTitlesCanStillUpdateConcurrently() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = combined { seed ->
            if (seed.url == "/first") {
                started.complete(Unit)
                release.await()
            }
            SMangaUpdate(seed, emptyList())
        }
        val loader = updates(source)
        val first = async { loader.load(manga("/first")) }
        started.await()
        val second = async { loader.load(manga("/second")) }
        assertEquals("/second", withTimeout(1_000) { second.await() }.manga.url)
        release.complete(Unit)
        first.await()
    }

    @Test
    fun cancellingAnUpdateReleasesTheTitleForTheNextRequest() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = combined { seed ->
            if (++calls == 1) {
                started.complete(Unit)
                release.await()
            }
            SMangaUpdate(seed, emptyList())
        }
        val loader = updates(source)
        val first = launch { loader.load(manga()) }
        started.await()
        first.cancelAndJoin()
        withTimeout(1_000) { loader.load(manga()) }
        assertEquals(2, calls)
    }

    @Test
    fun failedCombinedUpdateReleasesTheTitleForRetry() = runTest {
        var calls = 0
        val source = combined { seed ->
            if (++calls == 1) throw IOException("Temporary failure")
            SMangaUpdate(seed, listOf(chapter()))
        }
        val loader = updates(source)
        assertTrue(runCatching { loader.load(manga()) }.exceptionOrNull() is IOException)
        assertEquals(1, withTimeout(1_000) { loader.load(manga()) }.chapters.size)
    }

    @Test
    fun legacySourcesStillLoadChaptersUsingTheUpdatedDetails() = runTest {
        val details = manga("/updated")
        val originalChapter = chapter()
        val source = LegacySource(
            details = { details },
            chapters = { assertSame(details, it); listOf(originalChapter) },
        )
        val result = updates(source).load(manga())
        assertSame(details, result.manga)
        assertSame(originalChapter, result.chapters.single())
    }

    @Test
    fun legacyBlankMetadataUsesTheCatalogIdentity() = runTest {
        val details = SManga.create().apply { url = ""; title = ""; thumbnail_url = "" }
        val seed = manga()
        val source = LegacySource(
            details = { details },
            chapters = { assertEquals(seed.url, it.url); listOf(chapter()) },
        )
        val result = updates(source).load(seed)
        assertEquals(seed.url, result.manga.url)
        assertEquals(seed.title, result.manga.title)
        assertEquals(seed.thumbnail_url, result.manga.thumbnail_url)
    }

    @Test
    fun legacyDetailsFailureStillLoadsAvailableChapters() = runTest {
        val seed = manga()
        val source = LegacySource(
            details = { throw IOException("Details unavailable") },
            chapters = { assertSame(seed, it); listOf(chapter()) },
        )
        val result = updates(source).load(seed)
        assertSame(seed, result.manga)
        assertEquals(1, result.chapters.size)
    }

    @Test
    fun legacyEmptyOrFailedDetailsUrlFallsBackToTheOriginalChapterUrl() = runTest {
        for (failDetailsUrl in listOf(false, true)) {
            val visited = mutableListOf<String>()
            val source = LegacySource(
                details = { manga("/changed") },
                chapters = {
                    visited += it.url
                    if (it.url == "/changed") {
                        if (failDetailsUrl) throw IOException("Wrong chapter endpoint")
                        emptyList()
                    } else {
                        listOf(chapter())
                    }
                },
            )
            assertEquals(1, updates(source).load(manga()).chapters.size)
            assertEquals(listOf("/changed", "/work"), visited)
        }
    }

    @Test
    fun legitimateEmptyLegacyChaptersRemainAnEmptyResult() = runTest {
        val source = LegacySource(chapters = { emptyList() })
        assertTrue(updates(source).load(manga()).chapters.isEmpty())
    }

    @Test
    fun failedLegacyChapterLoadsKeepTheirMappedError() = runTest {
        val original = IOException("Unavailable chapters")
        val mapped = IOException("Mapped error", original)
        val source = LegacySource(chapters = { throw original })
        val loader = MihonMangaUpdates(source) { mapped }
        assertSame(mapped, runCatching { loader.load(manga()) }.exceptionOrNull())
    }

    @Test
    fun legacyAuthenticationAndCancellationStopBeforeFetchingChapters() = runTest {
        for (failure in listOf(AuthRequiredException(TestMangaSource), CancellationException("Cancelled"))) {
            val source = LegacySource(
                details = { throw failure },
                chapters = { error("Must not fetch after authentication or cancellation failure") },
            )
            assertSame(failure, runCatching { updates(source).load(manga()) }.exceptionOrNull())
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyRxSourcesUseTheirExistingFetchApi() = runTest {
        val seed = manga()
        val originalChapter = chapter()
        val source = object : Source {
            override val id = 42L
            override val name = "Legacy Rx"
            override fun fetchMangaDetails(manga: SManga) = Observable.just(manga)
            override fun fetchChapterList(manga: SManga) = Observable.just(listOf(originalChapter))
        }
        val result = updates(source).load(seed)
        assertSame(seed, result.manga)
        assertSame(originalChapter, result.chapters.single())
    }

    private fun updates(source: Source) = MihonMangaUpdates(source) { it }

    private fun manga(url: String = "/work") = SManga.create().apply {
        this.url = url
        title = "Catalog title"
        thumbnail_url = "https://extension.invalid/cover.jpg"
    }

    private fun chapter() = SChapter.create().apply { url = "/chapter/1"; name = "Chapter 1" }

    private fun combined(block: suspend (SManga) -> SMangaUpdate) = object : CombinedSource() {
        override suspend fun update(manga: SManga, fetchDetails: Boolean, fetchChapters: Boolean) = block(manga)
    }

    private abstract class CombinedSource : Source {
        override val id = 42L
        override val name = "Combined fixture"

        final override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            check(chapters.isEmpty())
            return update(manga, fetchDetails, fetchChapters)
        }

        abstract suspend fun update(manga: SManga, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate

        override suspend fun getMangaDetails(manga: SManga): SManga = error("No legacy details implementation")
        override suspend fun getChapterList(manga: SManga): List<SChapter> = error("No legacy chapter implementation")
    }

    private class LegacySource(
        private val details: suspend (SManga) -> SManga = { it },
        private val chapters: suspend (SManga) -> List<SChapter> = { emptyList() },
    ) : Source {
        override val id = 43L
        override val name = "Legacy fixture"
        override suspend fun getMangaDetails(manga: SManga) = details(manga)
        override suspend fun getChapterList(manga: SManga) = chapters(manga)
    }
}
