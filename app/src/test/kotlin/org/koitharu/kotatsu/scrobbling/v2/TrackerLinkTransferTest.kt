package org.koitharu.kotatsu.scrobbling.v2

import org.junit.Assert.*
import org.junit.Test

class TrackerLinkTransferTest {
    private fun link() = TrackerLinkReplica(481, "COMICK", "account/opaque", "REMANGA", "/edition",
        "media-uuid", "900719925474099312345", "125.75", "2.50", "chapter-opaque", "chapter",
        "reading", "2026-10-22", false, 100, 4, "replica")

    @Test fun opaqueAndFractionalValuesRoundTripWithoutLocalApprovalOrCounters() {
        val encoded = TrackerLinkTransfer.encode(TrackerLinkEnvelope(links = listOf(link())))
        val decoded = TrackerLinkTransfer.decode(encoded.toByteArray()).links.single()
        assertTrue(decoded.sameValue(link()))
        assertEquals("900719925474099312345", decoded.listEntryId)
        assertEquals("125.75", decoded.progressNumber)
        assertFalse(encoded.contains("confirmed"))
        assertFalse(encoded.contains("\"revision\""))
        assertEquals(false, decoded.entity(99, 1, false).confirmed)
    }

    @Test fun causalMergeIsDeterministicAndDeletionSurvivesRepeatedOlderCopies() {
        val older = link()
        val deleted = older.copy(deleted = true, logical = 5)
        assertEquals(deleted, TrackerLinkTransfer.newer(older, deleted))
        assertEquals(deleted, TrackerLinkTransfer.newer(deleted, older))
        assertEquals(deleted, TrackerLinkTransfer.newer(deleted, deleted))
        assertTrue(runCatching { TrackerLinkTransfer.newer(older, older.copy(mediaId = "other")) }.isFailure)
        assertTrue(runCatching { TrackerLinkTransfer.newer(older, older.copy(editionUrl = "/other")) }.isFailure)
    }

    @Test fun duplicateLogicalKeysUnsupportedVersionsAndMalformedUtf8AreRejected() {
        assertTrue(runCatching { TrackerLinkTransfer.encode(TrackerLinkEnvelope(links = listOf(link(), link().copy(mangaId = 2)))) }.isFailure)
        assertTrue(runCatching { TrackerLinkEnvelope(2, listOf(link())).validate() }.isFailure)
        assertTrue(runCatching { TrackerLinkTransfer.decode(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        assertTrue(runCatching { link().copy(progressUnit = null).validate() }.isFailure)
        assertTrue(runCatching { link().copy(editionUrl = "").validate() }.isFailure)
        val valid = TrackerLinkTransfer.encode(TrackerLinkEnvelope(links = listOf(link())))
        val duplicate = valid.replaceFirst("{", "{\"version\":0,")
        assertTrue(runCatching { TrackerLinkTransfer.decode(duplicate.toByteArray()) }.isFailure)
        val tooDeep = valid.replaceFirst("{", "{\"unknown\":" + "[".repeat(65) + "0" + "]".repeat(65) + ",")
        assertTrue(runCatching { TrackerLinkTransfer.decode(tooDeep.toByteArray()) }.isFailure)
    }

    @Test fun orderingIsStableAndUnknownFieldsRemainForwardCompatible() {
        val a = link()
        val b = a.copy(mangaId = 2, editionUrl = "/other", mediaId = "another")
        val one = TrackerLinkTransfer.encode(TrackerLinkEnvelope(links = listOf(a, b)))
        val two = TrackerLinkTransfer.encode(TrackerLinkEnvelope(links = listOf(b, a)))
        assertEquals(one, two)
        val value = one.replaceFirst("{", "{\"future\":true,")
        assertEquals(2, TrackerLinkTransfer.decode(value.toByteArray()).links.size)
    }
}
