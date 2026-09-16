package org.koitharu.kotatsu.local.data

import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NovelChapterArchiveTest {
    @Test
    fun `chapter text and repeated inline images round trip without remote references`() = runTest {
        val requests = mutableListOf<Pair<String, Int>>()
        val html = "<p>Chapter one &amp; two</p><img src='https://site/a.png'><img src='https://site/a.png'>"
        val archived = NovelChapterArchive.prepare(html) { url, index -> requests += url to index }
        assertEquals(listOf("https://site/a.png" to 1), requests)
        val restored = Jsoup.parseBodyFragment(NovelChapterArchive.resolve(archived, listOf("zip:/book.cbz#1.png")))
        assertEquals("Chapter one & two", restored.text())
        assertEquals(listOf("zip:/book.cbz#1.png", "zip:/book.cbz#1.png"), restored.select("img").map { it.attr("src") })
    }

    @Test
    fun `failed image download cannot produce a completed chapter`() = runTest {
        try {
            NovelChapterArchive.prepare("<p>text</p><img src='https://site/a.png'>") { _, _ ->
                throw IOException("HTTP 503")
            }
            fail("Image failure was swallowed")
        } catch (e: IOException) {
            assertEquals("HTTP 503", e.message)
        }
    }

    @Test(expected = IOException::class)
    fun `missing archive image is reported`() {
        NovelChapterArchive.resolve("<img src='kaisoku-page:2'>", listOf("zip:/book.cbz#1.png"))
    }
}
