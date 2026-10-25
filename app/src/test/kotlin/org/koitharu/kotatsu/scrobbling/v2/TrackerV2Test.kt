package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrackerV2Test {
    @org.junit.Test fun numericProjectionRejectsUnboundedExponentsWithoutChangingRawEvidence() {
        for (raw in listOf("1e2147483647", "1e-2147483647", "9".repeat(129), "0." + "0".repeat(40) + "1")) {
            val progress = TrackerRawProgress(raw)
            org.junit.Assert.assertNull(progress.numericNumber())
            org.junit.Assert.assertNull(progress.wholeChapterOrNull())
            org.junit.Assert.assertEquals(raw, progress.number)
        }
        org.junit.Assert.assertEquals(java.math.BigDecimal("365.5"), TrackerRawProgress("365.5").numericNumber())
    }

    private val owner = TrackerOwner("COMICK", "12345678-1234-4123-8123-123456789abc")

    @Test
    fun opaqueReferencesAndRawProgressRoundTripWithoutHashesOrNarrowing() {
        val entry = entry("aB7kP2xQ", TrackerRawProgress("12.5", "2", null))
        val record = TrackerLinkV2Entity.linked(123L, entry, 7)
        assertEquals(owner, record.reference().owner)
        assertEquals("aB7kP2xQ", record.mediaId)
        assertEquals("12.5", record.progress()?.number)
        assertEquals("2", record.progress()?.volume)
        assertNull(record.progress()?.wholeChapterOrNull())
        assertNull(TrackerRawProgress("Extra A").numericNumber())
        assertEquals(0, TrackerRawProgress("0").wholeChapterOrNull())
        assertNull(TrackerLinkV2Entity.linked(123L, entry("other", null), 8).progress())
        assertEquals("9999999999999999999999999999999",
            TrackerRawProgress("9999999999999999999999999999999").number)
    }

    @Test
    fun numericLegacyBridgeRejectsLossyAndAmbiguousIdentities() {
        assertEquals(Long.MAX_VALUE, LegacyTrackerBridge.mediaId(Long.MAX_VALUE.toString()))
        assertEquals(Int.MAX_VALUE, LegacyTrackerBridge.listEntryId(Int.MAX_VALUE.toString()))
        for (value in listOf("aB7kP2xQ", "001", "1.5", "0", "-1", "9223372036854775808")) {
            assertThrows(IllegalArgumentException::class.java) { LegacyTrackerBridge.mediaId(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { LegacyTrackerBridge.listEntryId("2147483648") }
        assertThrows(IllegalArgumentException::class.java) { LegacyTrackerBridge.wholeChapter(TrackerRawProgress("12.5")) }
        assertThrows(IllegalArgumentException::class.java) { LegacyTrackerBridge.wholeChapter(TrackerRawProgress(null)) }
        assertEquals(12, LegacyTrackerBridge.wholeChapter(TrackerRawProgress("12.0")))
    }

    @Test
    fun categoryStatusIsOptInAndConflictsNeverPickAnImplicitWinner() {
        val mappings = mapOf(1L to "CURRENT", 2L to "COMPLETED", 3L to "CURRENT")
        assertEquals(CategoryStatusDecision.Unmapped, CategoryStatusPolicy.evaluate(false, setOf(1), mappings))
        assertEquals(CategoryStatusDecision.Unmapped, CategoryStatusPolicy.evaluate(true, setOf(99), mappings))
        assertEquals(CategoryStatusDecision.Status("CURRENT", setOf(1, 3)),
            CategoryStatusPolicy.evaluate(true, setOf(1, 3), mappings))
        val conflict = CategoryStatusPolicy.evaluate(true, setOf(1, 2), mappings)
        assertTrue(conflict is CategoryStatusDecision.Conflict)
        assertEquals(mapOf("CURRENT" to setOf(1L), "COMPLETED" to setOf(2L)),
            (conflict as CategoryStatusDecision.Conflict).categoriesByStatus)
        val patch = TrackerPatch.statusOnly("COMPLETED")
        assertEquals(TrackerField.Unchanged, patch.progress)
        assertEquals(TrackerField.Unchanged, patch.rating)
        assertEquals(TrackerField.Unchanged, patch.notes)
    }

    @Test
    fun titleMatchingKeepsAdaptationsDistinctAndUniqueTitlesStillRequireConfirmation() {
        val manga = title("manga", "我的狐仙老婆", "MANGA")
        val novel = title("novel", "我的狐仙老婆", "NOVEL")
        val result = BulkTrackerMatchPolicy.propose(setOf("我的狐仙老婆"), listOf(manga, novel), expectedFormat = "MANGA")
        assertEquals(TrackerMatchConfidence.TITLE_SUGGESTION, result.confidence)
        assertEquals("manga", result.suggestedMediaId)
        val conflict = BulkTrackerMatchPolicy.propose(setOf("same"), listOf(title("1", "same"), title("2", "same")))
        assertEquals(TrackerMatchConfidence.AMBIGUOUS, conflict.confidence)
        val trustedConflict = BulkTrackerMatchPolicy.propose(setOf("same"), listOf(manga), setOf("manga", "missing"))
        assertEquals(TrackerMatchConfidence.AMBIGUOUS, trustedConflict.confidence)
        val trusted = BulkTrackerMatchPolicy.propose(setOf("different"), listOf(manga), setOf("manga"))
        assertEquals(TrackerMatchConfidence.TRUSTED_ID, trusted.confidence)
    }

    @Test
    fun bulkDraftHasZeroWritesBeforeConfirmationAndExistingEntriesAreOnlyAdopted() = runTest {
        val provider = FakeProvider()
        val commits = mutableListOf<Long>()
        val draft = TrackerBatchDraft(provider.session, listOf(
            TrackerBatchSelection(1, "existing", confirmed = false),
            TrackerBatchSelection(2, "existing", confirmed = true),
            TrackerBatchSelection(3, "missing", confirmed = true),
        ))
        assertEquals(0, provider.reads)
        val results = draft.apply(provider, { selected, _ -> commits += selected.localMangaId })
        assertEquals(listOf(2L), commits)
        assertEquals(0, provider.writes)
        assertEquals(2, results.count { it is TrackerBatchOutcome.Pending })
        draft.apply(provider, { selected, _ -> commits += selected.localMangaId })
        assertEquals(listOf(2L), commits)
    }

    @Test
    fun remoteCreationRequiresPerItemConsentAndIsNotRepeatedAfterSuccessfulLocalCommit() = runTest {
        val provider = FakeProvider()
        val draft = TrackerBatchDraft(provider.session, listOf(
            TrackerBatchSelection(1, "missing", confirmed = true, allowRemoteCreate = true),
        ))
        draft.apply(provider, { _, _ -> })
        assertEquals(1, provider.writes)
        assertEquals(TrackerPatch.statusOnly("PLANNING"), provider.lastPatch)
        draft.apply(provider, { _, _ -> fail("An applied draft must not be committed twice") })
        assertEquals(1, provider.writes)
    }

    @Test
    fun bulkDraftStopsOnAccountChangeAndCannotCrossTheSelectedOwner() = runTest {
        val provider = FakeProvider()
        val draft = TrackerBatchDraft(provider.session, listOf(TrackerBatchSelection(1, "existing", true)))
        provider.changed = true
        try {
            draft.apply(provider, { _, _ -> fail("Stale account must not commit") })
            fail("Stale account accepted")
        } catch (_: IllegalStateException) {
            assertEquals(0, provider.reads)
        }
    }

    private fun title(id: String, title: String, format: String = "MANGA") =
        TrackerTitle(id, title, url = "https://example.test/$id", format = format)

    private fun entry(id: String, progress: TrackerRawProgress? = TrackerRawProgress("12.5")) =
        TrackerRemoteEntry(TrackerReference(owner, id, id), title(id, "Title"), "CURRENT", progress, "8.5")

    private inner class FakeProvider : TrackerProvider {
        val session = TrackerConnection(owner, 1, setOf("library:read", "library:write"))
        var reads = 0
        var writes = 0
        var changed = false
        var lastPatch: TrackerPatch? = null
        override val key = owner.provider
        override val capabilities = TrackerCapability.entries.toSet()
        override suspend fun connection() = session
        override fun checkConnection(connection: TrackerConnection) {
            check(!changed && connection == session) { "account changed" }
        }
        override suspend fun search(connection: TrackerConnection, query: String) = emptyList<TrackerTitle>()
        override suspend fun metadata(connection: TrackerConnection, mediaId: String) = title(mediaId, "Title")
        override suspend fun libraryPage(connection: TrackerConnection, cursor: String?) = TrackerLibraryPage(emptyList(), null)
        override suspend fun entry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry? {
            reads++
            return if (mediaId == "existing" || writes > 0) entry(mediaId) else null
        }
        override suspend fun patch(connection: TrackerConnection, mediaId: String, patch: TrackerPatch,
            allowCreate: Boolean): TrackerRemoteEntry {
            check(allowCreate)
            writes++
            lastPatch = patch
            return entry(mediaId)
        }
    }
}
