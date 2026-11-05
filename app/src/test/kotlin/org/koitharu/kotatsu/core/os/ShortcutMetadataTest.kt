package org.koitharu.kotatsu.core.os

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class ShortcutMetadataTest {
    private val manga = Manga(7, "Title", emptySet(), "/title", "https://example.org/title", 0f,
        null, "https://example.org/cover.jpg", emptySet(), null, emptySet(), null, null, null,
        MangaParserSource.READMANGA_RU)

    @Test fun pageOnlyHistoryChangesReusePublishedIcons() {
        val keys = listOf(manga.shortcutFingerprint())
        assertTrue(canReuseDynamicShortcuts(keys, keys))
        assertEquals(keys, listOf(manga.copy(description = "Updated metadata").shortcutFingerprint()))
    }

    @Test fun reorderMembershipAndLegacyShortcutsRequirePublication() {
        val first = manga.shortcutFingerprint()
        val second = manga.copy(id = 8).shortcutFingerprint()
        assertFalse(canReuseDynamicShortcuts(listOf(first, second), listOf(second, first)))
        assertFalse(canReuseDynamicShortcuts(listOf(first), listOf(first, second)))
        assertFalse(canReuseDynamicShortcuts(listOf(first), listOf(null)))
        assertTrue(canReuseDynamicShortcuts(emptyList(), emptyList()))
    }

    @Test fun visibleTitleCoverAndIdentityChangesInvalidateIcons() {
        val old = manga.shortcutFingerprint()
        for (changed in listOf(manga.copy(id = 8), manga.copy(title = "Another"),
            manga.copy(coverUrl = "https://example.org/new.jpg"), manga.copy(coverUrl = null))) {
            assertFalse(canReuseDynamicShortcuts(listOf(changed.shortcutFingerprint()), listOf(old)))
        }
    }

    @Test fun failedCoverRetriesAreBoundedWithoutPermanentlyKeepingThePlaceholder() {
        val keys = listOf(manga.shortcutFingerprint())
        assertTrue(canReuseDynamicShortcuts(keys, keys, listOf(1000), now = 999))
        assertFalse(canReuseDynamicShortcuts(keys, keys, listOf(1000), now = 1000))
        assertTrue(canReuseDynamicShortcuts(keys, keys, listOf(0), now = 1000))
    }

    @Test fun fingerprintsAreStableAndLengthFramed() {
        assertEquals(manga.shortcutFingerprint(), manga.copy().shortcutFingerprint())
        assertEquals(manga.copy(coverUrl = null).shortcutFingerprint(), manga.copy(coverUrl = "").shortcutFingerprint())
        assertNotEquals(manga.copy(title = "ab", altTitles = setOf("c")).shortcutFingerprint(),
            manga.copy(title = "a", altTitles = setOf("bc")).shortcutFingerprint())
    }
}
