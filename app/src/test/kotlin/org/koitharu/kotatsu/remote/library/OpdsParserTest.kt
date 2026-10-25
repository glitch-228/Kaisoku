package org.koitharu.kotatsu.remote.library

import org.junit.Assert.*
import org.junit.Test

class OpdsParserTest {
    @Test fun atomPreservesExactIdsRelativeBasesAndZeroBasedPageStreaming() {
        val feed = OpdsParser.feed(xml("""<feed xmlns="http://www.w3.org/2005/Atom"
            xmlns:pse="http://vaemendis.net/opds-pse/ns" xml:base="/shelf/opds/">
            <title>Books 日本語</title><link rel="next" type="application/atom+xml" href="?page=2"/>
            <entry xml:base="../"><id>urn:book:0001/A</id><title>Same title</title><author><name>Author</name></author>
                <dc:language xmlns:dc="http://purl.org/dc/terms/">ar</dc:language><category term="fantasy" label="Fantasy"/>
                <summary type="html">&lt;p&gt;Text&lt;script&gt;bad&lt;/script&gt;&lt;/p&gt;</summary>
                <link rel="http://opds-spec.org/image" type="image/png" href="covers/1.png"/>
                <link rel="http://opds-spec.org/acquisition" type="application/vnd.comicbook+zip" href="books/1.cbz" length="100"/>
                <link rel="http://vaemendis.net/opds-pse/stream" type="image/jpeg" href="pages/1/{pageNumber}" pse:count="2"/>
            </entry><entry><id>urn:book:0002/B</id><title>Same title</title>
                <link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="2.epub"/>
            </entry></feed>"""))
        assertEquals("Books 日本語", feed.title)
        assertEquals(2, feed.publications.size)
        val first = feed.publications.first()
        assertEquals("urn:book:0001/A", first.identifier)
        assertEquals(setOf("Author"), first.authors)
        assertEquals(setOf("ar"), first.languages)
        assertEquals(setOf("Fantasy"), first.subjects)
        assertEquals("Text", first.description)
        assertEquals("https://library.invalid/shelf/covers/1.png", first.cover!!.href)
        assertEquals("https://library.invalid/shelf/books/1.cbz", first.acquisitionLinks.single().href)
        assertEquals("https://library.invalid/shelf/pages/1/0", LibraryTemplates.page(first.pageStream!!, 0))
        assertThrows(LibraryUnsupportedException::class.java) {
            LibraryTemplates.page(first.pageStream!!.copy(href = first.pageStream!!.href + "?width={maxWidth}"), 0)
        }
        assertEquals("https://library.invalid/shelf/opds/?page=2", feed.next!!.href)
        assertEquals(RemoteLibraryContent.UNKNOWN, first.content)
    }

    @Test fun opdsTwoKeepsGroupsFacetsSearchAndLocalizedMetadata() {
        val feed = OpdsParser.feed(json("""{
            "metadata":{"title":{"en":"Catalog","ja":"本"}},
            "links":[{"rel":["self","first"],"href":"/shelf/opds"},
                {"rel":"search","href":"search{?query,title,author}","type":"application/opds+json","templated":true}],
            "navigation":[{"title":"Latest","href":"latest","type":"application/opds+json"}],
            "publications":[{"metadata":{"identifier":"opaque:0000001","title":{"ar":"عنوان"},
                "author":[{"name":"A"},"B"],"language":["ar","zh"],"subject":[{"name":"Fantasy"}],"contentRating":"adult"},
                "links":[{"rel":"download","href":"/shelf/book.epub","type":"application/epub+zip"}]}],
            "facets":[{"metadata":{"title":"Language"},"links":[{"title":"Arabic","href":"?lang=ar","type":"application/opds+json","properties":{"activeFacet":true}}]}],
            "groups":[{"metadata":{"title":"Comics"},"publications":[{"metadata":{"identifier":"comic:2","title":"Other"},
                "links":[{"rel":"download","href":"two.cbz","type":"application/vnd.comicbook+zip"}]}]}]
        }"""))
        assertEquals("Catalog", feed.title)
        assertTrue(feed.search!!.templated)
        assertEquals("https://library.invalid/shelf/latest", feed.navigation.single().href)
        assertEquals("عنوان", feed.publications.single().title)
        assertEquals(setOf("A", "B"), feed.publications.single().authors)
        assertEquals(RemoteLibraryContent.ADULT, feed.publications.single().content)
        assertEquals("Language", feed.facets.single().title)
        assertTrue(feed.facets.single().links.single().active)
        assertEquals("comic:2", feed.groups.single().publications.single().identifier)
    }

    @Test fun readiumManifestRetainsDimensionsAndRejectsNoResourcesByInference() {
        val publication = OpdsParser.publication(json("""{"metadata":{"identifier":"book/edition-B","title":"Book"},
            "readingOrder":[{"href":"/shelf/page0.png","type":"image/png","width":4000,"height":6000},
                {"href":"/shelf/page1.jpg","type":"image/jpeg","width":8000,"height":3000}],
            "resources":[{"rel":"cover","href":"/shelf/cover.png","type":"image/png"}]}"""))
        assertEquals(4000, publication.readingOrder.first().width)
        assertEquals(6000, publication.readingOrder.first().height)
        assertEquals(8000, publication.readingOrder.last().width)
        assertEquals(2, publication.readingOrder.size)
        assertEquals("https://library.invalid/shelf/cover.png", publication.cover!!.href)
        val roundTrip = OpdsParser.publication(LibraryDocument(publication.documentUrl, "application/opds-publication+json", LibraryPublicationCodec.encode(publication)))
        assertEquals(publication, roundTrip)
    }

    @Test fun xmlEntitiesAndDocumentTypesAreRejectedInUtfEightAndSixteen() {
        val text = """<!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><feed><title>&xxe;</title></feed>"""
        for (bytes in listOf(text.toByteArray(), text.toByteArray(Charsets.UTF_16))) {
            assertThrows(RemoteLibraryException::class.java) { OpdsParser.feed(LibraryDocument(BASE, "application/atom+xml", bytes)) }
        }
        assertThrows(RemoteLibraryException::class.java) { OpdsParser.feed(xml("<!DOCTYPE feed SYSTEM \"https://example.invalid/evil.dtd\"><feed><title>Feed</title></feed>")) }
    }

    @Test fun unsafeSchemesAndEmbeddedCredentialsDoNotBecomeActions() {
        val publication = OpdsParser.publication(json("""{"metadata":{"identifier":"id","title":"Book"},"links":[
            {"rel":"download","type":"application/epub+zip","href":"javascript:alert(1)"},
            {"rel":"download","type":"application/epub+zip","href":"file:///private/book.epub"},
            {"rel":"download","type":"application/epub+zip","href":"https://u:password@other.invalid/book.epub"},
            {"rel":"borrow","type":"application/epub+zip","href":"borrow.epub"},
            {"rel":"buy","type":"application/epub+zip","href":"buy.epub"}]}"""))
        assertTrue(publication.acquisitionLinks.isEmpty())
        assertEquals(setOf("borrow", "buy"), publication.links.flatMap { it.rels }.toSet())
    }

    @Test fun limitsRejectDeepDocumentsAndNeverTruncateIdentifiers() {
        assertThrows(LibraryLimitException::class.java) { OpdsParser.feed(json("{".repeat(60) + "}".repeat(60))) }
        val nested = "<a>".repeat(60) + "</a>".repeat(60)
        assertThrows(LibraryLimitException::class.java) { OpdsParser.feed(xml("<feed><title>Feed</title>$nested</feed>")) }
        assertThrows(LibraryLimitException::class.java) {
            OpdsParser.publication(json("""{"metadata":{"identifier":"${"x".repeat(4097)}","title":"Book"}}"""))
        }
        assertThrows(LibraryLimitException::class.java) { OpdsParser.feed(LibraryDocument(BASE, null, ByteArray(MAX_LIBRARY_BODY + 1))) }
    }

    @Test fun malformedXmlIsRejectedInsteadOfBeingRepairedIntoAnEmptyOrDifferentFeed() {
        for (value in listOf("<feed><title>Broken</title>", "<feed><title>Broken</feed></title>",
            "<feed><title duplicate=\"one\" duplicate=\"two\">Broken</title></feed>")) {
            assertThrows(RemoteLibraryException::class.java) { OpdsParser.feed(xml(value)) }
        }
    }

    @Test fun duplicateTitlesRemainSeparateButConflictingExactIdsAreRejected() {
        val feed = """{"metadata":{"title":"Books"},"publications":[
            {"metadata":{"identifier":"edition-A","title":"Book"}},
            {"metadata":{"identifier":"edition-B","title":"Book"}}]}"""
        assertEquals(2, OpdsParser.feed(json(feed)).publications.size)
        assertThrows(RemoteLibraryException::class.java) { OpdsParser.feed(json(feed.replace("edition-B", "edition-A").replace("\"Book\"}}]", "\"Different\"}}]"))) }
    }

    @Test fun openSearchHonorsAdvertisedOffsetsAndEncodesUserInputOnce() {
        val descriptor = OpdsParser.openSearch(xml("""<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <Url type="application/atom+xml" pageOffset="0" indexOffset="0" template="search?q={searchTerms}&amp;page={startPage?}&amp;start={startIndex}&amp;lang={language?}"/>
            <Url type="text/html" template="unsafe?q={searchTerms}"/>
        </OpenSearchDescription>""")).single()
        val query = LibraryTemplates.search(descriptor.href, "日本語 & A/B + C")
        assertTrue(query.contains("q=%E6%97%A5%E6%9C%AC%E8%AA%9E%20%26%20A%2FB%20%2B%20C"))
        assertTrue(query.contains("page=0"))
        assertTrue(query.contains("start=0"))
        assertFalse(query.contains("%25E6"))
        val opdsTwo = LibraryTemplates.search("https://library.invalid/search{?query,title,author}", "مرحبا & test")
        assertTrue(opdsTwo.contains("?query=%D9%85%D8%B1%D8%AD%D8%A8%D8%A7%20%26%20test"))
        assertFalse(opdsTwo.contains("title="))
        assertThrows(LibraryUnsupportedException::class.java) { LibraryTemplates.search("https://library.invalid/{unsupported}", "book") }
    }

    private fun xml(value: String) = LibraryDocument(BASE, "application/atom+xml", value.toByteArray())
    private fun json(value: String) = LibraryDocument(BASE, "application/opds+json", value.toByteArray())
    private companion object { const val BASE = "https://library.invalid/shelf/opds" }
}
