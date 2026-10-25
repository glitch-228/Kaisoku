package org.koitharu.kotatsu.remote.library

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

class LibraryImageReferenceTest {
    @Test fun imageReferencesAreAccountScopedAndNeverContainProviderUrlsOrCredentials() {
        val connection = RemoteLibraryConnection.create("Home", "https://library.invalid/base", RemoteLibraryKind.KOMGA, "reader")
        val cover = LibraryImageReference(connection.id, connection.cacheScope, "edition:0001/日本語")
        assertEquals(cover, LibraryImageReference.parse(cover.toString()))
        val page = cover.copy(pageIndex = 0)
        assertEquals(page, LibraryImageReference.parse(page.toString()))
        assertNotEquals(page.toString(), page.copy(scope = connection.copy(account = "another").cacheScope).toString())
        assertFalse(page.toString().contains("https:"))
        assertFalse(page.toString().contains("reader"))
        assertNull(LibraryImageReference.parse(page.toString() + "?password=secret"))
        assertNull(LibraryImageReference.parse(page.toString().replace("/0", "/-1")))
        assertNull(LibraryImageReference.parse(cover.toString() + "/0"))
        assertNull(LibraryImageReference.parse(page.toString().replace("library-image:", "https:")))
    }

    @Test fun acquiredSourceMetadataNeverChangesLocalIdentityChaptersOrExistingOverrides() {
        val source = RemoteLibrarySource("00000000-0000-0000-0000-000000000034")
        val chapter = MangaChapter(17, "Chapter", 1f, 0, "file:///private/book.cbz", null, 0, null, source)
        val manga = Manga(42, "User title", emptySet(), "file:///private/book.cbz", "file:///private/book.cbz", -1f,
            null, "file:///private/cover.png", emptySet(), null, setOf("Embedded author"),
            description = "User synopsis", chapters = listOf(chapter), source = source)
        val metadata = LibraryAcquiredMetadata("Provider title", setOf("Provider author"), setOf("Genre"), "Provider synopsis", RemoteLibraryContent.ADULT)
        val result = metadata.applyTo(manga)
        assertEquals(manga.id, result.id)
        assertEquals(manga.url, result.url)
        assertEquals(manga.source, result.source)
        assertEquals(manga.title, result.title)
        assertEquals(manga.chapters, result.chapters)
        assertEquals(manga.coverUrl, result.coverUrl)
        assertEquals(setOf("Embedded author"), result.authors)
        assertEquals("User synopsis", result.description)
        assertEquals(ContentRating.ADULT, result.contentRating)
        assertNull(metadata.copy(content = RemoteLibraryContent.UNKNOWN).applyTo(manga).contentRating)
        assertEquals("Provider synopsis", metadata.applyTo(manga.copy(description = null)).description)
    }
}
