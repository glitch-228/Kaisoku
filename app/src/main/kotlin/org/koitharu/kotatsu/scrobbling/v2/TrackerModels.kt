package org.koitharu.kotatsu.scrobbling.v2

import java.math.BigDecimal

/** Public provider identifiers, never a numeric hash of an external account or work. */
data class TrackerOwner(val provider: String, val accountId: String) {
    init {
        require(PROVIDER.matches(provider)) { "Invalid tracker provider" }
        require(accountId.isNotBlank() && accountId.length <= 512) { "Invalid tracker account" }
    }

    private companion object {
        val PROVIDER = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

data class TrackerReference(
    val owner: TrackerOwner,
    val mediaId: String,
    val listEntryId: String? = null,
) {
    init {
        require(mediaId.isNotBlank() && mediaId.length <= 512) { "Invalid tracker media identity" }
        require(listEntryId == null || listEntryId.isNotBlank() && listEntryId.length <= 512) {
            "Invalid tracker entry identity"
        }
    }
}

/** A null progress object is unknown; a present number "0" is an explicit value. */
data class TrackerRawProgress(
    val number: String?,
    val volume: String? = null,
    val entryId: String? = null,
    val unit: String = "chapter",
) {
    init {
        require(listOf(number, volume, entryId).all { it == null || it.length <= 512 })
        require(unit.isNotBlank() && unit.length <= 32)
    }

    /** An optional comparison projection. The original string is always retained. */
    fun numericNumber(): BigDecimal? = number?.takeIf { it.length <= 128 }?.toBigDecimalOrNull()
        ?.takeIf { it.precision() <= 38 && it.scale() in -12..12 }

    fun wholeChapterOrNull(): Int? = if (unit != "chapter") null else numericNumber()?.let { value ->
        runCatching { value.intValueExact() }.getOrNull()?.takeIf { it >= 0 }
    }
}

data class TrackerConnection(
    val owner: TrackerOwner,
    val revision: Long,
    val scopes: Set<String> = emptySet(),
    val nickname: String? = null,
)

enum class TrackerCapability {
    SEARCH, METADATA, LIBRARY, PROGRESS_READ, PROGRESS_WRITE, STATUS_WRITE, RATING_WRITE, NOTES_WRITE,
}

data class TrackerTitle(
    val mediaId: String,
    val title: String,
    val aliases: Set<String> = emptySet(),
    val coverUrl: String? = null,
    val description: String? = null,
    val url: String,
    val format: String? = null,
    val externalIds: Map<String, String> = emptyMap(),
)

data class TrackerRemoteEntry(
    val reference: TrackerReference,
    val title: TrackerTitle,
    val status: String?,
    val progress: TrackerRawProgress?,
    val rating: String? = null,
    val notes: String? = null,
    val updatedAt: String? = null,
    val total: String? = null,
)

data class TrackerLibraryPage(val entries: List<TrackerRemoteEntry>, val nextCursor: String?)

/** Explicit presence prevents a status change from clearing progress, rating or notes. */
sealed interface TrackerField<out T> {
    data object Unchanged : TrackerField<Nothing>
    data class Set<T>(val value: T?) : TrackerField<T>
}

data class TrackerPatch(
    val status: TrackerField<String> = TrackerField.Unchanged,
    val progress: TrackerField<TrackerRawProgress> = TrackerField.Unchanged,
    val rating: TrackerField<String> = TrackerField.Unchanged,
    val notes: TrackerField<String> = TrackerField.Unchanged,
) {
    val isEmpty: Boolean
        get() = listOf(status, progress, rating, notes).all { it == TrackerField.Unchanged }

    companion object {
        fun statusOnly(status: String) = TrackerPatch(status = TrackerField.Set(status))
    }
}

interface TrackerProvider {
    val key: String
    val capabilities: Set<TrackerCapability>
    suspend fun connection(): TrackerConnection
    fun checkConnection(connection: TrackerConnection)
    suspend fun search(connection: TrackerConnection, query: String): List<TrackerTitle>
    suspend fun metadata(connection: TrackerConnection, mediaId: String): TrackerTitle
    suspend fun libraryPage(connection: TrackerConnection, cursor: String?): TrackerLibraryPage
    suspend fun entry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry?
    suspend fun patch(
        connection: TrackerConnection,
        mediaId: String,
        patch: TrackerPatch,
        allowCreate: Boolean = false,
    ): TrackerRemoteEntry
}

/** Numeric compatibility is explicit and checked; UUID/HID/decimal identifiers never enter old APIs. */
object LegacyTrackerBridge {
    fun mediaId(id: String): Long = id.toLongOrNull()
        ?.takeIf { it > 0 && it.toString() == id }
        ?: throw IllegalArgumentException("Tracker identity cannot use the numeric legacy API")

    fun listEntryId(id: String): Int = id.toIntOrNull()
        ?.takeIf { it > 0 && it.toString() == id }
        ?: throw IllegalArgumentException("Tracker entry cannot use the numeric legacy API")

    fun wholeChapter(progress: TrackerRawProgress): Int = progress.wholeChapterOrNull()
        ?: throw IllegalArgumentException("Tracker progress cannot use the whole-chapter legacy API")
}
