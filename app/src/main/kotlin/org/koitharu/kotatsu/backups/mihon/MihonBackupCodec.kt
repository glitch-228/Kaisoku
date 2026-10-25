package org.koitharu.kotatsu.backups.mihon

import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import org.koitharu.kotatsu.core.exceptions.BadBackupFormatException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Size and record limits apply before any library mutation or unbounded protobuf allocation. */
internal object MihonBackupCodec {
    const val MAX_COMPRESSED_BYTES = 16 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 24 * 1024 * 1024
    const val MAX_TITLES = 10_000
    const val MAX_CHAPTERS = 100_000
    private const val MAX_TRACKERS = 20_000
    private const val MAX_CATEGORIES = 1_000
    private const val MAX_SOURCES = 2_000

    data class Decoded(val backup: MihonBackup, val digest: String)

    fun decode(input: InputStream): Decoded {
        val compressed = input.bounded(MAX_COMPRESSED_BYTES)
        val payload = if (compressed.size >= 2 && compressed[0] == 0x1f.toByte() && compressed[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { it.bounded(MAX_PAYLOAD_BYTES) }
        } else compressed.also { require(it.size <= MAX_PAYLOAD_BYTES) { "Mihon backup is too large" } }
        try {
            preflight(payload)
            val backup = ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), payload)
            validate(backup)
            return Decoded(backup, sha256(payload))
        } catch (error: SerializationException) {
            throw BadBackupFormatException(error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid Mihon backup: ${error.message}", error)
        }
    }

    internal fun validate(backup: MihonBackup) {
        require(backup.manga.size <= MAX_TITLES && backup.categories.size <= MAX_CATEGORIES &&
            backup.sources.size <= MAX_SOURCES) { "Backup record limit exceeded" }
        require(backup.manga.sumOf { it.chapters.size.toLong() } <= MAX_CHAPTERS) { "Too many chapters" }
        require(backup.manga.sumOf { it.tracking.size.toLong() } <= MAX_TRACKERS) { "Too many tracker entries" }
        require(backup.manga.sumOf { it.history.size.toLong() } <= MAX_CHAPTERS &&
            backup.manga.map { it.source }.toSet().size <= MAX_SOURCES) { "Too much history or too many source bindings" }
        require(backup.manga.map { it.source to it.url }.toSet().size == backup.manga.size) {
            "Duplicate source editions require a separate reviewed merge"
        }
        require(backup.categories.map { it.order }.toSet().size == backup.categories.size) {
            "Ambiguous category references require a separate reviewed merge"
        }
        backup.sources.forEach { require(it.name.length <= 4_096) { "Invalid source name" } }
        backup.categories.forEach { require(it.name.length <= 2_048) { "Invalid category name" } }
        backup.manga.forEach { manga ->
            require(manga.url.isNotBlank() && manga.url.length <= 16_384 && manga.title.length <= 16_384) {
                "A title is missing its source URL or has oversized metadata"
            }
            require(manga.genre.size <= 256 && manga.categories.size <= MAX_CATEGORIES && manga.history.size <= MAX_CHAPTERS) {
                "Title metadata limit exceeded"
            }
            require(manga.genre.all { it.length <= 4_096 } && (manga.author?.length ?: 0) <= 16_384 &&
                (manga.artist?.length ?: 0) <= 16_384 && (manga.thumbnailUrl?.length ?: 0) <= 16_384) {
                "Oversized title metadata"
            }
            require(manga.chapters.map { it.url }.toSet().size == manga.chapters.size) {
                "Duplicate chapter URLs require a separate reviewed merge"
            }
            manga.chapters.forEach { chapter ->
                require(chapter.url.isNotBlank() && chapter.url.length <= 16_384 && chapter.name.length <= 16_384 &&
                    (chapter.scanlator?.length ?: 0) <= 16_384) { "Invalid chapter identity" }
                require(chapter.chapterNumber.isFinite()) { "Invalid chapter number" }
                checkedPage(chapter.lastPageRead)
            }
            manga.history.forEach { require(it.url.length <= 16_384) { "Invalid history URL" } }
            manga.tracking.forEach {
                require(it.lastChapterRead.isFinite() && it.lastChapterRead >= 0 && it.score.isFinite()) {
                    "Invalid raw tracker progress"
                }
                require(it.trackingUrl.length <= 16_384 && it.title.length <= 16_384) { "Oversized tracker metadata" }
            }
        }
    }

    internal fun checkedPage(value: Long): Int {
        require(value in 0..Int.MAX_VALUE.toLong()) { "Page position is outside the supported range" }
        return value.toInt()
    }

    private fun preflight(bytes: ByteArray) {
        var titles = 0
        var chapters = 0
        var trackers = 0
        var categories = 0
        var sources = 0
        var history = 0
        wireFields(bytes, 0, bytes.size) { field, start, end, wire ->
            when (field) {
                1 -> {
                    require(wire == 2) { "Invalid title message" }
                    require(++titles <= MAX_TITLES) { "Too many titles" }
                    var genres = 0
                    var memberships = 0
                    wireFields(bytes, start, end) { child, childStart, childEnd, childWire ->
                        when (child) {
                            2, 3, 4, 5, 9 -> require(childWire == 2 && childEnd - childStart <= 16_384) { "Oversized title metadata" }
                            7 -> require(++genres <= 256 && childWire == 2 && childEnd - childStart <= 4_096) { "Too many or oversized genres" }
                            16 -> {
                                require(++chapters <= MAX_CHAPTERS && childWire == 2) { "Too many or invalid chapters" }
                                wireFields(bytes, childStart, childEnd) { part, a, b, type ->
                                    if (part in 1..3) require(type == 2 && b - a <= 16_384) { "Oversized chapter identity" }
                                }
                            }
                            18 -> {
                                require(++trackers <= MAX_TRACKERS && childWire == 2) { "Too many or invalid tracker entries" }
                                wireFields(bytes, childStart, childEnd) { part, a, b, type ->
                                    if (part in 4..5) require(type == 2 && b - a <= 16_384) { "Oversized tracker metadata" }
                                }
                            }
                            104 -> {
                                require(++history <= MAX_CHAPTERS && childWire == 2) { "Too many or invalid history entries" }
                                wireFields(bytes, childStart, childEnd) { part, a, b, type ->
                                    if (part == 1) require(type == 2 && b - a <= 16_384) { "Oversized history identity" }
                                }
                            }
                            17 -> if (childWire == 0) {
                                require(++memberships <= MAX_CATEGORIES) { "Too many title categories" }
                            } else {
                                require(childWire == 2) { "Invalid title categories" }
                                val packed = WireCursor(bytes, childStart, childEnd)
                                while (packed.position < childEnd) {
                                    packed.varint()
                                    require(++memberships <= MAX_CATEGORIES) { "Too many title categories" }
                                }
                            }
                        }
                    }
                }
                2, 101 -> {
                    require(wire == 2) { "Invalid category or source message" }
                    if (field == 2) require(++categories <= MAX_CATEGORIES) { "Too many categories" }
                    else require(++sources <= MAX_SOURCES) { "Too many sources" }
                    wireFields(bytes, start, end) { part, a, b, type ->
                        if (part == 1) require(type == 2 && b - a <= 4_096) { "Oversized category or source name" }
                    }
                }
            }
        }
    }

    /** Groups are not part of the backup contract; scalar ranges allow limits on unpacked lists too. */
    private fun wireFields(bytes: ByteArray, start: Int, end: Int, field: (Int, Int, Int, Int) -> Unit) {
        val cursor = WireCursor(bytes, start, end)
        while (cursor.position < end) {
            val tag = cursor.varint()
            require(tag > 0 && tag ushr 3 <= 536_870_911) { "Invalid protobuf field" }
            val type = (tag and 7).toInt()
            val startValue = cursor.position
            var content = startValue
            when (type) {
                0 -> cursor.varint()
                1 -> cursor.skip(8)
                2 -> {
                    val length = cursor.varint()
                    require(length in 0..(end - cursor.position).toLong()) { "Invalid protobuf length" }
                    content = cursor.position
                    cursor.skip(length.toInt())
                }
                5 -> cursor.skip(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire field")
            }
            field((tag ushr 3).toInt(), content, cursor.position, type)
        }
    }

    private class WireCursor(val bytes: ByteArray, var position: Int, val end: Int) {
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(position < end) { "Truncated protobuf field" }
                val value = bytes[position++].toInt() and 255
                require(shift != 63 || value <= 1) { "Protobuf integer overflow" }
                result = result or ((value and 127).toLong() shl shift)
                if (value and 128 == 0) return result
            }
            throw IllegalArgumentException("Invalid protobuf integer")
        }

        fun skip(count: Int) {
            require(count >= 0 && count <= end - position) { "Truncated protobuf field" }
            position += count
        }
    }

    private fun InputStream.bounded(limit: Int): ByteArray {
        val buffer = ByteArray(16 * 1024)
        return ByteArrayOutputStream(minOf(64 * 1024, limit)).use { output ->
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Backup import cancelled")
                val read = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (read < 0) break
                if (read == 0) continue
                if (read > limit - output.size()) throw IOException("Mihon backup exceeds its safe size limit")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
