package org.koitharu.kotatsu.history.exact

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChapterReadTransferTest {
    private val refs = (1L..4L).map { ReadChapterReference("source", null, it, "/chapter/$it") }
    private val version = ReadRevision(100, 0, "device-a")
    private val policy = ChapterReadPolicyEntity(1, "source", "/edition", "one", true, false, "N", 100, 0, "device-a", null)

    private fun backup(read: Set<Long>, revision: ReadRevision = version, generation: String = "one") = ChapterReadBackup(
        policy.copy(generation = generation),
        refs.filter { it.chapterId in read }.map { ref -> ChapterReadRecordEntity(
            1, generation, ref.key, ref.source, ref.branchKey, ref.chapterId, ref.url, true,
            revision.wall, revision.logical, revision.actor,
        ) }, emptyList(), listOf(ChapterReadCountEntity(1, "N", read.size, refs.size,
            ChapterReadTransfer.listRevision(refs), ChapterReadTransfer.encodeReferences(refs), 100, 0, "device-a")),
    )

    @Test fun concurrentTransfersPreserveHolesAndConvergeWithoutChapterCache() {
        val a = backup(setOf(1))
        val b = backup(setOf(3), ReadRevision(101, 0, "b"))
        val ab = ChapterReadTransfer.merge(a, b)
        val ba = ChapterReadTransfer.merge(b, a)
        assertEquals(ab, ba)
        assertEquals(2, ab.counts.single().readCount)
        assertEquals(setOf(1L, 3L), ab.records.filter { it.isRead }.map { it.chapterId }.toSet())
        assertEquals(ab, ChapterReadTransfer.merge(ab, b))
    }

    @Test fun unreadUpdateWinsWithoutChangingResumeOrInventingEarlierReads() {
        val local = backup(setOf(1, 3))
        val incoming = backup(emptySet()).copy(records = listOf(local.records.last().copy(isRead = false,
            revisionWall = 200, revisionActor = "other")))
        val merged = ChapterReadTransfer.merge(local, incoming)
        assertEquals(1, merged.counts.single().readCount)
        assertEquals(listOf(1L), merged.records.filter { it.isRead }.map { it.chapterId })
    }

    @Test fun resetBarrierCannotBeResurrectedByStaleHigherRecordVersions() {
        val old = backup(setOf(1, 2, 3, 4), ReadRevision(999_999, 9, "future"))
        val reset = backup(emptySet(), generation = "reset").copy(policy = policy.copy(generation = "reset",
            deleted = true, revisionWall = 200, generationWall = 200))
        for (merged in listOf(ChapterReadTransfer.merge(old, reset), ChapterReadTransfer.merge(reset, old))) {
            assertTrue(merged.policy.deleted)
            assertEquals("reset", merged.policy.generation)
            assertEquals(0, merged.counts.single().readCount)
        }
    }

    @Test fun staleBranchPolicyEditsCannotReactivateAnOlderGeneration() {
        val old = backup(setOf(1, 2, 3, 4)).let { it.copy(policy = it.policy.copy(
            revisionWall = 9_999_999, progressBranch = "Sother", // mutable edit from a disconnected old client
        )) }
        val reset = backup(emptySet(), generation = "reset").copy(policy = policy.copy(generation = "reset",
            deleted = true, revisionWall = 200, generationWall = 200))
        for (merged in listOf(ChapterReadTransfer.merge(old, reset), ChapterReadTransfer.merge(reset, old))) {
            assertEquals("reset", merged.policy.generation)
            assertTrue(merged.policy.deleted)
            assertEquals(0, merged.counts.single().readCount)
        }
    }

    @Test fun latestConfirmedManifestKeepsBackfillsUnreadAndRemovedReferencesUnresolved() {
        val local = backup(setOf(1, 3))
        val updatedRefs = listOf(refs[0], refs[1], ReadChapterReference("source", null, 9, "/backfill"))
        val incoming = backup(emptySet()).copy(counts = listOf(ChapterReadCountEntity(1, "N", 0, 3,
            ChapterReadTransfer.listRevision(updatedRefs), ChapterReadTransfer.encodeReferences(updatedRefs), 200, 0, "b")))
        val merged = ChapterReadTransfer.merge(local, incoming)
        assertEquals(1, merged.counts.single().readCount)
        assertEquals(3, merged.counts.single().totalCount)
        assertEquals(2, merged.records.size)
    }

    @Test fun ordinaryReorderingDoesNotChangeListEvidence() {
        assertEquals(ChapterReadTransfer.listRevision(refs), ChapterReadTransfer.listRevision(refs.reversed()))
    }

    @Test fun embeddedManifestHasItsOwnDepthAndDuplicateKeyBoundary() {
        val count = backup(emptySet()).counts.single()
        val duplicate = "[{\"source\":\"source\",\"source\":\"other\",\"branch\":null,\"chapterId\":1,\"url\":\"/1\"}]"
        assertThrows(IllegalArgumentException::class.java) {
            ChapterReadTransfer.references(count.copy(chapterReferences = duplicate))
        }
        val deep = "[{\"ignored\":" + "[".repeat(80) + "0" + "]".repeat(80) + "}]"
        assertThrows(IllegalArgumentException::class.java) {
            ChapterReadTransfer.references(count.copy(chapterReferences = deep))
        }
        assertEquals(refs, ChapterReadTransfer.references(count))
    }

    @Test fun allRecordsAndRawBranchIdentitiesRoundTrip() {
        val original = ChapterReadEnvelope(titles = listOf(backup(setOf(1, 3))))
        val restored = Json.decodeFromString<ChapterReadEnvelope>(Json.encodeToString(original))
        restored.validate()
        assertEquals(original, restored)
    }

    @Test fun futureVersionsAndCrossTitleReferencesFailBeforePersistence() {
        assertThrows(IllegalArgumentException::class.java) { ChapterReadEnvelope(2, emptyList()).validate() }
        val bad = backup(setOf(1)).let { it.copy(records = it.records.map { row -> row.copy(mangaId = 99) }) }
        assertThrows(IllegalArgumentException::class.java) { ChapterReadEnvelope(titles = listOf(bad)).validate() }
    }

    @Test fun differentEditionsRequireAnExplicitIdentityDecision() {
        val other = backup(setOf(2)).copy(policy = policy.copy(editionUrl = "/adaptation"))
        assertThrows(IllegalArgumentException::class.java) { ChapterReadTransfer.merge(backup(setOf(1)), other) }
    }

    @Test fun invalidManifestCannotFabricateACompletedTitle() {
        val bad = backup(setOf(1)).let { it.copy(counts = it.counts.map { row -> row.copy(totalCount = 1, readCount = 1) }) }
        assertThrows(IllegalArgumentException::class.java) { bad.validate() }
    }

    @Test fun equalRevisionUnreadAndUrlTiesAreDeterministic() {
        val first = backup(setOf(1))
        val second = first.copy(records = first.records.map { it.copy(originalUrl = "/changed", isRead = false) })
        assertEquals(ChapterReadTransfer.merge(first, second), ChapterReadTransfer.merge(second, first))
        assertEquals(0, ChapterReadTransfer.merge(first, second).counts.single().readCount)
    }
}
