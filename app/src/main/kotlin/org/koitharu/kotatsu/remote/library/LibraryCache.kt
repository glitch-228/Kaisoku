package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Derived, account-scoped documents only. Neither credentials nor library/progress records live here. */
class LibraryCache(private val root: File, private val maximumBytes: Long = 16L * 1024 * 1024) {
    private val mutex = Mutex()

    suspend fun document(scope: String, url: String): LibraryDocument? = mutex.withLock {
        withContext(Dispatchers.IO) { read(file(scope, "d", url)) }
    }

    suspend fun publication(scope: String, id: String): LibraryPublication? = mutex.withLock {
        withContext(Dispatchers.IO) {
            read(file(scope, "p", id))?.let { document ->
                try { OpdsParser.publication(document).takeIf { it.identifier == id } } catch (_: IOException) { null }
            }
        }
    }

    suspend fun save(scope: String, document: LibraryDocument, feed: LibraryFeed? = null,
                     publication: LibraryPublication? = null, cacheUrl: String = document.url) = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureDirectory()
            write(file(scope, "d", cacheUrl), document)
            val publications = if (feed == null) listOfNotNull(publication) else feed.publications + feed.groups.flatMap { it.publications }
            for (item in publications.take(MAX_LIBRARY_ITEMS)) {
                write(file(scope, "p", item.identifier), LibraryDocument(item.documentUrl,
                    "application/opds-publication+json", LibraryPublicationCodec.encode(item)))
            }
            trim()
        }
    }

    private fun file(scope: String, kind: String, key: String): File {
        require(Regex("[a-f0-9]{64}").matches(scope))
        return File(root, "$kind-${libraryDigest("$scope\u0000$key")}.bin")
    }

    private fun read(file: File): LibraryDocument? {
        if (!file.isFile || file.length() !in 12..(MAX_LIBRARY_BODY + 32_768).toLong()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                val url = input.readUTF()
                val type = input.readUTF().takeIf(String::isNotEmpty)
                val length = input.readInt()
                if (length !in 1..MAX_LIBRARY_BODY) return null
                val body = ByteArray(length)
                input.readFully(body)
                if (input.read() != -1) return null
                file.setLastModified(System.currentTimeMillis())
                LibraryDocument(url, type, body)
            }
        } catch (_: IOException) { null }
    }

    private fun write(target: File, document: LibraryDocument) {
        if (document.bytes.isEmpty() || document.bytes.size > MAX_LIBRARY_BODY || document.url.length > 8192) return
        val stage = File(root, ".cache-${UUID.randomUUID()}")
        try {
            DataOutputStream(stage.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeUTF(document.url)
                output.writeUTF(document.contentType.orEmpty().take(500))
                output.writeInt(document.bytes.size)
                output.write(document.bytes)
            }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }

    private fun ensureDirectory() {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Library cache is unavailable")
    }

    private fun trim() {
        val files = root.listFiles().orEmpty().filter { Regex("[dp]-[a-f0-9]{64}\\.bin").matches(it.name) }
            .sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        var publications = files.count { it.name.startsWith("p-") }
        var documents = files.size - publications
        for (file in files) {
            if (bytes <= maximumBytes && publications <= MAX_LIBRARY_ITEMS && documents <= 64) break
            val size = file.length()
            if (file.delete()) {
                bytes -= size
                if (file.name.startsWith("p-")) publications-- else documents--
            }
        }
    }

    private companion object { const val MAGIC = 0x4b4c4231 }
}

internal object LibraryPublicationCodec {
    fun encode(publication: LibraryPublication): ByteArray = buildJsonObject {
        put("metadata", buildJsonObject {
            put("identifier", publication.identifier)
            put("title", publication.title)
            put("author", strings(publication.authors))
            put("language", strings(publication.languages))
            put("subject", strings(publication.subjects))
            publication.description?.let { put("description", it) }
            publication.modified?.let { put("modified", it) }
            put("contentRating", publication.content.name)
        })
        put("links", JsonArray(publication.links.map(::link)))
        put("images", JsonArray(publication.images.map(::link)))
        put("readingOrder", JsonArray(publication.readingOrder.map(::link)))
    }.toString().toByteArray(Charsets.UTF_8)

    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun link(link: LibraryLink): JsonObject = buildJsonObject {
        put("href", link.href)
        link.type?.let { put("type", it) }
        put("rel", strings(link.rels))
        link.title?.let { put("title", it) }
        put("templated", link.templated)
        link.width?.let { put("width", it) }
        link.height?.let { put("height", it) }
        link.size?.let { put("size", it) }
        link.hash?.let { put("hash", it) }
        put("properties", buildJsonObject {
            link.pageCount?.let { put("count", it) }
            put("activeFacet", link.active)
        })
    }
}
