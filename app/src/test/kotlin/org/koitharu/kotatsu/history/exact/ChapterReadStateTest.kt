package org.koitharu.kotatsu.history.exact

import org.junit.Assert.*
import org.junit.Test

class ChapterReadStateTest {
    private val version = ReadRevision(100, 0, "device-a")
    private fun chapter(id: Long, branch: String? = null, url: String = "/chapter/$id", source: String = "source") =
        ReadChapterReference(source, branch, id, url)
    private fun record(ref: ReadChapterReference, read: Boolean, revision: ReadRevision = version, generation: String = "one") =
        ExplicitChapterRead(ref.key, generation, ref, read, revision)

    @Test
    fun unreadHolesDoNotDependOnOpeningLaterChapters() {
        val chapters = (1L..4L).map { chapter(it) }
        val result = ChapterReadResolver.resolve("one", chapters, listOf(record(chapters[0], true), record(chapters[2], true)))
        assertEquals(setOf(1L, 3L), result.readChapterIds)
        assertEquals(2, result.resolvedCount)
    }

    @Test
    fun resetsExcludeAllOldRecordsEvenThoseWithHigherIndividualClock() {
        val ref = chapter(1)
        val old = record(ref, true, ReadRevision(999_999, 9, "other"), "old")
        assertTrue(ChapterReadResolver.resolve("new", listOf(ref), listOf(old)).readChapterIds.isEmpty())
    }

    @Test
    fun reorderedConcurrentReadUnreadUpdatesConverge() {
        val ref = chapter(1)
        val older = record(ref, true)
        val latest = record(ref, false, version.next(90, "device-a"))
        val forward = ChapterReadResolver.resolve("one", listOf(ref), listOf(older, latest))
        val reverse = ChapterReadResolver.resolve("one", listOf(ref), listOf(latest, older))
        assertEquals(forward, reverse)
        assertTrue(forward.readChapterIds.isEmpty())
    }

    @Test
    fun unreadWinsAnExactRevisionTie() {
        val ref = chapter(1)
        for (updates in listOf(listOf(record(ref, true), record(ref, false)), listOf(record(ref, false), record(ref, true)))) {
            assertTrue(ChapterReadResolver.resolve("one", listOf(ref), updates).readChapterIds.isEmpty())
        }
    }

    @Test
    fun exactIdsKeepWorkingAfterUrlChurnWithoutGuessingNewIds() {
        val original = chapter(1, url = "/old")
        assertEquals(setOf(1L), ChapterReadResolver.resolve("one", listOf(chapter(1, url = "/new")),
            listOf(record(original, true))).readChapterIds)
        assertTrue(ChapterReadResolver.resolve("one", listOf(chapter(2, url = "/new")),
            listOf(record(original, true))).readChapterIds.isEmpty())
    }

    @Test
    fun uniqueScopedUrlAliasesCanResolveAndAmbiguityCannot() {
        val original = chapter(1, url = "/old")
        val alias = ChapterReadAlias(original.key, original.source, original.branchKey, "/new")
        assertEquals(setOf(2L), ChapterReadResolver.resolve("one", listOf(chapter(2, url = "/new")),
            listOf(record(original, true)), listOf(alias)).readChapterIds)
        assertTrue(ChapterReadResolver.resolve("one", listOf(chapter(2, url = "/new"), chapter(3, url = "/new")),
            listOf(record(original, true)), listOf(alias)).readChapterIds.isEmpty())
        val competing = chapter(9, url = "/new")
        assertTrue(ChapterReadResolver.resolve("one", listOf(chapter(2, url = "/new")),
            listOf(record(original, true), record(competing, true)), listOf(alias)).readChapterIds.isEmpty())
    }

    @Test
    fun sourceAndBranchArePartOfIdentityIncludingEmptyBranch() {
        assertNotEquals(chapter(1).key, chapter(1, "").key)
        val original = chapter(1)
        val foreignAlias = ChapterReadAlias(original.key, "different", encodeReadBranch(null), "/chapter/1")
        assertTrue(ChapterReadResolver.resolve("one", listOf(chapter(1, source = "different")),
            listOf(record(original, true)), listOf(foreignAlias)).readChapterIds.isEmpty())
        assertTrue(ChapterReadResolver.resolve("one", listOf(chapter(1, "")),
            listOf(record(original, true))).readChapterIds.isEmpty())
        assertNull(decodeReadBranch(encodeReadBranch(null)))
        assertEquals("", decodeReadBranch(encodeReadBranch("")))
    }

    @Test
    fun removedReferencesRemainUnresolvedAndBackfillsStayUnread() {
        val old = record(chapter(1), true)
        val result = ChapterReadResolver.resolve("one", listOf(chapter(2), chapter(3)), listOf(old))
        assertEquals(1, result.unresolved)
        assertTrue(result.readChapterIds.isEmpty())
        assertEquals(setOf(1L), ChapterReadResolver.resolve("one", listOf(chapter(1), chapter(2), chapter(3)),
            listOf(old)).readChapterIds)
    }

    @Test
    fun duplicateReferencesCannotInflateReadCounts() {
        val ref = chapter(1)
        val result = ChapterReadResolver.resolve("one", listOf(ref), List(30) { record(ref, true) })
        assertEquals(1, result.resolvedCount)
        assertEquals(setOf(1L), result.readChapterIds)
    }

    @Test
    fun clockRemainsCausalWithBackwardDeviceTimeAndObservedFuture() {
        val first = version.next(50, "device-a")
        assertTrue(first > version)
        val observed = ReadRevision(1_000_000, 15, "remote")
        assertTrue(maxOf(first, observed).next(2, "device-a") > observed)
        assertEquals(0, version.next(101, "device-a").logical)
    }

    @Test
    fun identityHashIsUnambiguousForDelimitedUnicodeAndRtlNames() {
        assertNotEquals(readIdentityHash(listOf("a:b", "c")), readIdentityHash(listOf("a", "b:c")))
        assertEquals(chapter(0, "العربية中文").key, chapter(0, "العربية中文").key)
    }

    @Test
    fun unknownAndBoundedCountsNeverInventCompletion() {
        assertEquals(0f, ChapterReadCountEntity(1, "N", 0, 0, "x").percent)
        assertEquals(0.5f, ChapterReadCountEntity(1, "N", 2, 4, "x").percent)
        assertTrue(ChapterReadCountEntity(1, "N", 2, 4, "x").percent.isFinite())
    }
}
