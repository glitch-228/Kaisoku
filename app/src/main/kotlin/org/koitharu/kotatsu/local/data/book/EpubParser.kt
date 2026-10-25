package org.koitharu.kotatsu.local.data.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Package/spine metadata parser only; chapters and illustrations are read on demand. */
object EpubParser {
    private const val MAX_METADATA_BYTES = 2L * 1024 * 1024
    private const val MAX_TOTAL_CHAPTER_BYTES = 100L * 1024 * 1024
    const val MAX_CHAPTER_BYTES = 4L * 1024 * 1024
    private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/svg+xml")
    private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    fun parse(archive: BookArchive): EpubBook {
        val entries = archive.entries
        if (entries.size > 20_000 || entries.any { it.size < 0 }) throw EpubException.Limit("EPUB package entry limits exceeded")
        if (entries.map { it.name }.toSet().size != entries.size) throw EpubException.Invalid("EPUB has duplicate package paths")
        val files = entries.filterNot { it.isDirectory }.associateBy { it.name }
        val mime = read(archive, files, "mimetype", 128)
        try {
            if (mime.toString(Charsets.US_ASCII) != "application/epub+zip") throw EpubException.Invalid("File is not an EPUB package")
        } finally { mime.fill(0) }
        rejectEncryption(archive, files)
        val container = xml(archive, files, "META-INF/container.xml")
        val rootfiles = container.elements("rootfile").filter {
            it.attr("media-type") == "application/oebps-package+xml"
        }
        if (rootfiles.size != 1) throw EpubException.Unsupported("EPUB must have one supported reading package")
        val packagePath = EpubPaths.resolve("", rootfiles.single().attr("full-path")).path
        val document = xml(archive, files, packagePath)
        val pkg = document.elements("package").singleOrNull() ?: throw EpubException.Invalid("EPUB package document is invalid")
        val version = pkg.attr("version")
        if (!version.startsWith("2.") && !version.startsWith("3.")) throw EpubException.Unsupported("Only EPUB 2 and 3 are supported")
        val metadata = pkg.children().singleOrNull { it.localName() == "metadata" }
            ?: throw EpubException.Invalid("EPUB metadata is missing")
        val layout = metadata.elements("meta").filter { it.attr("property") == "rendition:layout" }
        if (layout.any { it.text().trim().equals("pre-paginated", true) }) {
            throw EpubException.Unsupported("Fixed-layout EPUB is not supported by the text reader")
        }
        if (metadata.elements("meta").any { it.attr("name").equals("fixed-layout", true) &&
                it.attr("content").equals("true", true) }) {
            throw EpubException.Unsupported("Fixed-layout EPUB is not supported by the text reader")
        }
        val manifest = pkg.children().singleOrNull { it.localName() == "manifest" }
            ?: throw EpubException.Invalid("EPUB manifest is missing")
        val resources = LinkedHashMap<String, EpubResource>()
        val paths = HashSet<String>()
        for (item in manifest.children().filter { it.localName() == "item" }) {
            val id = item.attr("id")
            if (id.isEmpty() || id in resources) throw EpubException.Invalid("EPUB manifest identifiers are missing or duplicated")
            val target = EpubPaths.resolve(packagePath, item.attr("href"))
            if (target.fragment != null || !paths.add(target.path)) throw EpubException.Invalid("EPUB manifest paths are ambiguous")
            if (target.path !in files) throw EpubException.Invalid("EPUB manifest resource is missing")
            resources[id] = EpubResource(id, target.path, item.attr("media-type").lowercase(), item.attr("properties").tokens())
        }
        val spine = pkg.children().singleOrNull { it.localName() == "spine" }
            ?: throw EpubException.Invalid("EPUB reading spine is missing")
        val primary = ArrayList<EpubResource>()
        for (item in spine.children().filter { it.localName() == "itemref" && it.attr("linear") != "no" }) {
            if ("rendition:layout-pre-paginated" in item.attr("properties").tokens()) {
                throw EpubException.Unsupported("Fixed-layout EPUB spine content is not supported")
            }
            val resource = resources[item.attr("idref")] ?: throw EpubException.Invalid("EPUB spine references an unknown resource")
            if (resource.mediaType != "application/xhtml+xml" && resource.mediaType != "text/html") {
                throw EpubException.Unsupported("EPUB reading spine contains unsupported content")
            }
            if (primary.any { it.path == resource.path }) throw EpubException.Invalid("EPUB repeats a primary reading resource")
            primary += resource
        }
        if (primary.isEmpty()) throw EpubException.Invalid("EPUB has no readable chapters")
        if (primary.any { files.getValue(it.path).size > MAX_CHAPTER_BYTES } ||
            primary.sumOf { files.getValue(it.path).size } > MAX_TOTAL_CHAPTER_BYTES) {
            throw EpubException.Limit("EPUB chapter text exceeds safe reading limits")
        }
        val navResource = resources.values.singleOrNull { "nav" in it.properties }
        val ncxResource = resources[spine.attr("toc")] ?: resources.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val navigation = when {
            navResource != null -> navigation(archive, files, navResource, primary.mapTo(HashSet()) { it.path }, true)
            ncxResource != null -> navigation(archive, files, ncxResource, primary.mapTo(HashSet()) { it.path }, false)
            else -> emptyList()
        }
        val title = metadata.elements("title").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw EpubException.Invalid("EPUB title is missing")
        val coverId = metadata.elements("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val cover = resources.values.firstOrNull { "cover-image" in it.properties } ?: resources[coverId]
        return EpubBook(
            title = title,
            authors = metadata.elements("creator").mapNotNullTo(LinkedHashSet()) { it.text().trim().takeIf(String::isNotEmpty) },
            language = metadata.elements("language").firstOrNull()?.text()?.trim()?.takeIf(String::isNotEmpty),
            identifier = metadata.elements("identifier").firstOrNull { it.id() == pkg.attr("unique-identifier") }?.text()
                ?: metadata.elements("identifier").firstOrNull()?.text(),
            description = metadata.elements("description").firstOrNull()?.text(),
            packagePath = packagePath,
            coverPath = cover?.takeIf { it.mediaType in IMAGE_TYPES && it.mediaType != "image/svg+xml" }?.path,
            resources = resources.values.associateBy { it.path },
            chapters = primary.mapIndexed { index, resource ->
                EpubChapter(resource.path, navigation.firstOrNull { it.chapterPath == resource.path }?.title
                    ?: "${index + 1}", resource.id)
            },
            navigation = navigation,
        )
    }

    private fun rejectEncryption(archive: BookArchive, files: Map<String, BookArchiveEntry>) {
        if ("META-INF/encryption.xml" !in files) return
        val document = xml(archive, files, "META-INF/encryption.xml")
        if (document.elements("EncryptedData").any { encrypted ->
                val method = encrypted.elements("EncryptionMethod").singleOrNull()?.attr("Algorithm")
                val reference = encrypted.elements("CipherReference").singleOrNull()?.attr("URI")
                val target = try { reference?.let { EpubPaths.resolve("", it).path } } catch (_: EpubException) { null }
                method !in FONT_OBFUSCATION || target !in files ||
                    target?.substringAfterLast('.')?.lowercase() !in setOf("otf", "ttf", "woff", "woff2")
            }) throw EpubException.Unsupported("DRM-protected EPUB content is not supported")
    }

    private fun navigation(archive: BookArchive, files: Map<String, BookArchiveEntry>, resource: EpubResource,
                           primary: Set<String>, epub3: Boolean): List<EpubNavigationEntry> {
        val doc = xml(archive, files, resource.path)
        val results = ArrayList<EpubNavigationEntry>()
        val items = if (epub3) {
            val nav = doc.elements("nav").firstOrNull { "toc" in it.attr("epub:type").tokens() || "doc-toc" in it.attr("role").tokens() }
            nav?.elements("a").orEmpty()
        } else doc.elements("navPoint")
        for (item in items) {
            val href = if (epub3) item.attr("href") else item.elements("content").firstOrNull()?.attr("src").orEmpty()
            val title = if (epub3) item.text().trim() else item.elements("navLabel").firstOrNull()?.text()?.trim().orEmpty()
            if (href.isEmpty() || title.isEmpty()) continue
            val target = try { EpubPaths.resolve(resource.path, href) } catch (_: EpubException) { continue }
            if (target.path in primary) results += EpubNavigationEntry(title, target.path, target.fragment)
        }
        return results.distinct()
    }

    private fun read(archive: BookArchive, files: Map<String, BookArchiveEntry>, path: String, limit: Long): ByteArray {
        val entry = files[path] ?: throw EpubException.Invalid("Required EPUB package entry is missing")
        if (entry.size > limit) throw EpubException.Limit("EPUB metadata exceeds safe limits")
        val bytes = archive.read(path, limit)
        if (bytes.size > limit || bytes.size.toLong() != entry.size) {
            bytes.fill(0)
            throw EpubException.Invalid("EPUB package entry size does not match its header")
        }
        return bytes
    }

    private fun xml(archive: BookArchive, files: Map<String, BookArchiveEntry>, path: String): Document {
        val bytes = read(archive, files, path, MAX_METADATA_BYTES)
        return try {
            // Jsoup's XML parser does not resolve external entities. Reject declarations as well,
            // including UTF-16/32 declarations after removing zero bytes from the ASCII prefix.
            val declarations = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
            if (declarations.contains("<!ENTITY", true) || Regex("<!DOCTYPE[^>]*\\[", RegexOption.IGNORE_CASE).containsMatchIn(declarations)) {
                throw EpubException.Invalid("EPUB XML internal subsets and entities are not supported")
            }
            Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
        } finally { bytes.fill(0) }
    }

    private fun Element.localName() = tagName().substringAfter(':')
    private fun Element.elements(name: String): List<Element> = getAllElements().filter { it.localName() == name }
    private fun String.tokens(): Set<String> = trim().split(Regex("\\s+")).filterTo(HashSet()) { it.isNotEmpty() }
}
