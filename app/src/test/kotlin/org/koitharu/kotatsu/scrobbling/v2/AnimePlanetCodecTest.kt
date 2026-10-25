package org.koitharu.kotatsu.scrobbling.v2

import org.junit.Assert.*
import org.junit.Test

class AnimePlanetCodecTest {
    private val token = "a".repeat(40)
    private val owner = TrackerOwner("ANIME_PLANET", "ReaderName")

    @Test
    fun verifiedProfileRequiresLoggedInUserAndTokenRatherThanCookiePresence() {
        val profile = AnimePlanetCodec.profile("""
            <div class="loggedIn"><a href="/users/ReaderName" title="ReaderName">Profile</a></div>
            <script>TOKEN = '$token';</script>
        """)
        assertEquals("ReaderName", profile.username)
        assertFalse(profile.toString().contains(token))
        assertThrows(IllegalStateException::class.java) { AnimePlanetCodec.profile("<html>Please sign in</html>") }
        assertThrows(IllegalStateException::class.java) {
            AnimePlanetCodec.profile("""<div class="loggedIn"><a href="/users/ReaderName">Profile</a></div>""")
        }
    }

    @Test
    fun titlePageKeepsFormIdentityRawProgressUnknownStatusesAndMetadataSeparate() {
        val page = AnimePlanetCodec.titlePage(page(), "example-manga", owner)
        assertEquals("1234567890123", page.entry.reference.mediaId)
        assertEquals("12.5", page.entry.progress?.number)
        assertEquals("2", page.entry.progress?.volume)
        assertEquals("WONT_READ", page.entry.status)
        assertEquals("3.5", page.entry.rating)
        assertEquals("Example Manga", page.entry.title.title)
        assertTrue(page.entry.title.description.orEmpty().contains("Synopsis"))
        assertFalse(page.entry.title.toString().contains(token))
        assertThrows(IllegalStateException::class.java) {
            AnimePlanetCodec.titlePage("<h1>Challenge</h1>", "example-manga", owner)
        }
    }

    @Test
    fun mutatingGetConstructionChangesOnlySelectedFieldsAndNeverTruncatesFractions() {
        val urls = AnimePlanetCodec.mutationUrls("123", token, TrackerPatch.statusOnly("PAUSED"))
        assertEquals(1, urls.size)
        assertEquals("/api/list/status/manga/123/5/$token", urls.single().encodedPath)
        assertNull(urls.single().query)
        assertThrows(IllegalArgumentException::class.java) {
            AnimePlanetCodec.mutationUrls("123", token,
                TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("12.5"))))
        }
        val progress = AnimePlanetCodec.mutationUrls("123", token,
            TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("12", "2"))))
        assertEquals("/api/list/update/manga/123/12/2/$token", progress.single().encodedPath)
    }

    @Test
    fun titleUrlsRejectForeignHostsAndCredentials() {
        assertEquals("example-manga", AnimePlanetCodec.slugFromUrl("https://www.anime-planet.com/manga/example-manga"))
        for (uri in listOf("https://evil.test/manga/example", "http://www.anime-planet.com/manga/example",
            "https://u:p@www.anime-planet.com/manga/example", "https://www.anime-planet.com/manga/example?next=evil")) {
            assertThrows(IllegalArgumentException::class.java) { AnimePlanetCodec.slugFromUrl(uri) }
        }
    }

    private fun page() = """
        <h1 itemprop="name">Example Manga</h1>
        <div itemprop="description">Synopsis <b>text</b></div>
        <form id="manga123" data-id="1234567890123">
          <select class="changeStatus"><option value="0">None</option><option value="6" selected>Won't read</option></select>
          <select class="chapters" name="chapters"><option value="12.5" selected>12.5</option></select>
          <select class="volumes" name="volumes"><option value="2" selected>2</option></select>
          <div class="starrating"><div name="3.5"></div></div>
        </form><script>TOKEN = '$token';</script>
    """
}
