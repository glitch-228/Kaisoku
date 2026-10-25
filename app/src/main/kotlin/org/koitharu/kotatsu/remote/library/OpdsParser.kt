package org.koitharu.kotatsu.remote.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/** Read-only format parsing. It never follows links, resolves JSON-LD contexts, or loads XML entities. */
object OpdsParser {
    fun feed(document: LibraryDocument): LibraryFeed {
        checkBody(document.bytes)
        return if (isJson(document)) jsonFeed(json(document.bytes), document.url) else atomFeed(xml(document), document.url)
    }

    fun publication(document: LibraryDocument): LibraryPublication {
        checkBody(document.bytes)
        return if (isJson(document)) jsonPublication(json(document.bytes), document.url) else {
            val doc = xml(document)
            val entry = doc.children().firstOrNull { it.localName() == "entry" }
                ?: throw RemoteLibraryException("The library returned no publication entry")
            atomPublication(entry, document.url)
        }
    }

    fun openSearch(document: LibraryDocument): List<LibraryLink> {
        val root = xml(document).children().firstOrNull { it.localName().equals("OpenSearchDescription", true) }
            ?: throw RemoteLibraryException("Invalid OpenSearch description")
        return root.children().filter { it.localName() == "Url" }.mapNotNull { element ->
            val type = element.attr("type")
            val template = element.attr("template")
            if (type.substringBefore(';') !in LibraryLink.CATALOG_TYPES || template.isBlank()) return@mapNotNull null
            val pageOffset = element.attr("pageOffset").ifBlank { "1" }.toIntOrNull()
                ?.takeIf { it >= 0 } ?: return@mapNotNull null
            val indexOffset = element.attr("indexOffset").ifBlank { "1" }.toIntOrNull()
                ?.takeIf { it >= 0 } ?: return@mapNotNull null
            LibraryLink(resolveTemplate(element.base(document.url), template
                .replace("{startPage}", pageOffset.toString()).replace("{startPage?}", pageOffset.toString())
                .replace("{startIndex}", indexOffset.toString()).replace("{startIndex?}", indexOffset.toString())),
                type, setOf("search"), templated = true)
        }.take(10)
    }

    private fun atomFeed(document: Document, url: String): LibraryFeed {
        val feed = document.children().firstOrNull { it.localName() == "feed" }
            ?: throw RemoteLibraryException("The library returned neither an OPDS feed nor publication")
        val links = feed.childrenNamed("link").mapNotNull { atomLink(it, url) }
        val publications = ArrayList<LibraryPublication>()
        val navigation = ArrayList<LibraryLink>()
        for (entry in feed.childrenNamed("entry")) {
            val publication = atomPublication(entry, url)
            if (publication.links.any { it.isDirectAcquisition || it.isManifest || it.hasRel(LibraryLink.PSE_STREAM) }) {
                publications += publication
            } else {
                publication.links.filter { it.isCatalog && !it.templated }.forEach {
                    navigation += it.copy(title = it.title ?: publication.title)
                }
            }
        }
        checkItems(publications.size + navigation.size)
        val facets = links.filter { it.hasRel("http://opds-spec.org/facet") || it.hasRel("facet") }
            .groupBy { it.facetGroup ?: "Filters" }.map { (title, group) -> LibraryFacet(title, group) }
        return LibraryFeed(feed.childText("title") ?: "Library", url, links, navigation, uniquePublications(publications), facets)
    }

    private fun atomPublication(entry: Element, url: String): LibraryPublication {
        val links = entry.childrenNamed("link").mapNotNull { atomLink(it, url) }
        val base = entry.base(url)
        val identifier = entry.childText("id")?.identifier() ?: links.firstOrNull {
            it.hasRel("self") || it.hasRel("alternate")
        }?.href?.let { "href:$it" } ?: throw RemoteLibraryException("An OPDS publication has no stable identifier")
        val title = entry.childText("title")?.bounded(1000) ?: throw RemoteLibraryException("An OPDS publication has no title")
        val description = entry.children().firstOrNull { it.localName() == "content" }
            ?: entry.children().firstOrNull { it.localName() == "summary" }
        return LibraryPublication(identifier, title, base,
            authors = entry.childrenNamed("author").mapNotNullTo(LinkedHashSet()) { it.childText("name")?.bounded(500) },
            languages = entry.childrenNamed("language").mapNotNullTo(LinkedHashSet()) { it.text().trim().takeIf(String::isNotEmpty)?.bounded(100) },
            subjects = entry.childrenNamed("category").mapNotNullTo(LinkedHashSet()) {
                (it.attr("label").ifBlank { it.attr("term") }).takeIf(String::isNotBlank)?.bounded(200)
            },
            // Show descriptions as text; never inject scripts/linked HTML into the native browser.
            description = description?.let { Jsoup.parse(it.text()).text().bounded(16_384) },
            links = links,
            images = links.filter { it.rels.any { rel -> rel in setOf("http://opds-spec.org/image", "http://opds-spec.org/image/thumbnail", "cover") } },
            content = entry.children().firstOrNull { it.localName().equals("contentRating", true) }?.text()?.let(::content)
                ?: RemoteLibraryContent.UNKNOWN,
            modified = entry.childText("updated")?.bounded(100),
        )
    }

    private fun atomLink(element: Element, url: String): LibraryLink? {
        val href = element.attr("href").takeIf(String::isNotBlank) ?: return null
        return safeLink {
            LibraryLink(resolveTemplate(element.base(url), href), element.attr("type").takeIf(String::isNotBlank),
                element.attr("rel").tokens().ifEmpty { setOf("alternate") }, element.attr("title").takeIf(String::isNotBlank)?.bounded(1000),
                templated = '{' in href, pageCount = element.attributeLocal("count")?.toIntOrNull()?.takeIf { it in 1..MAX_LIBRARY_PAGES },
                width = element.attributeLocal("width")?.toIntOrNull()?.takeIf { it > 0 },
                height = element.attributeLocal("height")?.toIntOrNull()?.takeIf { it > 0 },
                size = element.attr("length").toLongOrNull()?.takeIf { it >= 0 },
                hash = element.attributeLocal("hash")?.bounded(200),
                facetGroup = element.attributeLocal("facetGroup")?.bounded(500),
                active = element.attributeLocal("activeFacet")?.equals("true", true) == true)
        }
    }

    private fun jsonFeed(root: JsonObject, url: String): LibraryFeed {
        val metadata = root.objectOrEmpty("metadata")
        val publications = root.objects("publications").map { jsonPublication(it, url) }
        val navigation = jsonLinks(root["navigation"], url)
        val groups = root.objects("groups").map { group ->
            val groupMetadata = group.objectOrEmpty("metadata")
            LibraryGroup(localized(groupMetadata["title"]) ?: "Library", jsonLinks(group["navigation"], url),
                uniquePublications(group.objects("publications").map { jsonPublication(it, url) }), jsonLinks(group["links"], url))
        }
        val facets = root.objects("facets").map { facet -> LibraryFacet(
            localized(facet.objectOrEmpty("metadata")["title"]) ?: "Filters", jsonLinks(facet["links"], url)) }
        checkItems(publications.size + navigation.size + groups.sumOf { it.navigation.size + it.publications.size } + facets.sumOf { it.links.size })
        if (listOf("navigation", "publications", "groups").none(root::containsKey)) {
            throw RemoteLibraryException("The library returned no OPDS catalog collections")
        }
        return LibraryFeed(localized(metadata["title"]) ?: "Library", url, jsonLinks(root["links"], url), navigation,
            uniquePublications(publications), facets, groups)
    }

    private fun jsonPublication(root: JsonObject, url: String): LibraryPublication {
        val metadata = root.objectOrEmpty("metadata")
        val links = jsonLinks(root["links"], url)
        val id = metadata.string("identifier")?.identifier() ?: links.firstOrNull { it.hasRel("self") }?.href?.let { "href:$it" }
            ?: throw RemoteLibraryException("An OPDS publication has no stable identifier")
        val title = localized(metadata["title"])?.bounded(1000) ?: throw RemoteLibraryException("An OPDS publication has no title")
        val readingOrder = jsonLinks(root["readingOrder"], url)
        if (readingOrder.size > MAX_LIBRARY_PAGES) throw LibraryLimitException("The publication contains too many pages")
        return LibraryPublication(id, title, url,
            authors = stringsOrNames(metadata["author"]).mapTo(LinkedHashSet()) { it.bounded(500) },
            languages = stringsOrNames(metadata["language"]).mapTo(LinkedHashSet()) { it.bounded(100) },
            subjects = stringsOrNames(metadata["subject"]).mapTo(LinkedHashSet()) { it.bounded(200) },
            description = metadata.string("description")?.let { Jsoup.parse(it).text().bounded(16_384) },
            links = links, images = jsonLinks(root["images"], url) + jsonLinks(root["resources"], url).filter { it.hasRel("cover") },
            readingOrder = readingOrder,
            content = metadata.string("contentRating")?.let(::content) ?: RemoteLibraryContent.UNKNOWN,
            modified = metadata.string("modified")?.bounded(100),
        )
    }

    private fun jsonLinks(value: JsonElement?, base: String): List<LibraryLink> = (value as? JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val href = obj.string("href") ?: return@mapNotNull null
        val properties = obj.objectOrEmpty("properties")
        safeLink {
            LibraryLink(resolveTemplate(base, href), obj.string("type"), stringsOrNames(obj["rel"]).toSet(),
                localized(obj["title"])?.bounded(1000), obj.primitive("templated")?.booleanOrNull ?: false,
                pageCount = (properties.primitive("numberOfPages") ?: properties.primitive("count"))?.intOrNull?.takeIf { it in 1..MAX_LIBRARY_PAGES },
                width = obj.primitive("width")?.intOrNull?.takeIf { it > 0 }, height = obj.primitive("height")?.intOrNull?.takeIf { it > 0 },
                size = obj.primitive("size")?.longOrNull?.takeIf { it >= 0 },
                hash = (obj.string("hash") ?: properties.string("hash"))?.bounded(200),
                active = properties.primitive("activeFacet")?.booleanOrNull == true)
        }
    }

    private fun json(bytes: ByteArray): JsonObject {
        val text = bytes.toString(Charsets.UTF_8)
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 48) throw LibraryLimitException("The library document is nested too deeply")
                '}', ']' -> if (--depth < 0) throw RemoteLibraryException("Invalid library JSON")
            }
        }
        return try { Json.parseToJsonElement(text) as? JsonObject ?: throw RemoteLibraryException("Invalid library JSON") }
        catch (error: kotlinx.serialization.SerializationException) { throw RemoteLibraryException("Invalid library JSON", error) }
    }

    private fun xml(document: LibraryDocument): Document {
        checkBody(document.bytes)
        // Jsoup never resolves external entities. Explicitly reject all declarations as well,
        // including UTF-16/32 encodings, before parsing the bounded body.
        val declarations = document.bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
        if (declarations.contains("<!DOCTYPE", true) || declarations.contains("<!ENTITY", true)) {
            throw RemoteLibraryException("XML document types and entities are not supported")
        }
        validateXml(document)
        val doc = try { Jsoup.parse(document.bytes.inputStream(), null, document.url, Parser.xmlParser()) }
        catch (error: Exception) { throw RemoteLibraryException("Invalid library XML", error) }
        var count = 0
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is DocumentType) throw RemoteLibraryException("XML document types are not supported")
                if (depth > 48 || ++count > 50_000) throw LibraryLimitException("The library XML tree exceeds safe limits")
            }
            override fun tail(node: Node, depth: Int) = Unit
        }, doc)
        return doc
    }

    private fun validateXml(document: LibraryDocument) {
        // Jsoup deliberately repairs broken markup. A resource-disabled SAX pass first enforces
        // XML well-formedness and depth while retaining Jsoup's existing OPDS metadata helpers.
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
            for (feature in listOf("http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try { factory.setFeature(feature, false) }
                catch (_: javax.xml.parsers.ParserConfigurationException) { }
                catch (_: org.xml.sax.SAXNotRecognizedException) { }
                catch (_: org.xml.sax.SAXNotSupportedException) { }
            }
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External XML resources are disabled") }
            val handler = object : DefaultHandler() {
                private var depth = 0
                private var count = 0
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    if (++depth > 48 || ++count > 50_000) throw LibraryLimitException("The library XML tree exceeds safe limits")
                }
                override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
                override fun error(error: SAXParseException) { throw error }
                override fun fatalError(error: SAXParseException) { throw error }
            }
            reader.contentHandler = handler
            reader.errorHandler = handler
            document.bytes.inputStream().use { reader.parse(InputSource(it).apply { systemId = document.url }) }
        } catch (error: RemoteLibraryException) { throw error }
        catch (error: Exception) { throw RemoteLibraryException("Invalid library XML", error) }
    }

    private fun uniquePublications(items: List<LibraryPublication>): List<LibraryPublication> {
        // The exact provider identity is authoritative; title similarity never merges editions.
        val result = LinkedHashMap<String, LibraryPublication>()
        for (item in items) {
            val previous = result[item.identifier]
            if (previous != null && previous != item) throw RemoteLibraryException("The catalog repeats a publication identifier with conflicting metadata")
            result[item.identifier] = item
        }
        return result.values.toList()
    }

    private fun isJson(document: LibraryDocument): Boolean = document.contentType?.substringBefore(';')?.lowercase()?.let {
        it == "application/json" || it.endsWith("+json")
    } == true || document.bytes.firstOrNull { it > 32 } == '{'.code.toByte()

    private fun checkBody(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_LIBRARY_BODY) throw LibraryLimitException("Invalid library document size")
    }
    private fun checkItems(count: Int) { if (count > MAX_LIBRARY_ITEMS) throw LibraryLimitException("The library feed contains too many entries") }
    private fun Element.localName() = tagName().substringAfter(':')
    private fun Element.childrenNamed(name: String) = children().filter { it.localName() == name }
    private fun Element.childText(name: String) = childrenNamed(name).firstOrNull()?.text()?.trim()?.takeIf(String::isNotEmpty)
    private fun Element.attributeLocal(name: String) = attributes().firstOrNull { it.key.substringAfter(':') == name }?.value?.takeIf(String::isNotEmpty)
    private fun Element.base(documentUrl: String): String {
        var base = documentUrl
        for (element in parents().asReversed() + this) {
            val value = element.attr("xml:base")
            if (value.isNotEmpty()) base = resolveTemplate(base, value)
        }
        return base
    }
    private fun JsonObject.objectOrEmpty(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map {
        it as? JsonObject ?: throw RemoteLibraryException("Invalid OPDS collection entry")
    }
    private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
    private fun JsonObject.string(key: String) = primitive(key)?.contentOrNull?.takeIf(String::isNotBlank)
    private fun stringsOrNames(element: JsonElement?): List<String> = when (element) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        is JsonArray -> element.flatMap(::stringsOrNames)
        is JsonObject -> (localized(element["name"]) ?: element.string("code"))?.let(::listOf).orEmpty()
    }
    private fun localized(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)
        is JsonObject -> (element["en"] ?: element["und"] ?: element.values.firstOrNull())?.let(::localized)
        else -> null
    }
    private fun content(value: String) = when (value.trim().lowercase(java.util.Locale.ROOT)) {
        "safe", "sfw", "everyone", "all", "false" -> RemoteLibraryContent.SAFE
        "adult", "explicit", "nsfw", "18+", "true" -> RemoteLibraryContent.ADULT
        "mixed", "mature", "teen" -> RemoteLibraryContent.MIXED
        else -> RemoteLibraryContent.UNKNOWN
    }
    private fun String.tokens(): Set<String> = trim().split(Regex("\\s+")).filterTo(LinkedHashSet()) { it.isNotEmpty() }
    private fun String.bounded(maximum: Int): String = take(maximum)
    private fun String.identifier(): String = also {
        if (isBlank() || length > 4096) throw LibraryLimitException("The publication identifier exceeds safe limits; it cannot be truncated")
    }
    private inline fun safeLink(block: () -> LibraryLink): LibraryLink? = try { block() } catch (_: IllegalArgumentException) { null }

    internal fun resolveTemplate(base: String, href: String): String {
        require(href.isNotBlank() && href.length <= 8192 && '\\' !in href && href.none { it < ' ' })
        val tokens = ArrayList<String>()
        val encoded = Regex("\\{[^{}]+}").replace(href) { tokens += it.value; "KAISOKUTEMPLATE${tokens.lastIndex}TOKEN" }
        require('{' !in encoded && '}' !in encoded)
        val parent = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid library base URL")
        val target = parent.resolve(encoded) ?: throw IllegalArgumentException("Unsupported library URL")
        require(target.username.isEmpty() && target.password.isEmpty() && target.fragment == null)
        var result = target.toString()
        tokens.forEachIndexed { index, value -> result = result.replace("KAISOKUTEMPLATE${index}TOKEN", value) }
        return result
    }
}
