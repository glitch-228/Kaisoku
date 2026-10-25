package org.koitharu.kotatsu.scrobbling.v2.progress

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

class OutgoingProgressEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = OutgoingProgressConnection("COMICK", "opaque-account", 7, setOf("library:write"))

    @Test fun defaultsAndOpeningStatePerformNoNetworkAndUnconfirmedEventsCannotArmAWrite() = runTest {
        val f = fixture()
        val event = completion("1")
        assertEquals(OutgoingProgressEnqueue.NOT_ENABLED, f.engine.enqueueCompletion(connection, 481, event))
        assertTrue(f.engine.view().consents.isEmpty())
        assertEquals(0, f.engine.dispatch())
        f.engine.setAutomatic(connection, true)
        assertEquals(OutgoingProgressEnqueue.REVIEW_REQUIRED, f.engine.enqueueCompletion(connection, 481, event))
        assertEquals(OutgoingProgressState.PAUSED, f.primary().state)
        assertEquals(0, f.access.reads)
        assertTrue(f.access.requests.isEmpty())
    }

    @Test fun fractionalProgressEmitsOnlyTheSelectedNumberAndPreservesUnrelatedRemoteValues() = runTest {
        val f = fixture()
        val before = f.access.entries.getValue(key(connection, 481)).copy(
            progress = TrackerRawProgress("1", "4.5", "opaque-chapter"), status = "COMPLETED",
            rating = "8.75", notes = "Keep this note", updatedAt = "original-date")
        f.access.entries[key(connection, 481)] = before
        f.enableAndComplete("12.750")
        assertEquals(1, f.engine.dispatch())
        val request = f.access.requests.single()
        assertEquals(OutgoingProgressFields(number = "12.750"), request.fields)
        assertNull(request.fields.volume)
        assertNull(request.fields.entryId)
        val after = f.access.entries.getValue(key(connection, 481))
        assertEquals(before.copy(progress = TrackerRawProgress("12.750", "4.5", "opaque-chapter")), after)
        assertNull(f.engine.view().lanes.single().primary)
        assertEquals(OutgoingProgressState.ACKNOWLEDGED,
            f.engine.view().lanes.single().acknowledgment!!.action.state)
    }

    @Test fun integerOnlyAccountsRejectFractionsWithoutTruncatingThem() = runTest {
        val f = fixture()
        f.access.accounts[connection.owner()] = f.account().copy(maxChapterScale = 0)
        f.enableAndComplete("12.5")
        assertEquals(OutgoingProgressState.PAUSED, f.primary().state)
        f.engine.dispatch()
        assertTrue(f.access.requests.isEmpty())
        assertEquals("12.5", f.primary().fields.number)
    }

    @Test fun outgoingNumbersRejectNonfiniteNegativeExponentOverscaleAndOverprecisionValues() {
        for (value in listOf("NaN", "Infinity", "-1", "1e3", ".5", "1.", "0.0000000000001", "9".repeat(39),
            "9".repeat(38) + ".1")) {
            assertThrows(IllegalArgumentException::class.java) { OutgoingProgressFields(number = value) }
        }
        assertEquals("0", OutgoingProgressFields(number = "0").number)
        assertEquals("12.750", OutgoingProgressFields(number = "12.750").number)
        assertThrows(IllegalArgumentException::class.java) {
            OutgoingProgressFields(number = "12", entryId = "opaque")
        }
    }

    @Test fun unknownEntryOnlyAndLabeledRemoteProgressNeverInventZeroOrSendAutomatically() = runTest {
        for (progress in listOf(null, TrackerRawProgress(null, entryId = "opaque-chapter"),
            TrackerRawProgress("Extra A"), TrackerRawProgress("-1"), TrackerRawProgress("3", unit = "unknown"))) {
            val f = fixture()
            f.access.entries[key(connection, 481)] = f.access.entries.getValue(key(connection, 481)).copy(progress = progress)
            f.enableAndComplete("1")
            f.engine.dispatch()
            assertEquals(OutgoingProgressState.PAUSED, f.primary().state)
            assertTrue(f.primary().requiresReview)
            assertTrue(f.access.requests.isEmpty())
        }
    }

    @Test fun knownHigherRemoteProgressIsRetainedAndItsReceiptSuppressesRepeatedInvalidations() = runTest {
        val f = fixture()
        f.access.entries[key(connection, 481)] = remote(connection, 481, "20.5")
        f.enableAndComplete("12.5")
        f.engine.dispatch()
        assertTrue(f.access.requests.isEmpty())
        val snapshot = f.access.snapshots.getValue(key(connection, 481))
        f.access.snapshots[key(connection, 481)] = snapshot.copy(positionRevision = "cursor-moved")
        val event = completion("15")
        f.access.events += event.eventId
        assertEquals(OutgoingProgressEnqueue.ALREADY_OBSERVED, f.engine.enqueueCompletion(connection, 481, event))
        assertEquals("20.5", f.engine.view().lanes.single().acknowledgment!!.observed!!.number)
    }

    @Test fun queuedReaderCompletionsCoalesceByMaximumWithoutChangingBranchOrResetEvidence() = runTest {
        val f = fixture()
        f.enableAndComplete("2.5")
        f.complete("5.25")
        val primary = f.primary()
        f.complete("3.5")
        assertEquals(primary, f.primary())
        assertNull(f.engine.view().lanes.single().later)
        f.engine.dispatch()
        assertEquals(listOf("5.25"), f.access.requests.map { it.fields.number })
    }

    @Test fun laterCompletionStaysSeparateFromTheDurableUnknownBodyAndOnlyAdvancesAfterExplicitReadback() = runTest {
        val f = fixture()
        f.enableAndComplete("2.5")
        val original = f.primary()
        f.access.duringWrite = {
            f.complete("4.75")
            val saved = f.engine.view().lanes.single()
            assertEquals(original.id, saved.primary!!.id)
            assertEquals("2.5", saved.primary!!.fields.number)
            assertEquals(OutgoingProgressState.SENDING, saved.primary!!.state)
            assertEquals("4.75", saved.later!!.fields.number)
        }
        f.access.failAfterWrite = IOException("Authorization: Bearer never-copy-this")
        f.engine.dispatch()
        val uncertain = f.primary()
        assertEquals(OutgoingProgressState.READBACK_REQUIRED, uncertain.state)
        assertFalse(uncertain.error.orEmpty().contains("never-copy-this"))
        val recreated = OutgoingProgressEngine(OutgoingProgressStore(f.file), f.access) { f.access.now }
        val reads = f.access.reads
        assertEquals(0, recreated.dispatch())
        assertEquals(reads, f.access.reads)
        assertEquals(1, f.access.requests.size)
        f.access.duringWrite = {}
        f.access.failAfterWrite = null
        recreated.check(uncertain.id)
        assertEquals("4.75", recreated.view().lanes.single().primary!!.fields.number)
        recreated.dispatch()
        assertEquals(listOf("2.5", "4.75"), f.access.requests.map { it.fields.number })
    }

    @Test fun cancellationAfterMutationIsPersistedReadbackOnly() = runTest {
        val f = fixture()
        f.enableAndComplete("2")
        f.access.failAfterWrite = CancellationException("fixture")
        try { f.engine.dispatch(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertTrue(f.primary().uncertain)
        assertEquals(OutgoingProgressState.READBACK_REQUIRED, f.primary().state)
        val recreated = OutgoingProgressEngine(OutgoingProgressStore(f.file), f.access) { f.access.now }
        assertEquals(0, recreated.dispatch())
        assertEquals(1, f.access.requests.size)
    }

    @Test fun processDeathAtSendingRecoversWithoutAnyAutomaticReadOrResend() = runTest {
        val f = fixture()
        f.enableAndComplete("2")
        f.store.mutate { journal -> journal.copy(lanes = journal.lanes.map { it.copy(primary = it.primary!!.copy(
            state = OutgoingProgressState.SENDING, uncertain = true)) }) }
        val recreated = OutgoingProgressEngine(OutgoingProgressStore(f.file), f.access) { f.access.now }
        val action = recreated.view().lanes.single().primary!!
        assertEquals(OutgoingProgressState.READBACK_REQUIRED, action.state)
        assertEquals(0, recreated.dispatch())
        assertEquals(0, f.access.reads)
        assertTrue(f.access.requests.isEmpty())
    }

    @Test fun disablingStopsUnsentWorkAndSameOwnerReadonlyReconnectCanCheckAnEarlierSend() = runTest {
        val f = fixture()
        f.enableAndComplete("2")
        f.engine.setAutomatic(connection, false)
        f.engine.dispatch()
        assertTrue(f.access.requests.isEmpty())
        val g = fixture()
        g.enableAndComplete("3")
        g.access.failAfterWrite = IOException("unknown")
        g.engine.dispatch()
        val action = g.primary()
        g.engine.setAutomatic(connection, false)
        val reconnected = connection.copy(revision = 8, scopes = setOf("library:read"))
        g.access.accounts[connection.owner()] = g.account().copy(connection = reconnected, writable = false)
        assertTrue(runCatching { g.engine.check(action.id, reconnected.copy(accountId = "different")) }.isFailure)
        g.access.failAfterWrite = null
        g.engine.check(action.id, reconnected)
        assertNull(g.engine.view().lanes.single().primary)
        assertEquals(1, g.access.requests.size)
        assertEquals(0, g.engine.dispatch())
    }

    @Test fun staleSourceEditionBranchGenerationLinkAndSessionAllBlockBeforeProviderMutation() = runTest {
        for (change in 0..5) {
            val f = fixture()
            f.enableAndComplete("3")
            val before = f.access.snapshots.getValue(key(connection, 481))
            when (change) {
                0 -> f.access.snapshots[key(connection, 481)] = before.copy(source = "MANGADEX")
                1 -> f.access.snapshots[key(connection, 481)] = before.copy(editionUrl = "/different")
                2 -> f.access.snapshots[key(connection, 481)] = before.copy(branchKey = "Sother")
                3 -> f.access.snapshots[key(connection, 481)] = before.copy(evidenceRevision = "reset-two")
                4 -> f.access.snapshots[key(connection, 481)] = before.copy(linkRevision = "relinked")
                5 -> f.access.accounts[connection.owner()] = f.account().copy(connection = connection.copy(revision = 8))
            }
            f.engine.dispatch()
            assertTrue("change=$change", f.access.requests.isEmpty())
            assertEquals(OutgoingProgressState.PAUSED, f.primary().state)
        }
    }

    @Test fun guardAfterProviderPacingRechecksTheCommittedSpecificCompletionWitness() = runTest {
        val f = fixture()
        f.enableAndComplete("3")
        f.access.beforeWrite = { f.access.events.clear() }
        f.engine.dispatch()
        assertTrue(f.access.requests.isEmpty())
        assertTrue(f.primary().uncertain) // SENDING was durable, so recovery stays conservative.
        assertEquals(OutgoingProgressState.READBACK_REQUIRED, f.primary().state)
    }

    @Test fun manualLoweringRequiresSeparateConsentAndTheFreshSelectedBaselineMustStillMatch() = runTest {
        val f = fixture()
        f.access.entries[key(connection, 481)] = remote(connection, 481, "10")
        val preview = f.engine.preview(connection, 481, OutgoingProgressFields(number = "5"))
        assertTrue(preview.requiresLowerConsent)
        assertTrue(runCatching { f.engine.confirm(preview.id) }.isFailure)
        f.engine.confirm(preview.id, allowLower = true)
        f.access.entries[key(connection, 481)] = remote(connection, 481, "11")
        f.engine.dispatch()
        assertTrue(f.access.requests.isEmpty())
        assertTrue(f.primary().requiresReview)
        val g = fixture()
        g.access.entries[key(connection, 481)] = remote(connection, 481, "10")
        val accepted = g.engine.preview(connection, 481, OutgoingProgressFields(number = "5"))
        g.engine.confirm(accepted.id, allowLower = true)
        g.engine.dispatch()
        assertTrue(g.access.requests.single().allowLower)
        assertEquals("5", g.access.entries.getValue(key(connection, 481)).progress!!.number)
    }

    @Test fun unknownAndOpaqueManualProgressNeedExplicitReviewAndNeverArmAutomaticConsent() = runTest {
        val f = fixture()
        f.access.entries[key(connection, 481)] = remote(connection, 481, null)
        val preview = f.engine.preview(connection, 481, OutgoingProgressFields(number = "0"))
        assertTrue(preview.requiresUnknownConsent)
        assertTrue(runCatching { f.engine.confirm(preview.id) }.isFailure)
        f.engine.confirm(preview.id, allowUnknown = true)
        f.engine.dispatch()
        assertEquals("0", f.access.requests.single().fields.number)
        assertTrue(f.engine.view().consents.isEmpty())
        val g = fixture()
        val opaque = g.engine.preview(connection, 481, OutgoingProgressFields(entryId = "opaque/chapter-A"))
        assertTrue(opaque.requiresUnknownConsent)
        g.engine.confirm(opaque.id, allowUnknown = true)
        g.engine.dispatch()
        assertEquals(OutgoingProgressFields(entryId = "opaque/chapter-A"), g.access.requests.single().fields)
    }

    @Test fun manualVolumeSelectionPreservesChapterAndStaleCursorInvalidatesUnsentManualReview() = runTest {
        val f = fixture()
        f.access.entries[key(connection, 481)] = remote(connection, 481, "12.5").copy(
            progress = TrackerRawProgress("12.5", "2"))
        val preview = f.engine.preview(connection, 481, OutgoingProgressFields(volume = "3.5"))
        f.engine.confirm(preview.id)
        f.engine.dispatch()
        assertNull(f.access.requests.single().fields.number)
        assertEquals(TrackerRawProgress("12.5", "3.5"), f.access.entries.getValue(key(connection, 481)).progress)
        val g = fixture()
        val stale = g.engine.preview(connection, 481, OutgoingProgressFields(number = "3"))
        g.access.snapshots[key(connection, 481)] = g.access.snapshots.getValue(key(connection, 481)).copy(
            positionRevision = "cursor-changed")
        assertTrue(runCatching { g.engine.confirm(stale.id) }.isFailure)
        assertTrue(g.access.requests.isEmpty())
    }

    @Test fun aMissingRemoteEntryCannotBeCreatedAndUnknownRecoveryNeedsFreshManualReview() = runTest {
        val f = fixture()
        f.access.entries.remove(key(connection, 481))
        f.enableAndComplete("1")
        f.engine.dispatch()
        assertTrue(f.primary().requiresReview)
        assertTrue(f.access.requests.isEmpty())
        val g = fixture()
        g.enableAndComplete("3")
        g.access.failAfterWrite = IOException("unknown")
        g.engine.dispatch()
        val uncertain = g.primary()
        assertTrue(runCatching { g.engine.discard(uncertain.id) }.isFailure)
        assertTrue(runCatching { g.engine.retry(uncertain.id) }.isFailure)
        g.access.entries[key(connection, 481)] = remote(connection, 481, "1")
        g.engine.check(uncertain.id)
        assertFalse(g.primary().uncertain)
        assertTrue(g.primary().requiresReview)
        assertTrue(runCatching { g.engine.retry(uncertain.id) }.isFailure)
        assertEquals(1, g.access.requests.size)
    }

    @Test fun uncertainWorkBlocksAnotherLocalEditionLinkedToTheSameRemoteWork() = runTest {
        val f = fixture()
        f.enableAndComplete("3")
        f.access.failAfterWrite = IOException("unknown")
        f.engine.dispatch()
        val second = f.access.snapshots.getValue(key(connection, 481)).copy(mangaId = 482)
        f.access.snapshots[key(connection, 482)] = second
        f.access.entries[key(connection, 482)] = remote(connection, 482, "0").copy(
            reference = TrackerReference(connection.owner(), second.mediaId, second.listEntryId))
        f.complete("4", 482)
        val reads = f.access.reads
        assertEquals(0, f.engine.dispatch())
        assertEquals(reads, f.access.reads)
        assertEquals(1, f.access.requests.size)
    }

    @Test fun accountCooldownDoesNotBlockAnotherAccountAndUnknownSendStillCannotReplayAfterExpiry() = runTest {
        val f = fixture()
        val other = connection.copy(accountId = "independent-account")
        f.access.install(other, 600)
        f.engine.setAutomatic(connection, true)
        f.engine.setAutomatic(other, true)
        f.complete("3")
        val event = completion("4", 600)
        f.access.events += event.eventId
        f.engine.enqueueCompletion(other, 600, event)
        f.access.failForOwner = connection.owner()
        f.access.failAfterWrite = TrackerProviderError("COMICK", 429, 0, "must-not-copy")
        f.engine.dispatch()
        assertEquals(2, f.access.requests.size)
        assertNotNull(f.engine.view().lanes.single { it.accountId == connection.accountId }.primary)
        assertNull(f.engine.view().lanes.single { it.accountId == other.accountId }.primary)
        val uncertain = f.primary()
        assertTrue(runCatching { f.engine.check(uncertain.id) }.isFailure)
        f.access.now = 61_001
        assertEquals(0, f.engine.dispatch())
        assertEquals(2, f.access.requests.size)
        f.access.failAfterWrite = null
        f.engine.check(uncertain.id)
        assertNull(f.engine.view().lanes.single { it.accountId == connection.accountId }.primary)
    }

    @Test fun readonlyRecoveryResolvesOldReferenceAfterResetRelinkAndSourceChangesWithoutLocalWrites() = runTest {
        for (change in 0..3) {
            val f = fixture()
            f.enableAndComplete("3")
            f.access.failAfterWrite = IOException("unknown")
            f.engine.dispatch()
            val action = f.primary()
            val original = f.access.snapshots.getValue(key(connection, 481))
            val changed = when (change) {
                0 -> original.copy(evidenceRevision = "reset-after-send")
                1 -> original.copy(mediaId = "another-work", listEntryId = "another-entry", linkRevision = "relinked")
                2 -> original.copy(source = "MANGADEX", editionUrl = "/another-edition")
                else -> original.copy(branchKey = "Sother")
            }
            f.access.snapshots[key(connection, 481)] = changed
            f.access.events.clear()
            f.access.failAfterWrite = null
            f.engine.check(action.id)
            assertNull("change=$change", f.engine.view().lanes.single().primary)
            assertEquals(changed, f.access.snapshots[key(connection, 481)])
            assertEquals(action.guard.reference(), f.engine.view().lanes.single().acknowledgment!!.action.guard.reference())
            assertEquals(1, f.access.requests.size)
            assertEquals(0, f.engine.dispatch())
        }
    }

    @Test fun manualCursorChangesAfterUnknownSendDoNotPreventReadonlyOutcomeRecovery() = runTest {
        val f = fixture()
        val preview = f.engine.preview(connection, 481, OutgoingProgressFields(number = "4"))
        f.engine.confirm(preview.id)
        f.access.failAfterWrite = IOException("unknown")
        f.engine.dispatch()
        val action = f.primary()
        val changed = f.access.snapshots.getValue(key(connection, 481)).copy(positionRevision = "reader-moved")
        f.access.snapshots[key(connection, 481)] = changed
        f.engine.check(action.id)
        assertNull(f.engine.view().lanes.single().primary)
        assertEquals(changed, f.access.snapshots[key(connection, 481)])
        assertEquals(1, f.access.requests.size)
    }

    @Test fun automaticCompletionDuringAnUncertainManualLowerIsNotSuppressedByAnOlderHigherReceipt() = runTest {
        val f = fixture()
        f.enableAndComplete("10")
        f.engine.dispatch()
        val preview = f.engine.preview(connection, 481, OutgoingProgressFields(number = "5"))
        f.engine.confirm(preview.id, allowLower = true)
        f.access.duringWrite = { f.complete("7") }
        f.access.failAfterWrite = IOException("unknown")
        f.engine.dispatch()
        val lane = f.engine.view().lanes.single()
        assertEquals("5", lane.primary!!.fields.number)
        assertEquals("7", lane.later!!.fields.number)
        assertEquals("10", lane.acknowledgment!!.observed!!.number)
        assertEquals(OutgoingProgressState.READBACK_REQUIRED, lane.primary!!.state)
    }

    @Test fun aBlockedFirstAccountCannotStarveAnEligibleAccountBeyondTheEightActionWindow() = runTest {
        val f = fixture()
        f.engine.setAutomatic(connection, true)
        for (id in 481L..490L) {
            f.access.install(connection, id)
            f.complete("3", id)
        }
        val other = connection.copy(accountId = "unblocked-account")
        f.access.install(other, 600)
        f.engine.setAutomatic(other, true)
        val event = completion("4", 600)
        f.access.events += event.eventId
        f.engine.enqueueCompletion(other, 600, event)
        f.store.mutate { it.copy(cooldowns = listOf(OutgoingProgressCooldown(connection.provider,
            connection.accountId, 61_000, "Provider cooldown"))) }
        assertEquals(1, f.engine.dispatch())
        assertEquals(other.owner(), f.access.requests.single().guard.connection.owner())
        assertEquals(10, f.engine.view().lanes.count { it.accountId == connection.accountId && it.primary != null })
    }

    @Test fun corruptionAndPendingBoundsFailClosedAndPreserveThePriorAtomicBytes() = runTest {
        val f = fixture()
        f.enableAndComplete("2")
        val bytes = f.file.readBytes()
        val existing = f.engine.view().lanes.single()
        assertTrue(runCatching { f.store.mutate { journal -> journal.copy(lanes = List(1001) { index ->
            val id = 1000L + index
            val action = existing.primary!!.copy(id = "action$index",
                guard = existing.primary!!.guard.copy(snapshot = existing.primary!!.guard.snapshot.copy(mangaId = id)))
            existing.copy(mangaId = id, primary = action)
        }) } }.isFailure)
        assertArrayEquals(bytes, f.file.readBytes())
        val corrupt = temporary.newFile("broken.json").apply { writeText("{broken") }
        assertTrue(runCatching { OutgoingProgressStore(corrupt).current() }.isFailure)
        assertEquals("{broken", corrupt.readText())
        assertTrue(f.access.requests.isEmpty())
    }

    private suspend fun Fixture.enableAndComplete(number: String) {
        engine.setAutomatic(connection, true)
        complete(number)
    }
    private suspend fun Fixture.complete(number: String, mangaId: Long = 481) {
        val event = completion(number, mangaId)
        access.events += event.eventId
        engine.enqueueCompletion(connection, mangaId, event)
    }
    private suspend fun Fixture.primary() = engine.view().lanes.single { it.mangaId == 481L }.primary!!
    private fun Fixture.account() = access.accounts.getValue(connection.owner())

    private fun completion(number: String, id: Long = 481) = OutgoingProgressCompletion(
        "confirmed-$id-$number", "REMANGA:N:chapter-$number", number, "N", "exact-generation-one")
    private fun key(connection: OutgoingProgressConnection, id: Long) = connection.owner() to id
    private fun remote(connection: OutgoingProgressConnection, id: Long, number: String?) = TrackerRemoteEntry(
        TrackerReference(connection.owner(), "opaque-media-$id", "opaque-list-$id"),
        TrackerTitle("opaque-media-$id", "Remote", url = "https://tracker.invalid/title"), "CURRENT",
        number?.let { TrackerRawProgress(it) }, "8.5", "Keep note")

    private fun fixture(): Fixture {
        val file = File(temporary.newFolder(), "progress.json")
        val store = OutgoingProgressStore(file)
        val access = Access()
        access.install(connection, 481)
        return Fixture(file, store, access, OutgoingProgressEngine(store, access) { access.now })
    }
    private data class Fixture(val file: File, val store: OutgoingProgressStore, val access: Access,
        val engine: OutgoingProgressEngine)
    private data class Request(val guard: OutgoingProgressGuard, val fields: OutgoingProgressFields,
        val expected: OutgoingProgressRemote?, val allowLower: Boolean, val allowUnknown: Boolean)

    private inner class Access : OutgoingProgressAccess {
        val accounts = linkedMapOf<TrackerOwner, OutgoingProgressAccount>()
        val snapshots = hashMapOf<Pair<TrackerOwner, Long>, OutgoingProgressSnapshot>()
        val entries = hashMapOf<Pair<TrackerOwner, Long>, TrackerRemoteEntry>()
        val events = hashSetOf<String>()
        val requests = mutableListOf<Request>()
        var reads = 0
        var now = 1000L
        var beforeWrite: suspend () -> Unit = {}
        var duringWrite: suspend () -> Unit = {}
        var failAfterWrite: Exception? = null
        var failForOwner: TrackerOwner? = null
        fun install(connection: OutgoingProgressConnection, id: Long) {
            accounts[connection.owner()] = OutgoingProgressAccount(connection, "Verified account", true, 3, 2, true)
            snapshots[key(connection, id)] = OutgoingProgressSnapshot(id, "Title", "REMANGA", "/edition-$id", null,
                "N", "opaque-media-$id", "opaque-list-$id", "v2:7", "exact-generation-one", "cursor-one")
            entries[key(connection, id)] = remote(connection, id, "0")
        }
        override suspend fun accounts() = accounts.values.toList()
        override suspend fun snapshot(connection: OutgoingProgressConnection, mangaId: Long) = snapshots[key(connection, mangaId)]
        override suspend fun validate(guard: OutgoingProgressGuard) {
            check(accounts[guard.connection.owner()]?.connection?.sameSession(guard.connection) == true)
            val current = requireNotNull(snapshots[key(guard.connection, guard.snapshot.mangaId)])
            check(guard.snapshot.sameEvidence(current, ignorePosition = guard.completion != null))
            guard.completion?.let { check(it.eventId in events && it.evidenceRevision == current.evidenceRevision) }
        }
        override suspend fun readback(guard: OutgoingProgressGuard, beforeRequest: suspend () -> Unit): TrackerRemoteEntry? {
            beforeRequest()
            reads++
            return entries[key(guard.connection, guard.snapshot.mangaId)]
        }
        override suspend fun updateProgressOnly(guard: OutgoingProgressGuard, fields: OutgoingProgressFields,
            expectedRemote: OutgoingProgressRemote?, allowLower: Boolean, allowUnknown: Boolean,
            beforeSend: suspend () -> Unit): TrackerRemoteEntry {
            beforeWrite()
            beforeSend()
            val key = key(guard.connection, guard.snapshot.mangaId)
            val before = requireNotNull(entries[key]) { "Creation is forbidden" }
            requests += Request(guard, fields, expectedRemote, allowLower, allowUnknown)
            val raw = before.progress
            val after = before.copy(progress = TrackerRawProgress(fields.number ?: raw?.number,
                fields.volume ?: raw?.volume, fields.entryId ?: raw?.entryId))
            entries[key] = after
            duringWrite()
            if (failForOwner == null || failForOwner == guard.connection.owner()) failAfterWrite?.let { throw it }
            return after
        }
    }
}
