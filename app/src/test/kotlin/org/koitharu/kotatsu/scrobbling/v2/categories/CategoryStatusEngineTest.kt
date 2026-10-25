package org.koitharu.kotatsu.scrobbling.v2.categories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.scrobbling.v2.*
import java.io.IOException

class CategoryStatusEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = TrackerOwner("COMICK", "opaque-account")
    private val connection = TrackerConnection(owner, 42, setOf("library:read", "library:write"), "Reader")
    private val a = CategoryStatusBinding(1, 10, "Reading")
    private val b = CategoryStatusBinding(2, 20, "Done")
    private val ref = TrackerReference(owner, "opaque-media", "opaque-entry")
    private val title = CategoryStatusTitle(481, "Fixture", "REMANGA", "/edition", "https://fixture.invalid/title",
        ref, "exact-link-version", listOf(a))
    private val mappings = listOf(CategoryStatusMapping(1, 10, "CURRENT"), CategoryStatusMapping(2, 20, "COMPLETED"))

    @Test fun optInHasNoInitialWritesUnlessSelectedAndRepeatedMembershipsDoNotRepeat() = runBlocking {
        val fixture = Fixture()
        fixture.engine.reconcile(); fixture.engine.dispatch()
        assertEquals(0, fixture.access.reads)
        assertEquals(0, fixture.access.writes)
        val preview = fixture.engine.preview(connection, true, mappings)
        assertEquals(0, fixture.access.reads)
        fixture.engine.confirm(preview.id, emptySet())
        fixture.engine.dispatch()
        assertEquals(0, fixture.access.writes)
        fixture.access.local = title.copy(memberships = listOf(b))
        repeat(3) { fixture.engine.reconcile() }
        assertEquals(1, fixture.engine.view().pending.size)
        val unchanged = fixture.access.remote
        fixture.engine.dispatch()
        assertEquals(1, fixture.access.writes)
        assertEquals(unchanged.copy(status = "COMPLETED"), fixture.access.remote)
        repeat(3) { fixture.engine.reconcile(); fixture.engine.dispatch() }
        assertEquals(1, fixture.access.writes)
        assertTrue(fixture.engine.view().pending.isEmpty())
        fixture.access.local = title.copy(memberships = emptyList())
        fixture.engine.reconcile(); fixture.engine.dispatch()
        assertEquals(1, fixture.access.writes) // Last removal cannot clear/delete an entry.
    }

    @Test fun sameStatusDeduplicatesAndConflictsRequireAnExplicitResolution() = runBlocking {
        val fixture = Fixture()
        fixture.access.local = title.copy(memberships = listOf(a, b))
        val preview = fixture.engine.preview(connection, true, mappings)
        fixture.engine.confirm(preview.id, setOf(481))
        fixture.engine.dispatch()
        assertEquals(0, fixture.access.writes)
        val pending = fixture.engine.view().pending.single()
        assertEquals(CategoryStatusActionState.CONFLICT, pending.state)
        assertEquals(setOf("CURRENT", "COMPLETED"), pending.conflicts.keys)
        fixture.engine.resolve(pending.id, "COMPLETED")
        fixture.engine.dispatch()
        assertEquals(1, fixture.access.writes)
        val equal = Fixture()
        equal.access.local = title.copy(memberships = listOf(a, b))
        equal.engine.confirm(equal.engine.preview(connection, true, mappings.map { it.copy(status = "COMPLETED") }).id, setOf(481))
        equal.engine.dispatch()
        assertEquals(1, equal.access.writes)
    }

    @Test fun uncertainTransmissionSurvivesRecreationAndOnlyReadsBack() = runBlocking {
        val fixture = Fixture()
        fixture.access.failAfterSending = IOException("fixture network uncertainty")
        fixture.engine.confirm(fixture.engine.preview(connection, true, listOf(mappings.last())).id, emptySet())
        fixture.access.local = title.copy(memberships = listOf(b))
        fixture.engine.reconcile(); fixture.engine.dispatch()
        assertEquals(1, fixture.access.writes)
        assertEquals(CategoryStatusActionState.READBACK_REQUIRED, fixture.engine.view().pending.single().state)
        val recreated = CategoryStatusEngine(CategoryStatusStore(fixture.file), fixture.access) { 1000 }
        recreated.dispatch()
        assertEquals(1, fixture.access.writes)
        assertTrue(recreated.view().pending.isEmpty())
    }

    @Test fun persistedSendingRecoversAsReadbackAndKeepsConnectionNickname() = runBlocking {
        val fixture = Fixture()
        fixture.access.local = title.copy(memberships = listOf(b))
        fixture.engine.confirm(fixture.engine.preview(connection, true, mappings).id, setOf(481))
        val pending = fixture.engine.view().pending.single()
        fixture.store.mutate { old -> old.copy(pending = old.pending.mapValues { it.value.copy(state = CategoryStatusActionState.SENDING) }) }
        val recovered = CategoryStatusStore(fixture.file).view().pending.single()
        assertEquals(CategoryStatusActionState.READBACK_REQUIRED, recovered.state)
        assertEquals(pending.guard.connection, recovered.guard.connection)
    }

    @Test fun explicitReadbackAfterSameAccountReconnectWorksWithWritesDisabledAndNeverResends() = runBlocking {
        val fixture = Fixture()
        fixture.access.local = title.copy(memberships = listOf(b))
        fixture.access.failAfterSending = IOException("uncertain")
        fixture.engine.confirm(fixture.engine.preview(connection, true, mappings).id, setOf(481))
        fixture.engine.dispatch()
        assertEquals(1, fixture.access.writes)
        fixture.access.current = connection.copy(revision = 43, scopes = setOf("library:read"))
        fixture.access.writeEnabled = false
        fixture.engine.disable(owner)
        val pending = fixture.engine.view().pending.single()
        val reads = fixture.access.reads
        fixture.engine.dispatch()
        assertEquals(reads, fixture.access.reads) // Background dispatch cannot adopt the new session.
        fixture.engine.check(pending.id)
        assertEquals(1, fixture.access.writes)
        assertEquals(reads + 1, fixture.access.reads)
        assertTrue(fixture.engine.view().pending.isEmpty())
        assertTrue(fixture.engine.view().armedOwners.isEmpty())
    }

    @Test fun readbackAfterReconnectCannotAdoptADifferentOwnerOrSourceEdition() = runBlocking {
        for (change in 0..1) {
            val fixture = Fixture()
            fixture.access.local = title.copy(memberships = listOf(b))
            fixture.access.failAfterSending = IOException("uncertain")
            fixture.engine.confirm(fixture.engine.preview(connection, true, mappings).id, setOf(481))
            fixture.engine.dispatch()
            val pending = fixture.engine.view().pending.single()
            if (change == 0) fixture.access.current = connection.copy(owner = TrackerOwner("COMICK", "another-account"))
            else fixture.access.local = fixture.access.local!!.copy(sourceUrl = "/different-edition")
            val reads = fixture.access.reads
            assertTrue(runCatching { fixture.engine.check(pending.id) }.isFailure)
            assertEquals(reads, fixture.access.reads)
            assertEquals(1, fixture.access.writes)
            assertEquals(CategoryStatusActionState.READBACK_REQUIRED, fixture.engine.view().pending.single().state)
        }
    }

    @Test fun changesAfterPacingBlockTheWriteAndAccountRelinkChangesStayPaused() = runBlocking {
        for (change in 0..3) {
            val fixture = Fixture()
            fixture.access.local = title.copy(memberships = listOf(b))
            fixture.engine.confirm(fixture.engine.preview(connection, true, mappings).id, setOf(481))
            fixture.access.beforeWrite = {
                when (change) {
                    0 -> fixture.access.local = fixture.access.local!!.copy(linkRevision = "changed")
                    1 -> fixture.access.current = connection.copy(revision = 43)
                    2 -> fixture.access.local = fixture.access.local!!.copy(sourceUrl = "/another-edition")
                    3 -> fixture.access.local = fixture.access.local!!.copy(memberships = listOf(a))
                }
            }
            fixture.engine.dispatch()
            assertEquals("change=$change", 0, fixture.access.writes)
            assertEquals(CategoryStatusActionState.PAUSED, fixture.engine.view().pending.single().state)
        }
    }

    @Test fun transferredMappingsNeverTransferArmingQueuesOrMembershipBaselines() = runBlocking {
        val fixture = Fixture()
        fixture.access.local = title.copy(memberships = listOf(b))
        fixture.engine.confirm(fixture.engine.preview(connection, true, mappings).id, setOf(481))
        val value = fixture.store.exportTransfer()
        assertFalse(value.contains("opaque-media"))
        assertFalse(value.contains("exact-link-version"))
        val restored = CategoryStatusStore(temporary.newFolder().resolve("journal.json"))
        restored.mergeTransfer(value)
        assertTrue(restored.view().armedOwners.isEmpty())
        assertTrue(restored.view().pending.isEmpty())
        val engine = CategoryStatusEngine(restored, fixture.access) { 1000 }
        engine.reconcile(); engine.dispatch()
        assertEquals(0, fixture.access.writes)
        val before = restored.view()
        restored.mergeTransfer(value)
        assertEquals(before, restored.view())
        fixture.engine.delete(owner)
        restored.mergeTransfer(fixture.store.exportTransfer())
        restored.mergeTransfer(value)
        assertTrue(restored.view().configurations.single().deleted)
        assertTrue(restored.view().armedOwners.isEmpty())
    }

    @Test fun staleReviewsCategoryRecreationAndCorruptTransfersCannotMutateTheJournal() = runBlocking {
        val fixture = Fixture()
        val preview = fixture.engine.preview(connection, true, mappings)
        fixture.access.local = title.copy(sourceUrl = "/new-edition")
        assertTrue(runCatching { fixture.engine.confirm(preview.id, setOf(481)) }.isFailure)
        assertTrue(fixture.engine.view().configurations.isEmpty())
        fixture.access.local = title
        fixture.access.categories = listOf(a.copy(categoryCreatedAt = 999), b)
        assertTrue(runCatching { fixture.engine.preview(connection, true, mappings) }.isFailure)
        assertTrue(fixture.engine.view().configurations.isEmpty())
        val before = fixture.store.exportTransfer()
        assertTrue(runCatching { fixture.store.mergeTransfer("{".repeat(100)) }.isFailure)
        assertEquals(before, fixture.store.exportTransfer())
    }

    private inner class Fixture {
        val file = temporary.newFolder().resolve("journal.json")
        val store = CategoryStatusStore(file)
        val access = Access()
        val engine = CategoryStatusEngine(store, access) { 1000L }
    }

    private inner class Access : CategoryStatusAccess {
        override val changes = MutableSharedFlow<Unit>()
        var current = connection
        var writeEnabled = true
        var local: CategoryStatusTitle? = title
        var categories = listOf(a, b)
        var reads = 0
        var writes = 0
        var beforeWrite: suspend () -> Unit = {}
        var failAfterSending: Exception? = null
        var remote = TrackerRemoteEntry(ref, TrackerTitle(ref.mediaId, "Fixture", url = "https://fixture.invalid/title"),
            "CURRENT", TrackerRawProgress("7.5", "2"), "9.25", "Preserve notes", "old-date")
        override suspend fun accounts() = if (writeEnabled) listOf(CategoryStatusAccount(current, "Reader", listOf("CURRENT", "COMPLETED", "PAUSED").map { CategoryStatusChoice(it, it) })) else emptyList()
        override suspend fun readbackConnection(owner: TrackerOwner) = current.takeIf { it.owner == owner }
        override suspend fun categories() = categories
        override suspend fun titles(connection: TrackerConnection, limit: Int) = listOfNotNull(local)
        override suspend fun title(connection: TrackerConnection, mangaId: Long) = local
        override suspend fun readback(guard: CategoryStatusGuard, beforeRequest: suspend () -> Unit): TrackerRemoteEntry {
            beforeRequest(); reads++; return remote
        }
        override suspend fun updateStatusOnly(guard: CategoryStatusGuard, status: String, beforeSend: suspend () -> Unit): TrackerRemoteEntry {
            beforeWrite(); beforeSend()
            writes++; remote = remote.copy(status = status)
            failAfterSending?.let { throw it }
            return remote
        }
    }
}
