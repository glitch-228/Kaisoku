package org.koitharu.kotatsu.backups.mihon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ForeignImportEnvelopeTest {
    private val pending = PendingTrackerImportEntity("b".repeat(64), "a".repeat(64), 123, "mihon:7", "/book",
        "anilist", 2, Long.MAX_VALUE.toString(), (Long.MAX_VALUE - 1).toString(), "12.5", "2", "8.75", 500)

    @Test fun optionalVersionedSectionKeepsRawValuesAndHasNoAccountIdentity() {
        val original = ForeignImportEnvelope(pending = listOf(pending), receipts = listOf(ForeignImportReceiptEntity(
            pending.importDigest, pending.mangaId, pending.source, pending.editionUrl, 500)))
        original.validate()
        val text = Json.encodeToString(original)
        val restored = Json.decodeFromString<ForeignImportEnvelope>(text)
        restored.validate()
        assertEquals(original, restored)
        assertFalse(text.contains("account_id"))
        assertFalse(text.contains("accountId"))
        assertEquals("12.5", restored.pending.single().rawProgress)
    }

    @Test fun unsupportedVersionsAndConflictingOwnershipAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ForeignImportEnvelope(version = 2).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            ForeignImportEnvelope(pending = listOf(pending, pending.copy(importId = "c".repeat(64), source = "another"))).validate()
        }
    }

    @Test fun duplicateReceiptsAndOversizedEvidenceDoNotEnterTheStore() {
        val receipt = ForeignImportReceiptEntity(pending.importDigest, 123, pending.source, "/book", 500)
        assertThrows(IllegalArgumentException::class.java) { ForeignImportEnvelope(receipts = listOf(receipt, receipt)).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            ForeignImportEnvelope(pending = listOf(pending.copy(rawPayload = "x".repeat(131_073)))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeignImportEnvelope(pending = listOf(pending.copy(importDigest = "not a digest"))).validate()
        }
    }
}
