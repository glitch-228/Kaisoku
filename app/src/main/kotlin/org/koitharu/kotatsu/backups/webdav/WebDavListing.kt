package org.koitharu.kotatsu.backups.webdav

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object WebDavListing {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_ENTRIES = 1000
    private const val DAV = "DAV:"

    /** Only a bounded, UTF-8, entity-free DAV multistatus is accepted. */
    fun parse(bytes: ByteArray, endpoint: WebDavEndpoint, expectedName: String? = null): List<WebDavResource> {
        if (bytes.size > MAX_BYTES) throw IOException("WebDAV listing is too large")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("\uFEFF")
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IOException("WebDAV listings must use UTF-8", e)
        }
        if ('\u0000' in text || text.contains("<!DOCTYPE", ignoreCase = true) ||
            text.contains("<!ENTITY", ignoreCase = true)) throw IOException("WebDAV entities are not supported")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        // Android's XML implementation does not expose every JAXP feature; rejecting declarations above and
        // the resolver below are mandatory regardless of optional parser feature support.
        for ((feature, enabled) in listOf(
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )) {
            try {
                factory.setFeature(feature, enabled)
            } catch (_: javax.xml.parsers.ParserConfigurationException) {
                // Strict input and the entity resolver remain in force on older Android parsers.
            }
        }
        val document = try {
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External WebDAV entities are forbidden") }
            builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) = throw e
                override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
            })
            builder.parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw IOException("Invalid WebDAV listing", e)
        }
        val root = document.documentElement
        if (document.doctype != null || root.namespaceURI != DAV || root.localName != "multistatus") {
            throw IOException("Invalid WebDAV multistatus")
        }
        val responses = root.children("response")
        if (responses.size > MAX_ENTRIES) throw IOException("WebDAV listing has too many entries")
        val result = linkedMapOf<String, WebDavResource>()
        for (response in responses) {
            val href = response.children("href").singleOrNull()?.textContent?.trim() ?: continue
            if (endpoint.isCollection(href)) continue
            val name = endpoint.childName(href) ?: continue
            if (expectedName != null && name != expectedName || expectedName == null && !WebDavEndpoint.isNativeBackup(name)) {
                continue
            }
            val props = response.children("propstat").filter { it.children("status").singleOrNull()
                ?.textContent?.trim()?.matches(Regex("HTTP/\\S+ 200(?: .*)?")) == true }
                .flatMap { it.children("prop") }
            if (props.any { prop -> prop.children("resourcetype").any { it.children("collection").isNotEmpty() } }) continue
            val lengths = props.flatMap { it.children("getcontentlength") }.mapNotNull { it.textContent.trim().toLongOrNull() }
            val size = lengths.singleOrNull()?.takeIf { it >= 0 } ?: continue
            val etag = props.flatMap { it.children("getetag") }.singleOrNull()?.textContent?.trim()
            val modified = props.flatMap { it.children("getlastmodified") }.singleOrNull()?.textContent?.trim()
                ?.let { value -> runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() }.getOrNull() } ?: 0L
            val item = try {
                WebDavResource(name, size, etag, modified)
            } catch (_: IllegalArgumentException) {
                throw IOException("Invalid WebDAV resource properties")
            }
            val previous = result.put(name, item)
            if (previous != null && previous != item) throw IOException("Conflicting WebDAV resource properties")
        }
        return result.values.toList()
    }

    private fun Element.children(name: String): List<Element> = buildList {
        var node = firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == DAV && node.localName == name) add(node)
            node = node.nextSibling
        }
    }
}
