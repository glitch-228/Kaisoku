package org.koitharu.kotatsu.core.model

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.remote.library.*
import java.util.UUID

class RemoteLibraryContentPolicyTest {
    private val id = "d2039f1a-d590-42fd-b47b-6de417b95375"
    private val local = Manga(41, "Saved local title", emptySet(), "file:///fixture/book.cbz", "file:///fixture/book.cbz",
        -1f, null, null, emptySet(), null, emptySet(), source = LocalMangaSource)

    @Test fun disconnectedIdentityStaysExactAndUnknownIsConservativelyMixed() {
        RemoteLibrarySourceRegistry.replaceAll(emptyList())
        val source = MangaSource("library:$id")
        assertEquals("library:$id", source.name)
        assertTrue(source is RemoteLibrarySource)
        assertEquals(SourceContentClass.MIXED, source.contentClass())
        assertTrue(source.isNsfw())
        assertEquals("library:not-a-uuid", MangaSource("library:not-a-uuid").name)
    }

    @Test fun acquiredRatingsSurviveDisconnectAndDoNotChangeLocalIdentityOrUserOverrides() {
        try {
            val metadata = LibraryAcquiredMetadata("Provider title", setOf("Author"), setOf("Genre"), "Source synopsis", RemoteLibraryContent.MIXED)
            val record = LibraryAcquisitionRecord(id, "opaque/provider/作品", local.id, local.url, "1".repeat(64),
                LibraryFormat.CBZ, metadata)
            RemoteLibrarySourceRegistry.replaceAll(emptyList(), listOf(record))
            assertTrue(local.isNsfw())
            assertEquals(SourceContentClass.MIXED, local.contentClassForFiltering())
            assertFalse(SourceContentPolicy(false, emptySet(), true).allows(local))
            assertTrue(SourceContentPolicy(true, setOf(SourceContentClass.MIXED), true).allows(local))
            assertFalse(SourceContentPolicy(true, setOf(SourceContentClass.SAFE), false).allows(local))
            assertFalse(local.isNsfwForFiltering(true))
            val decorated = metadata.applyTo(local)
            assertEquals(local.id, decorated.id)
            assertEquals(local.url, decorated.url)
            assertEquals(local.chapters, decorated.chapters)
            assertNull(decorated.contentRating)
            assertEquals("Source synopsis", decorated.description)
            val override = MangaOverride(title = "User title", coverUrl = null, contentRating = null, description = "User synopsis")
            assertEquals("User title", decorated.withOverride(override).title)
            assertEquals("User synopsis", decorated.withOverride(override).description)
            assertTrue(decorated.withOverride(override).isNsfw())
            assertEquals(SourceContentClass.ADULT, local.copy(contentRating = ContentRating.ADULT).contentClassForFiltering())
        } finally { RemoteLibrarySourceRegistry.replaceAll(emptyList()) }
    }

    @Test fun renamingConnectionKeepsIdentityAndUpdatesSavedSourceClassification() {
        try {
            val connection = RemoteLibraryConnection(id, "Old name", "https://example.invalid/opds/", "https://example.invalid/",
                RemoteLibraryKind.OPDS, "", RemoteLibraryContent.SAFE)
            RemoteLibrarySourceRegistry.replaceAll(listOf(connection))
            val before = MangaSource(connection.sourceName) as RemoteLibrarySource
            RemoteLibrarySourceRegistry.replaceAll(listOf(connection.copy(name = "New name", content = RemoteLibraryContent.ADULT)))
            assertEquals(before.name, MangaSource(connection.sourceName).name)
            assertEquals("New name", before.resolved().displayName)
            assertEquals(SourceContentClass.ADULT, before.contentClass())
            assertTrue(before.isNsfw())
        } finally { RemoteLibrarySourceRegistry.replaceAll(emptyList()) }
    }
}
