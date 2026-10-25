package org.koitharu.kotatsu.local.data.book

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.CodingErrorAction

internal data class EpubTarget(val path: String, val fragment: String?)

/** URI resolution without accepting network/absolute/parent-escaping package references. */
internal object EpubPaths {
    fun resolve(from: String, reference: String): EpubTarget {
        val uri = try { URI(reference.trim()) } catch (e: Exception) {
            throw EpubException.Invalid("EPUB contains an invalid resource reference", e)
        }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null) {
            throw EpubException.Invalid("EPUB resource must be a local package entry")
        }
        val decoded = percentDecode(uri.rawPath.orEmpty())
        if (decoded.startsWith('/') || '\\' in decoded || '\u0000' in decoded || ':' in decoded.substringBefore('/')) {
            throw EpubException.Invalid("EPUB contains an unsafe resource path")
        }
        val components = ArrayList<String>()
        if (decoded.isEmpty()) {
            if (from.isEmpty()) throw EpubException.Invalid("EPUB resource path is empty")
            components += from.split('/')
        } else {
            if (from.contains('/')) components += from.substringBeforeLast('/').split('/')
            for (part in decoded.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (components.isEmpty()) throw EpubException.Invalid("EPUB resource escapes its package")
                        else components.removeAt(components.lastIndex)
                    else -> components += part
                }
            }
        }
        if (components.isEmpty()) throw EpubException.Invalid("EPUB resource path is empty")
        val fragment = uri.rawFragment?.let(::percentDecode)?.takeIf { it.isNotEmpty() }
        if (fragment?.contains('\u0000') == true) throw EpubException.Invalid("EPUB contains an unsafe resource anchor")
        return EpubTarget(components.joinToString("/"), fragment)
    }

    private fun percentDecode(raw: String): String {
        val bytes = ByteArrayOutputStream()
        var cursor = 0
        while (cursor < raw.length) {
            if (raw[cursor] == '%') {
                if (cursor + 2 >= raw.length) throw EpubException.Invalid("EPUB contains invalid URI escapes")
                val value = raw.substring(cursor + 1, cursor + 3).toIntOrNull(16)
                    ?: throw EpubException.Invalid("EPUB contains invalid URI escapes")
                bytes.write(value)
                cursor += 3
            } else {
                val point = raw.codePointAt(cursor)
                bytes.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
                cursor += Character.charCount(point)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: Exception) { throw EpubException.Invalid("EPUB contains invalid UTF-8 resource escapes", e) }
    }
}
