package org.koitharu.kotatsu.core.parser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class SavedMetadataMangaRepositoryTest {
    private val saved = Manga(41, "Saved title", emptySet(), "/41", "https://example.org/41", -1f,
        null, null, emptySet(), null, emptySet(), description = "Saved synopsis", source = MangaParserSource.MANGALIB)

    @Test fun pausedMetadataMakesNoAutomaticRequestAndExplicitRefreshStillWorks() = runBlocking {
        var calls = 0
        var evidence = 0
        val remote = object : MangaRepository by EmptyMangaRepository(saved.source) {
            override suspend fun getDetails(manga: Manga) = saved.copy(title = "Remote").also { calls++ }
            override suspend fun refreshDetails(manga: Manga) = getDetails(manga)
            override suspend fun getLightweightMetadata(manga: Manga): Manga = getDetails(manga)
        }
        val repository = SavedMetadataMangaRepository(remote, { saved }, { evidence++ })
        assertEquals(saved, repository.getDetails(saved))
        assertEquals(saved.copy(chapters = null), repository.getLightweightMetadata(saved))
        assertEquals(0, calls)
        assertEquals("Remote", repository.refreshDetails(saved).title)
        assertEquals(1, calls)
        assertEquals(1, evidence)
    }

    @Test fun policyChangedDuringRequestRetainsSavedMetadataAndSuppressesFreshEvidence() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var paused = false
        var evidence = 0
        val remote = object : MangaRepository by EmptyMangaRepository(saved.source) {
            override suspend fun getDetails(manga: Manga): Manga {
                started.complete(Unit)
                release.await()
                return saved.copy(title = "Queued remote result")
            }
        }
        val repository = SavedMetadataMangaRepository(remote, { saved.takeIf { paused } }, { evidence++ })
        val loading = async { repository.getDetails(saved) }
        started.await()
        paused = true
        release.complete(Unit)
        assertEquals(saved, loading.await())
        assertEquals(0, evidence)
    }

    @Test fun unavailableLightweightMetadataStaysAbsent() = runBlocking {
        val repository = SavedMetadataMangaRepository(EmptyMangaRepository(saved.source), { null }, {})
        assertNull(repository.getLightweightMetadata(saved))
    }
}
