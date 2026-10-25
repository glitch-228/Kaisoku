package org.koitharu.kotatsu.local.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubParserTest {
    @Test fun epubTwoNcxAndThreeNavigationPreserveSpineAndMetadata() {
        for (version in listOf("2.0", "3.0")) {
            val source = fixture(version)
            val book = EpubParser.parse(source)
            assertEquals("Example 日本語", book.title)
            assertEquals(setOf("Author"), book.authors)
            assertEquals("ar", book.language)
            assertEquals("urn:uuid:test-book", book.identifier)
            assertEquals(listOf("OPS/first.xhtml", "OPS/second.xhtml"), book.chapters.map { it.path })
            assertEquals(listOf("البداية", "第二章"), book.chapters.map { it.title })
            assertEquals("OPS/pictures/cover.jpg", book.coverPath)
            assertEquals("entry", book.navigation.first().fragment)
            assertFalse(source.reads.any { it.endsWith("first.xhtml") || it.endsWith("second.xhtml") || it.endsWith("cover.jpg") })
        }
    }

    @Test fun chapterTextUsesOnlyDeclaredLocalIllustrationsAndKeepsRtl() {
        val source = fixture("3.0")
        source.put("OPS/first.xhtml", """<html><body><h1 id="entry" dir="rtl">مرحبا</h1><p onclick="bad()">Text
            <img src="pictures/cover.jpg" srcset="https://example.invalid/tracker.png"/><img src="https://example.invalid/remote.jpg" alt="Remote"/>
            <svg><image xlink:href="pictures/cover.jpg"/></svg><script>danger()</script><style>bad</style>
            <iframe src="https://example.invalid/iframe"></iframe><a href="https://example.invalid/">Link</a>
            <a href="#entry">Local</a></p></body></html>""")
        val book = EpubParser.parse(source)
        val requested = ArrayList<String>()
        val html = book.readChapter(source, book.chapters.first()) { path -> requested += path; "local-book:$path" }
        assertEquals(listOf("OPS/pictures/cover.jpg", "OPS/pictures/cover.jpg"), requested)
        assertTrue(html.contains("dir=\"rtl\""))
        assertTrue(html.contains("href=\"#entry\""))
        assertFalse(html.contains("https:"))
        assertFalse(html.contains("script"))
        assertFalse(html.contains("danger()"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("iframe"))
        assertFalse(html.contains("srcset"))
    }

    @Test fun encodedUnicodePathsAndAnchorsResolveWithoutNetwork() {
        val resolved = EpubPaths.resolve("OPS/nav.xhtml", "chapters/%E7%AC%AC%E4%B8%80%E7%AB%A0.xhtml#%E5%BC%80%E5%A7%8B")
        assertEquals("OPS/chapters/第一章.xhtml", resolved.path)
        assertEquals("开始", resolved.fragment)
        assertEquals("OPS/first.xhtml", EpubPaths.resolve("OPS/sub/chapter.xhtml", "../first.xhtml").path)
        for (path in listOf("../../escape", "%2e%2e/%2e%2e/escape", "https://remote.invalid/a", "//remote.invalid/a", "/outside", "a?query=1", "a%00b", "a%5cb", "%ff")) {
            expect<EpubException.Invalid> { EpubPaths.resolve("OPS/package.opf", path) }
        }
    }

    @Test fun chapterIdentityDoesNotDependOnOrdinalOrRenderedText() {
        val source = fixture("3.0")
        val original = EpubParser.parse(source)
        source.put("OPS/first.xhtml", "<html><body>Completely changed rendered text</body></html>")
        val changed = EpubParser.parse(source)
        assertEquals(original.chapterIdentity("local/book.epub", original.chapters.first()),
            changed.chapterIdentity("local/book.epub", changed.chapters.first()))
        assertFalse(original.chapterIdentity("local/book.epub", original.chapters.first()) ==
            original.chapterIdentity("local/other.epub", original.chapters.first()))
    }

    @Test fun drmAndFixedLayoutAndUnknownSpineResourcesAreExplicitFailures() {
        val drm = fixture("3.0").apply { put("META-INF/encryption.xml", """<encryption><EncryptedData><EncryptionMethod Algorithm="https://example.invalid/drm"/></EncryptedData></encryption>""") }
        expect<EpubException.Unsupported> { EpubParser.parse(drm) }
        val layout = fixture("3.0").apply { replace("OPS/package.opf", "</metadata>", "<meta property=\"rendition:layout\">pre-paginated</meta></metadata>") }
        expect<EpubException.Unsupported> { EpubParser.parse(layout) }
        val unknown = fixture("3.0").apply { replace("OPS/package.opf", "idref=\"first\"", "idref=\"missing\"") }
        expect<EpubException.Invalid> { EpubParser.parse(unknown) }
    }

    @Test fun obfuscatedFontsAreIgnoredButTextUsingAFontAlgorithmIsNotAccepted() {
        val source = fixture("3.0").apply {
            put("OPS/font.otf", "not loaded")
            put("META-INF/encryption.xml", """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><CipherData><CipherReference URI="OPS/font.otf"/></CipherData></EncryptedData></encryption>""")
        }
        assertEquals(2, EpubParser.parse(source).chapters.size)
        assertFalse("OPS/font.otf" in source.reads)
        source.replace("META-INF/encryption.xml", "OPS/font.otf", "OPS/first.xhtml")
        expect<EpubException.Unsupported> { EpubParser.parse(source) }
    }

    @Test fun entitiesAndTraversalAreRejectedWhileNcxExternalDoctypeIsNeverFetched() {
        val safe = fixture("2.0").apply {
            replace("OPS/toc.ncx", "<ncx>", "<!DOCTYPE ncx SYSTEM \"https://127.0.0.1:1/not-fetched.dtd\"><ncx>")
        }
        assertEquals(2, EpubParser.parse(safe).chapters.size)
        val entity = fixture("3.0").apply {
            replace("OPS/package.opf", "<package", "<!DOCTYPE package [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]><package")
        }
        expect<EpubException.Invalid> { EpubParser.parse(entity) }
        val escaped = fixture("3.0").apply { replace("OPS/package.opf", "first.xhtml", "../../outside.xhtml") }
        expect<EpubException.Invalid> { EpubParser.parse(escaped) }
    }

    @Test fun duplicateManifestNamesAndMissingResourcesAndOversizedChaptersAreRejected() {
        val duplicate = fixture("3.0").apply { replace("OPS/package.opf", "id=\"second\"", "id=\"first\"") }
        expect<EpubException.Invalid> { EpubParser.parse(duplicate) }
        val missing = fixture("3.0").apply { data.remove("OPS/first.xhtml") }
        expect<EpubException.Invalid> { EpubParser.parse(missing) }
        val large = fixture("3.0").apply { reportedSizes["OPS/first.xhtml"] = EpubParser.MAX_CHAPTER_BYTES + 1 }
        expect<EpubException.Limit> { EpubParser.parse(large) }
        assertFalse("OPS/first.xhtml" in large.reads)
    }

    @Test fun malformedOrRemoteNavigationIsSkippedWithoutChangingSpine() {
        val source = fixture("3.0").apply {
            replace("OPS/nav.xhtml", "first.xhtml#entry", "https://example.invalid/wrong")
        }
        val book = EpubParser.parse(source)
        assertEquals(listOf("OPS/first.xhtml", "OPS/second.xhtml"), book.chapters.map { it.path })
        assertEquals("1", book.chapters.first().title)
        assertEquals(1, book.navigation.size)
    }

    private fun fixture(version: String): MemoryBook {
        val nav = if (version.startsWith("3")) "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
            else "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
        return MemoryBook().apply {
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OPS/package.opf", """<package version="$version" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Example 日本語</dc:title><dc:creator>Author</dc:creator><dc:language>ar</dc:language><dc:identifier id="book-id">urn:uuid:test-book</dc:identifier>
                <meta name="cover" content="cover"/></metadata><manifest>$nav
                <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover" href="pictures/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                </manifest><spine toc="ncx"><itemref idref="first"/><itemref idref="second"/></spine></package>""")
            put("OPS/first.xhtml", "<html><body><p>First</p></body></html>")
            put("OPS/second.xhtml", "<html><body><p>Second</p></body></html>")
            put("OPS/pictures/cover.jpg", "fixture image bytes")
            if (version.startsWith("3")) put("OPS/nav.xhtml", """<html><body><nav epub:type="toc"><ol><li><a href="first.xhtml#entry">البداية</a></li><li><a href="second.xhtml">第二章</a></li></ol></nav></body></html>""")
            else put("OPS/toc.ncx", """<ncx><navMap><navPoint><navLabel><text>البداية</text></navLabel><content src="first.xhtml#entry"/></navPoint><navPoint><navLabel><text>第二章</text></navLabel><content src="second.xhtml"/></navPoint></navMap></ncx>""")
        }
    }

    private class MemoryBook : BookArchive {
        val data = LinkedHashMap<String, ByteArray>()
        val reads = ArrayList<String>()
        val reportedSizes = HashMap<String, Long>()
        override val entries get() = data.map { BookArchiveEntry(it.key, reportedSizes[it.key] ?: it.value.size.toLong()) }
        override fun read(name: String, maximumBytes: Long): ByteArray {
            reads += name
            val bytes = data.getValue(name)
            if (bytes.size > maximumBytes) throw EpubException.Limit("Fixture exceeded requested bound")
            return bytes.copyOf()
        }
        fun put(name: String, text: String) { data[name] = text.toByteArray() }
        fun replace(name: String, old: String, new: String) { put(name, data.getValue(name).toString(Charsets.UTF_8).replace(old, new)) }
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got $error", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
