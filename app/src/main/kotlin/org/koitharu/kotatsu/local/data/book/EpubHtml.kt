package org.koitharu.kotatsu.local.data.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Produce text-reader HTML; no EPUB script, stylesheet or remote resource reaches the renderer. */
internal object EpubHtml {
    private val ALLOWED = setOf(
        "p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "br", "hr", "blockquote", "pre", "code",
        "em", "strong", "b", "i", "u", "s", "sub", "sup", "ruby", "rt", "rp", "ul", "ol", "li", "dl", "dt", "dd",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "img", "a", "section", "article", "aside",
    )
    private val REMOVE = setOf(
        "script", "style", "iframe", "frame", "frameset", "object", "embed", "form", "input", "button", "textarea",
        "select", "link", "meta", "base", "audio", "video", "source", "track", "canvas", "noscript", "template",
    )

    fun sanitize(bytes: ByteArray, chapterPath: String, resources: Map<String, EpubResource>, imageUri: (String) -> String): String {
        if (bytes.size > EpubParser.MAX_CHAPTER_BYTES) throw EpubException.Limit("EPUB chapter exceeds safe reading limits")
        val document = Jsoup.parse(bytes.inputStream(), null, "")
        val body = document.body()
        for (element in body.getAllElements().toList().drop(1)) {
            val name = element.normalName().substringAfter(':')
            if (name in REMOVE) { element.remove(); continue }
            if (name == "image") {
                val src = element.attr("href").ifBlank { element.attr("xlink:href") }
                val replacement = Element("img").attr("src", src)
                element.replaceWith(replacement)
                sanitizeImage(replacement, chapterPath, resources, imageUri)
                continue
            }
            if (name !in ALLOWED) { element.unwrap(); continue }
            if (name == "img") {
                sanitizeImage(element, chapterPath, resources, imageUri)
                continue
            }
            val id = element.id().takeIf { it.isNotEmpty() && it.length <= 512 && '\u0000' !in it }
            val dir = element.attr("dir").takeIf { it == "rtl" || it == "ltr" || it == "auto" }
            val language = element.attr("lang").ifBlank { element.attr("xml:lang") }
                .takeIf { it.matches(Regex("[A-Za-z0-9-]{1,35}")) }
            val fragment = if (name == "a") {
                try {
                    EpubPaths.resolve(chapterPath, element.attr("href")).takeIf { it.path == chapterPath }?.fragment
                } catch (_: EpubException) { null }
            } else null
            element.clearAttributes()
            if (id != null) element.attr("id", id)
            if (dir != null) element.attr("dir", dir)
            if (language != null) element.attr("lang", language)
            if (fragment != null) element.attr("href", "#$fragment")
        }
        removeComments(body)
        document.outputSettings().prettyPrint(false)
        return body.html()
    }

    private fun sanitizeImage(image: Element, chapterPath: String, resources: Map<String, EpubResource>, imageUri: (String) -> String) {
        val alt = image.attr("alt").take(1024)
        val target = try { EpubPaths.resolve(chapterPath, image.attr("src")) } catch (_: EpubException) { null }
        val resource = target?.let { resources[it.path] }
        if (resource == null || !resource.mediaType.startsWith("image/") || resource.mediaType == "image/svg+xml" ||
            target.fragment != null) {
            image.replaceWith(TextNode(alt))
            return
        }
        image.clearAttributes()
        image.attr("src", imageUri(resource.path))
        if (alt.isNotEmpty()) image.attr("alt", alt)
    }

    private fun removeComments(parent: Node) {
        for (child in parent.childNodes().toList()) {
            if (child is Element || child is TextNode) removeComments(child) else child.remove()
        }
    }
}
