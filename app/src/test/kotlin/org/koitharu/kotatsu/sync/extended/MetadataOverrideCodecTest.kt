package org.koitharu.kotatsu.sync.extended

import org.junit.Assert.*
import org.junit.Test

class MetadataOverrideCodecTest {
    private fun field(name: String = "title", value: String? = "Saved") = MetadataOverrideReplica(
        481, "REMANGA", "/edition", name, value, 100, 2, "fixture")

    @Test fun resetsAndIndependentFieldsRoundTripDeterministically() {
        val values = listOf(field(), field("description", null), field("content_rating", "SAFE"))
        val encoded = MetadataOverrideCodec.encode(MetadataOverrideEnvelope(fields = values))
        assertArrayEquals(encoded, MetadataOverrideCodec.encode(MetadataOverrideEnvelope(fields = values.reversed())))
        val decoded = MetadataOverrideCodec.decode(encoded)
        assertEquals(3, decoded.fields.size)
        assertNull(decoded.fields.single { it.field == "description" }.value)
        assertEquals("Saved", decoded.fields.single { it.field == "title" }.value)
    }

    @Test fun duplicateIdentitiesUnknownFormatsAndUnsafeCoverReferencesAreRejected() {
        for (value in listOf(MetadataOverrideEnvelope(version = 2, fields = emptyList()),
            MetadataOverrideEnvelope(fields = listOf(field(), field())),
            MetadataOverrideEnvelope(fields = listOf(field("unknown"))),
            MetadataOverrideEnvelope(fields = listOf(field("content_rating", "UNKNOWN"))),
            MetadataOverrideEnvelope(fields = listOf(field("cover", "file:///private/other"))),
            MetadataOverrideEnvelope(fields = listOf(field("cover", "https://user:password@example.test/x"))))) {
            assertThrows(IllegalArgumentException::class.java) { MetadataOverrideCodec.encode(value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MetadataOverrideCodec.decode("{\"version\":1,\"version\":1,\"fields\":[]}".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { MetadataOverrideCodec.decode(byteArrayOf(0xc0.toByte(), 0x80.toByte())) }
    }

    @Test fun semanticCoverIdentityDoesNotDependOnDeviceLocalStagingPaths() {
        val hash = "a".repeat(64)
        val state = MetadataOverrideState(481, "REMANGA", "/edition", "cover", "file:///local/new.img", hash, 100, 2, "fixture")
        val replica = field("cover", "sha256:$hash").copy(coverSha256 = hash, coverData = "AAAA")
        assertTrue(MetadataOverrideCodec.sameValue(state, replica))
        assertFalse(MetadataOverrideCodec.sameValue(state, replica.copy(value = "sha256:${"b".repeat(64)}")))
        assertFalse(MetadataOverrideCodec.sameValue(state, replica.copy(revisionLogical = 3)))
    }
}
