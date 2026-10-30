package org.koitharu.kotatsu.scrobbling.v2.bulk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerProviderError
import org.koitharu.kotatsu.scrobbling.v2.TrackerRawProgress
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import org.koitharu.kotatsu.scrobbling.v2.TrackerTitle
import java.io.File
import java.io.IOException

class BulkTrackerEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = BulkTrackerAccount("COMICK", "opaque-account", 7, setOf("library:read", "library:write"))

    @Test
    fun existingLinksRemainRetainedAndNoRequestRunsUntilExplicitSearchAndApply() = runTest {
        val f = fixture(2)
        f.access.links[2] = BulkTrackerLink("old", "old-entry", "v2:3", 3)
        val draft = f.engine.match(account, f.access.locals.values.toList())
        assertEquals(listOf(BulkTrackerItemState.UNRESOLVED, BulkTrackerItemState.RETAINED), draft.items.map { it.state })
        assertEquals(0, f.access.reads)
        assertEquals(0, f.access.creates)
        f.engine.search(draft.id)
        assertEquals(3, f.access.queries.size)
        assertTrue(f.access.queries.none { it.startsWith("Title 2") })
        f.engine.apply(draft.id)
        assertEquals(0, f.access.creates)
        assertEquals(0, f.access.commits.size)
    }

    @Test
    fun missingEntryWithoutSeparateAdditionConsentKeepsCandidatesAndNeverCreates() = runTest {
        val f = fixture()
        val draft = matched(f)
        val item = f.engine.draft(draft.id).items.single()
        f.engine.choose(draft.id, item.id, item.candidates.first(), null, null, false)
        f.engine.apply(draft.id)
        val pending = f.engine.draft(draft.id).items.single()
        assertEquals(BulkTrackerItemState.FAILED, pending.state)
        assertEquals(item.candidates, pending.candidates)
        assertFalse(pending.allowCreate)
        assertEquals(0, f.access.creates)
    }

    @Test
    fun adoptionPreservesFractionalProgressRatingNotesAndIsIdempotent() = runTest {
        val f = fixture()
        val draft = matched(f)
        val item = f.engine.draft(draft.id).items.single()
        val candidate = item.candidates.first()
        val remote = remote(candidate.mediaId).copy(progress = TrackerRawProgress("12.50", "2.5"), rating = "8.75",
            notes = "kept", status = "COMPLETED")
        f.access.entries[candidate.mediaId] = remote
        f.engine.choose(draft.id, item.id, candidate, null, null, false)
        f.engine.apply(draft.id)
        f.engine.apply(draft.id)
        assertEquals(listOf(remote), f.access.commits.values.toList())
        assertEquals(0, f.access.creates)
        assertEquals(BulkTrackerItemState.APPLIED, f.engine.draft(draft.id).items.single().state)
    }

    @Test
    fun unknownCreationOutcomeSurvivesRecreationAndOnlyReadsBack() = runTest {
        val f = fixture()
        val draft = confirmed(f)
        f.access.failAfterCreate = IOException("Authorization: Bearer super-secret")
        f.engine.apply(draft.id)
        val interrupted = f.engine.draft(draft.id).items.single()
        assertEquals(BulkTrackerItemState.READBACK_REQUIRED, interrupted.state)
        assertFalse(interrupted.error.orEmpty().contains("super-secret"))
        assertEquals(1, f.access.creates)
        val recreated = BulkTrackerEngine(BulkTrackerStore(f.file), f.access) { 1000L }
        f.access.failAfterCreate = null
        recreated.apply(draft.id)
        assertEquals(1, f.access.creates)
        assertEquals(1, f.access.commits.size)
        assertEquals(BulkTrackerItemState.APPLIED, recreated.draft(draft.id).items.single().state)
    }

    @Test
    fun cancellationAfterRemoteSuccessIsDurablyReadbackOnly() = runTest {
        val f = fixture()
        val draft = confirmed(f)
        f.access.failAfterCreate = CancellationException("canceled")
        try { f.engine.apply(draft.id); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(BulkTrackerItemState.READBACK_REQUIRED, f.engine.draft(draft.id).items.single().state)
        val recreated = BulkTrackerEngine(BulkTrackerStore(f.file), f.access) { 1000L }
        f.access.failAfterCreate = null
        recreated.check(draft.id, draft.items.single().id)
        assertEquals(1, f.access.creates)
        assertEquals(1, f.access.commits.size)
    }

    @Test
    fun missingReadbackRevokesAdditionConsentAndRequiresFreshReview() = runTest {
        val f = fixture()
        val draft = confirmed(f)
        f.access.failAfterCreate = IOException("unknown")
        f.engine.apply(draft.id)
        f.access.entries.clear()
        f.access.failAfterCreate = null
        f.engine.check(draft.id, draft.items.single().id)
        val item = f.engine.draft(draft.id).items.single()
        assertEquals(BulkTrackerItemState.FAILED, item.state)
        assertFalse(item.allowCreate)
        f.engine.apply(draft.id)
        assertEquals(1, f.access.creates)
        f.engine.choose(draft.id, item.id, requireNotNull(item.chosen), null, null, true)
        f.engine.apply(draft.id)
        assertEquals(2, f.access.creates)
    }

    @Test
    fun sessionRebindKeepsUncertaintyAndRevokesUnsentConsentWithoutCrossingOwners() = runTest {
        val f = fixture()
        val draft = confirmed(f)
        f.access.current = account.copy(revision = 8)
        try { f.engine.apply(draft.id); fail("Stale session accepted") } catch (_: IllegalStateException) { }
        try { f.engine.rebind(draft.id, account.copy(account = "another")); fail("Wrong owner accepted") }
        catch (_: IllegalArgumentException) { }
        f.engine.rebind(draft.id, f.access.current)
        val rebound = f.engine.draft(draft.id).items.single()
        assertEquals(BulkTrackerItemState.SUGGESTED, rebound.state)
        assertFalse(rebound.allowCreate)
        f.engine.apply(draft.id)
        assertEquals(0, f.access.creates)
    }

    @Test
    fun sourceEditionAndLinkChangesAfterConfirmationBlockWrites() = runTest {
        val f = fixture()
        val draft = confirmed(f)
        f.access.locals[1] = f.access.locals.getValue(1).copy(source = "other-source")
        f.engine.apply(draft.id)
        assertEquals(BulkTrackerItemState.FAILED, f.engine.draft(draft.id).items.single().state)
        assertEquals(0, f.access.creates)
        val g = fixture()
        val other = confirmed(g)
        g.access.beforeCreate = { g.access.links[1] = BulkTrackerLink("relinked", "entry", "v2:8", 8) }
        g.engine.apply(other.id)
        assertEquals(0, g.access.creates)
        assertEquals(BulkTrackerItemState.READBACK_REQUIRED, g.engine.draft(other.id).items.single().state)
    }

    @Test
    fun oneLocalFailureKeepsOtherSuccessAndAllUnresolvedCandidates() = runTest {
        val f = fixture(2)
        val draft = matched(f)
        for (item in f.engine.draft(draft.id).items) {
            val candidate = item.candidates.first()
            f.access.entries[candidate.mediaId] = remote(candidate.mediaId)
            f.engine.choose(draft.id, item.id, candidate, null, null, false)
        }
        f.access.failCommitFor = 1
        f.engine.apply(draft.id)
        val items = f.engine.draft(draft.id).items
        assertEquals(BulkTrackerItemState.READY_TO_LINK, items[0].state)
        assertEquals(BulkTrackerItemState.APPLIED, items[1].state)
        assertTrue(items.all { it.candidates.isNotEmpty() })
        f.access.failCommitFor = null
        f.engine.apply(draft.id)
        assertEquals(setOf(1L, 2L), f.access.commits.keys)
        assertEquals(0, f.access.creates)
    }

    @Test
    fun failedNextPageRetainsPartialListCursorAndUnresolvedImport() = runTest {
        val f = fixture()
        f.access.pages[null] = BulkTrackerPage(listOf(BulkTrackerRemote.from(remote("first"))), "next")
        f.engine.loadLibrary(account, false)
        f.access.pageError = IOException("offline")
        try { f.engine.loadLibrary(account, true); fail("Page failure swallowed") } catch (_: IOException) { }
        val partial = requireNotNull(f.engine.library(account))
        assertEquals("next", partial.nextCursor)
        assertFalse(partial.complete)
        assertEquals(listOf("first"), partial.entries.map { it.candidate.mediaId })
        val draft = f.engine.importList(account, partial.entries)
        assertNull(draft.items.single().local)
        f.engine.apply(draft.id)
        assertEquals(0, f.access.creates)
        assertTrue(f.access.commits.isEmpty())
        val restored = BulkTrackerEngine(BulkTrackerStore(f.file), f.access) { 1000L }
        assertEquals(partial, restored.library(account))
    }

    @Test
    fun boundsAndInvalidJournalFailBeforeAnyWorkAndKeepPriorBytes() = runTest {
        val f = fixture(101)
        try { f.engine.match(account, f.access.locals.values.toList()); fail("101 selected accepted") }
        catch (_: IllegalArgumentException) { }
        assertFalse(f.file.exists())
        f.engine.match(account, f.access.locals.values.take(100))
        val bytes = f.file.readBytes()
        try { f.store.mutate { it.copy(drafts = List(33) { index -> it.drafts.first().copy(id = "draft$index") }) }; fail("Unbounded drafts") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, f.file.readBytes())
        val corrupt = temporary.newFile("corrupt.json").apply { writeText("{broken") }
        try { BulkTrackerStore(corrupt).current(); fail("Corrupt journal accepted") } catch (_: Exception) { }
        assertEquals("{broken", corrupt.readText())
        assertEquals(0, f.access.creates)
    }

    @Test
    fun uncertaintyBlocksAnotherDraftForTheSameAccountAndWork() = runTest {
        val f = fixture()
        val first = confirmed(f)
        f.access.failAfterCreate = IOException("unknown")
        f.engine.apply(first.id)
        f.access.entries.clear()
        f.access.failAfterCreate = null
        val second = confirmed(f)
        f.engine.apply(second.id)
        assertEquals(1, f.access.creates)
        assertEquals(BulkTrackerItemState.READBACK_REQUIRED, f.engine.draft(first.id).items.single().state)
        assertEquals(BulkTrackerItemState.FAILED, f.engine.draft(second.id).items.single().state)
    }

    @Test
    fun rateLimitedUncertainAdditionRequiresCooldownBeforeManualReadback() = runTest {
        val f = fixture()
        val draft = confirmed(f)
        f.access.failAfterCreate = TrackerProviderError("COMICK", 429, 0, "secret")
        f.engine.apply(draft.id)
        val reads = f.access.reads
        assertEquals(61_000L, f.engine.draft(draft.id).retryAt)
        try { f.engine.check(draft.id, draft.items.single().id); fail("Cooldown ignored") }
        catch (_: IllegalStateException) { }
        assertEquals(reads, f.access.reads)
        f.access.nowMillis = 61_001L
        f.access.failAfterCreate = null
        f.engine.check(draft.id, draft.items.single().id)
        assertEquals(1, f.access.creates)
        assertEquals(BulkTrackerItemState.APPLIED, f.engine.draft(draft.id).items.single().state)
    }

    @Test
    fun knownAdaptationsAreSeparatedAndAmbiguousTitlesRemainUnconfirmed() = runTest {
        val f = fixture()
        f.access.candidateOverride = listOf("manga-a" to "MANGA", "manga-b" to "MANGA", "novel" to "NOVEL")
            .map { (id, format) -> BulkTrackerCandidate(id, "Same title", emptySet(), "https://tracker.test/$id", format) }
        val draft = matched(f)
        val item = f.engine.draft(draft.id).items.single()
        assertEquals(setOf("manga-a", "manga-b"), item.candidates.map { it.mediaId }.toSet())
        assertNull(item.chosen)
        f.engine.apply(draft.id)
        assertEquals(0, f.access.creates)
        assertTrue(f.access.commits.isEmpty())
    }

    @Test
    fun persistedCreatingStateRecoversReadbackOnlyWithoutAnAutomaticRequest() = runTest {
        val f = fixture()
        val draft = confirmed(f)
        f.store.mutate { journal -> journal.copy(drafts = journal.drafts.map { saved -> saved.copy(items = saved.items.map {
            it.copy(state = BulkTrackerItemState.CREATING)
        }) }) }
        val recreated = BulkTrackerEngine(BulkTrackerStore(f.file), f.access) { f.access.nowMillis }
        val recovered = recreated.draft(draft.id).items.single()
        assertEquals(BulkTrackerItemState.READBACK_REQUIRED, recovered.state)
        assertEquals(0, f.access.reads)
        val candidate = requireNotNull(recovered.chosen)
        f.access.entries[candidate.mediaId] = remote(candidate.mediaId)
        recreated.check(draft.id, recovered.id)
        assertEquals(0, f.access.creates)
        assertEquals(1, f.access.commits.size)
    }

    @Test
    fun partialSearchFailureKeepsCandidatesAndContinuesIndependentItems() = runTest {
        val f = fixture(2)
        f.access.failQueryNumber = 2
        val draft = matched(f)
        val items = f.engine.draft(draft.id).items
        assertEquals(BulkTrackerItemState.FAILED, items.first().state)
        assertTrue(items.first().candidates.isNotEmpty())
        assertEquals(BulkTrackerItemState.SUGGESTED, items.last().state)
        assertTrue(items.last().candidates.isNotEmpty())
        assertEquals(0, f.access.creates)
    }

    @Test
    fun favoritesActionSeedIsDurableIdempotentAndNeverStartsANetworkSearch() = runTest {
        val f = fixture()
        val selected = f.access.locals.values.toList()
        val first = f.engine.match(account, selected, "favorite-action-request")
        val recreated = BulkTrackerEngine(BulkTrackerStore(f.file), f.access) { f.access.nowMillis }
        val repeated = recreated.match(account, selected, "favorite-action-request")
        assertEquals(first, repeated)
        assertEquals(1, recreated.drafts(account).size)
        assertTrue(f.access.queries.isEmpty())
        assertEquals(0, f.access.reads)
        assertEquals(0, f.access.creates)
    }

    @Test fun sourceRestrictionAfterConfirmationBlocksRemoteCreation() = runTest {
        val f = fixture()
        val local = f.access.locals.getValue(1)
        val candidate = BulkTrackerCandidate("chosen", "Chosen", emptySet(), "https://tracker.test/chosen", "MANGA")
        val draft = f.engine.prepareSearchLink(account, local, candidate, "selected-source")
        f.engine.choose(draft.id, draft.items.single().id, candidate, local, null, true)
        f.access.readable = false
        f.engine.apply(draft.id)
        assertEquals(0, f.access.creates)
        assertTrue(f.access.commits.isEmpty())
        assertEquals(BulkTrackerItemState.FAILED, f.engine.draft(draft.id).items.single().state)
    }

    private suspend fun matched(f: Fixture): BulkTrackerDraft = f.engine.match(account, f.access.locals.values.toList())
        .also { f.engine.search(it.id) }

    @Test fun singleSearchPairPreparationIsReadOnlyDurableAndNeedsConfirmation() = runTest {
        val f = fixture()
        val candidate = BulkTrackerCandidate("opaque-media", "Chosen title", emptySet(), "https://tracker.test/title", "MANGA")
        val local = f.access.locals.getValue(1)
        val draft = f.engine.prepareSearchLink(account, local, candidate, "search-pair")
        assertEquals(0, f.access.reads); assertEquals(0, f.access.creates); assertTrue(f.access.commits.isEmpty())
        assertEquals(BulkTrackerItemState.UNRESOLVED, draft.items.single().state)
        val recreated = BulkTrackerEngine(BulkTrackerStore(f.file), f.access) { f.access.nowMillis }
        assertEquals(draft, recreated.prepareSearchLink(account, local, candidate, "search-pair"))
        recreated.apply(draft.id)
        assertEquals(0, f.access.creates)
        assertTrue(f.access.commits.isEmpty())
        f.access.entries[candidate.mediaId] = remote(candidate.mediaId).copy(progress = TrackerRawProgress("12.50"), notes = "keep")
        recreated.choose(draft.id, draft.items.single().id, candidate, local, null, false)
        recreated.apply(draft.id)
        assertEquals("12.50", f.access.commits.getValue(1).progress?.number)
        assertEquals("keep", f.access.commits.getValue(1).notes)
        assertEquals(0, f.access.creates)
    }

    @Test fun reusedSearchPairCannotSwitchAccountOrEdition() = runTest {
        val f = fixture()
        val local = f.access.locals.getValue(1)
        val candidate = BulkTrackerCandidate("opaque-media", "Chosen", emptySet(), "https://tracker.test/a", null)
        f.engine.prepareSearchLink(account, local, candidate, "saved-search")
        try { f.engine.prepareSearchLink(account, local.copy(url = "/other"), candidate, "saved-search"); fail() }
        catch (_: IllegalStateException) { }
        f.access.current = account.copy(account = "other")
        try { f.engine.prepareSearchLink(account, local, candidate, "another-search"); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, f.access.reads); assertEquals(0, f.access.creates)
    }

    private suspend fun confirmed(f: Fixture): BulkTrackerDraft = matched(f).also { draft ->
        val item = f.engine.draft(draft.id).items.single()
        f.engine.choose(draft.id, item.id, item.candidates.first(), null, null, true)
    }

    private fun fixture(count: Int = 1): Fixture {
        val directory = temporary.newFolder()
        val file = File(directory, "state.json")
        val store = BulkTrackerStore(file)
        val access = FakeAccess()
        repeat(count) { index ->
            val id = index + 1L
            access.locals[id] = BulkTrackerLocal(id, "Title $id", setOf("Alias $id", "Other $id", "Fourth $id"),
                "source", "/$id", "https://source.test/$id", "MANGA", "en")
        }
        return Fixture(file, store, access, BulkTrackerEngine(store, access) { access.nowMillis })
    }

    private data class Fixture(val file: File, val store: BulkTrackerStore, val access: FakeAccess,
        val engine: BulkTrackerEngine)

    private fun remote(id: String) = TrackerRemoteEntry(TrackerReference(TrackerOwner(account.provider, account.account),
        id, "entry-$id"), TrackerTitle(id, "Remote $id", url = "https://tracker.test/$id", format = "MANGA"),
        "PLANNING", TrackerRawProgress("0"))

    private inner class FakeAccess : BulkTrackerAccess {
        var current = account
        val locals = linkedMapOf<Long, BulkTrackerLocal>()
        val links = hashMapOf<Long, BulkTrackerLink>()
        val entries = hashMapOf<String, TrackerRemoteEntry>()
        val pages = hashMapOf<String?, BulkTrackerPage>()
        val queries = mutableListOf<String>()
        val commits = linkedMapOf<Long, TrackerRemoteEntry>()
        var readable = true
        override suspend fun checkReadable(local: BulkTrackerLocal) { check(readable) { "Source is restricted" } }
        var reads = 0
        var creates = 0
        var failAfterCreate: Exception? = null
        var failCommitFor: Long? = null
        var beforeCreate: () -> Unit = {}
        var pageError: Exception? = null
        var nowMillis = 1000L
        var candidateOverride: List<BulkTrackerCandidate>? = null
        var failQueryNumber: Int? = null
        override fun check(account: BulkTrackerAccount) { check(account == current) { "account changed" } }
        override suspend fun local(id: Long) = locals[id]
        override suspend fun link(account: BulkTrackerAccount, id: Long): BulkTrackerLink? { check(account); return links[id] }
        override suspend fun search(account: BulkTrackerAccount, query: String): List<BulkTrackerCandidate> {
            check(account)
            queries += query
            if (queries.size == failQueryNumber) throw IOException("Search failed after an earlier result")
            candidateOverride?.let { return it }
            return listOf(BulkTrackerCandidate("work-${query.substringAfterLast(' ')}", "Remote $query", emptySet(),
                "https://tracker.test/${query.substringAfterLast(' ')}", "MANGA"))
        }
        override suspend fun entry(account: BulkTrackerAccount, mediaId: String,
            beforeRequest: suspend () -> Unit): TrackerRemoteEntry? {
            check(account); beforeRequest(); reads++; return entries[mediaId]
        }
        override suspend fun createStatus(account: BulkTrackerAccount): String? { check(account); return "PLANNING" }
        override suspend fun create(account: BulkTrackerAccount, mediaId: String, status: String, beforeSend: suspend () -> Unit) {
            check(account); beforeCreate(); beforeSend(); creates++; entries[mediaId] = remote(mediaId)
            failAfterCreate?.let { throw it }
        }
        override suspend fun commit(account: BulkTrackerAccount, item: BulkTrackerItem, entry: TrackerRemoteEntry) {
            check(account)
            val local = requireNotNull(item.local)
            check(links[local.id] == item.expectedLink)
            if (local.id == failCommitFor) throw IOException("Local write failed")
            commits[local.id] = entry
            links[local.id] = BulkTrackerLink(entry.reference.mediaId, entry.reference.listEntryId, "v2:9", 9)
        }
        override suspend fun page(account: BulkTrackerAccount, cursor: String?): BulkTrackerPage {
            check(account); pageError?.let { throw it }; return requireNotNull(pages[cursor])
        }
    }
}
