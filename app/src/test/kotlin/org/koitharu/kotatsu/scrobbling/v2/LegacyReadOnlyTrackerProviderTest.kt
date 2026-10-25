package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.data.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class LegacyReadOnlyTrackerProviderTest {
    private val connection = TrackerConnection(TrackerOwner("MANGABAKA", "user-opaque-α"), 9)

    @Test fun publicOwnerIsIndependentOfTheLocalHashAndNeverSurvivesASessionChange() {
        val binding = LegacyRawOwnerBinding(connection.owner, -1234, 9)
        assertTrue(binding.matches(connection, -1234, 9))
        assertFalse(binding.matches(connection.copy(owner = TrackerOwner("MANGABAKA", "other")), -1234, 9))
        assertFalse(binding.matches(connection.copy(revision = 10), -1234, 10))
        assertFalse(binding.matches(connection, 1234, 9))
        assertFalse(binding.matches(connection, -1234, 10))
    }

    @Test fun pagesAndEntriesPreserveOpaqueAndFractionalValuesWithoutLegacyRefreshOrMutation() = runTest {
        val repository = Repository(connection.owner)
        val provider = provider(repository)
        val page = provider.libraryPage(connection, null)
        assertEquals(connection.owner, page.entries.single().reference.owner)
        assertEquals("list-2147483648-β", page.entries.single().reference.listEntryId)
        assertEquals("12.50", page.entries.single().progress?.number)
        assertEquals(page.entries.single(), provider.entry(connection, "42"))
        assertEquals(1, repository.pages)
        assertEquals(1, repository.entries)
        assertEquals(0, repository.forbidden)
    }

    @Test fun staleAndWrongOwnerResponsesCannotPopulateTheTools() = runTest {
        val repository = Repository(connection.owner)
        val provider = provider(repository)
        repository.afterPage = { repository.storage.accessToken = "changed" }
        try { provider.libraryPage(connection, null); fail("Stale session must be rejected") }
        catch (_: TrackerSessionChangedException) { }
        repository.afterPage = {}
        repository.entryOwner = TrackerOwner("MANGABAKA", "wrong-owner")
        try { provider.entry(connection, "42"); fail("Wrong owner must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun unsupportedWritesAndNonNumericWorkIdsNeverReachOldMutationContracts() = runTest {
        val repository = Repository(connection.owner)
        val provider = provider(repository)
        assertFalse(TrackerCapability.STATUS_WRITE in provider.capabilities)
        assertEquals("42", provider.search(connection, "fixture").single().mediaId)
        assertEquals("42", provider.metadata(connection, "42").mediaId)
        try { provider.metadata(connection, "opaque-work"); fail("A legacy work ID must be checked") }
        catch (_: IllegalArgumentException) { }
        try { provider.patch(connection, "42", TrackerPatch.statusOnly("PLANNING"), true); fail("Read-only") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(0, repository.forbidden)
    }

    @Test fun explicitlySupportedWritesUseOnlyTheLosslessMethodAndOriginalOpaqueReference() = runTest {
        val repository = Repository(connection.owner).apply { supportsRawWrites = true }
        val provider = provider(repository)
        assertTrue(TrackerCapability.STATUS_WRITE in provider.capabilities)
        assertTrue(TrackerCapability.PROGRESS_WRITE in provider.capabilities)
        val patch = TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("365.5")))
        val after = provider.patch(connection, "42", patch, allowCreate = false)
        assertEquals("list-2147483648-β", repository.writtenReference?.listEntryId)
        assertEquals(connection.owner, repository.writtenReference?.owner)
        assertEquals(patch, repository.writtenPatch)
        assertEquals("365.5", after.progress?.number)
        assertEquals(0, repository.forbidden)
    }

    private fun provider(repository: Repository) = LegacyReadOnlyTrackerProvider("MANGABAKA", repository,
        { connection }, { check(it == connection) })

    private class Repository(private val owner: TrackerOwner) : ScrobblerRepository {
        override val storage = ScrobblerStorage(MemoryPreferences(), MemoryPreferences(), MemoryPreferences(), ScrobblerService.MANGABAKA)
            .apply { accessToken = "fixture"; user = ScrobblerUser(-1234, "Fixture", null, ScrobblerService.MANGABAKA) }
        override val cachedUser get() = storage.user
        override val oauthUrl = ""
        override val isAuthorized = true
        override var supportsRawWrites = false
        var writtenReference: TrackerReference? = null
        var writtenPatch: TrackerPatch? = null
        override suspend fun mutateRawEntry(expected: TrackerReference, patch: TrackerPatch,
            allowCreate: Boolean): TrackerRemoteEntry {
            writtenReference = expected
            writtenPatch = patch
            return remote().copy(progress = (patch.progress as? TrackerField.Set)?.value ?: remote().progress)
        }
        var entryOwner = owner
        var pages = 0
        var entries = 0
        var forbidden = 0
        var afterPage: () -> Unit = {}
        private fun remote() = TrackerRemoteEntry(TrackerReference(entryOwner, "42", "list-2147483648-β"),
            TrackerTitle("42", "Fixture", url = "https://example.test/42"), "reading", TrackerRawProgress("12.50"))
        override suspend fun readRawLibraryPage(cursor: String?): TrackerLibraryPage {
            pages++
            return TrackerLibraryPage(listOf(remote()), null).also { afterPage() }
        }
        override suspend fun readRawEntry(mediaId: Long): TrackerRemoteEntry { entries++; return remote() }
        override suspend fun findManga(query: String, offset: Int) = listOf(ScrobblerManga(42, "Fixture", null, null,
            "https://example.test/42", false))
        override suspend fun getMangaInfo(id: Long) = ScrobblerMangaInfo(id, "Fixture", "", "https://example.test/42", "Description")
        private fun forbidden(): Nothing { forbidden++; error("Legacy side effect must stay unused") }
        override suspend fun fetchLibrary(): List<TrackerLibraryEntry> = forbidden()
        override suspend fun loadUser(): ScrobblerUser = forbidden()
        override suspend fun authorize(code: String?) = forbidden()
        override fun logout() = forbidden()
        override suspend fun unregister(mangaId: Long) = forbidden()
        override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean): Boolean = forbidden()
        override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) = forbidden()
        override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?, setStartDate: Boolean) = forbidden()
    }
}
