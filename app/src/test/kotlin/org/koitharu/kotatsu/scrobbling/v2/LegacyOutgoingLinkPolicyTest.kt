package org.koitharu.kotatsu.scrobbling.v2

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity

class LegacyOutgoingLinkPolicyTest {
    private val manga = MangaBackup(481, "Fixture", url = "/edition", publicUrl = "https://fixture.invalid/edition",
        coverUrl = "", source = "REMANGA").toEntity()
    private val old = ScrobblingEntity(2, 7, 481, 42, "CURRENT", 12, "keep", 8f, 123)
    private val current = TrackerLinkV2Entity("ANILIST", "123", 481, "42", "7", "12.5", null, null, "chapter",
        "CURRENT", null, 1, false, "REMANGA", "/edition", 100, 0, "fixture", true)

    @Test fun existingNumericBehaviorRemainsAvailableWithoutExtendedOwnership() {
        assertTrue(LegacyOutgoingLinkPolicy.allows(null, old, manga))
        assertTrue(LegacyOutgoingLinkPolicy.allows(current, old, manga))
        assertTrue(LegacyOutgoingLinkPolicy.allows(current.copy(listEntryId = null), old, manga))
    }

    @Test fun correctedOrOpaqueReferencesCannotSendToThePreviousNumericTarget() {
        for (value in listOf(current.copy(mediaId = "43"), current.copy(mediaId = "opaque-work"),
            current.copy(listEntryId = "9007199254740993123"), current.copy(mangaId = 482))) {
            assertFalse(LegacyOutgoingLinkPolicy.allows(value, old, manga))
        }
    }

    @Test fun pendingDeletedAndChangedSourceEditionsStayInactive() {
        for (value in listOf(current.copy(confirmed = false), current.copy(deleted = true),
            current.copy(source = "MANGADEX"), current.copy(editionUrl = "/other"))) {
            assertFalse(LegacyOutgoingLinkPolicy.allows(value, old, manga))
        }
        assertFalse(LegacyOutgoingLinkPolicy.allows(current, old, null))
    }
}
