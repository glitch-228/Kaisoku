package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Response
import org.koitharu.kotatsu.core.util.BoundedJson
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerRawProgress
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import org.koitharu.kotatsu.scrobbling.v2.TrackerTitle
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Runs after provider pacing, including each request in a read/patch/readback sequence. */
internal class TrackerRequestGuard(val check: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TrackerRequestGuard>
}

internal suspend fun <T> withTrackerRequestGuard(check: suspend () -> Unit, block: suspend () -> T): T {
    val outer = currentCoroutineContext()[TrackerRequestGuard]?.check
    return withContext(TrackerRequestGuard { outer?.invoke(); check() }) { block() }
}

internal suspend fun checkTrackerRequestGuard() { currentCoroutineContext()[TrackerRequestGuard]?.check?.invoke() }

/** Read scalar lexemes before any Int/Float legacy projection, retaining fractional and large values. */
internal fun Response.rawTrackerJson(): JsonObject = rawTrackerElement() as? JsonObject
    ?: throw IOException("Invalid tracker response")

internal fun Response.rawTrackerElement(): kotlinx.serialization.json.JsonElement = use { response ->
    val body = response.body
    if (body.contentLength() > 8 * 1024 * 1024) throw IOException("Tracker response is too large")
    val source = body.source()
    if (source.request(8 * 1024 * 1024L + 1)) throw IOException("Tracker response is too large")
    // The shared validator also rejects duplicate keys, invalid surrogates and excessive nesting.
    // A small wrapper permits the array-root Shikimori response without weakening object transfers.
    val bytes = source.readByteArray()
    val prefix = "{\"value\":".toByteArray(Charsets.UTF_8)
    val wrapped = ByteArray(bytes.size + prefix.size + 1)
    prefix.copyInto(wrapped)
    bytes.copyInto(wrapped, prefix.size)
    wrapped[wrapped.lastIndex] = '}'.code.toByte()
    try {
        val text = BoundedJson.validateObject(wrapped, 8 * 1024 * 1024 + prefix.size + 1)
        (Json.parseToJsonElement(text) as JsonObject).getValue("value")
    } catch (error: IllegalArgumentException) {
        throw IOException("Invalid tracker JSON response", error)
    }
}

internal fun JsonObject.raw(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.also {
    require(it.length <= 512 * 1024) { "Tracker field is too large" }
}
internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: error("Missing tracker field: $key")
internal fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map {
    it as? JsonObject ?: error("Invalid tracker list entry")
} ?: error("Missing tracker array: $key")

/** Provider-specific status and progress decoding; no database, cache, tracker echo or remote mutation. */
internal object TrackerRawRead {
    fun entry(service: ScrobblerService, owner: String, mediaId: String, title: TrackerTitle,
        row: JsonObject): TrackerRemoteEntry {
        require(title.mediaId == mediaId)
        val fields = if (service == ScrobblerService.KITSU) row.obj("attributes") else row
        val id = when (service) {
            ScrobblerService.MAL -> mediaId
            ScrobblerService.MANGABAKA -> row.raw("id") ?: mediaId
            else -> requireNotNull(row.raw("id"))
        }
        val status = when (service) {
            ScrobblerService.MAL -> if (fields.raw("is_rereading") == "true") "rereading" else fields.raw("status")
            ScrobblerService.KITSU -> if (fields.raw("reconsuming") == "true") "reconsuming" else fields.raw("status")
            ScrobblerService.MANGABAKA -> fields.raw("state")
            else -> fields.raw("status")
        }
        val number = fields.raw(when (service) {
            ScrobblerService.MAL -> "num_chapters_read"
            ScrobblerService.SHIKIMORI -> "chapters"
            ScrobblerService.MANGABAKA -> "progress_chapter"
            else -> "progress"
        })
        // Kitsu's volumesOwned is a collection count, not reading progress.
        val volume = if (service == ScrobblerService.KITSU) null else fields.raw(when (service) {
            ScrobblerService.ANILIST -> "progressVolumes"
            ScrobblerService.MAL -> "num_volumes_read"
            ScrobblerService.KITSU -> error("No volume progress field")
            ScrobblerService.SHIKIMORI -> "volumes"
            ScrobblerService.MANGABAKA -> "progress_volume"
        })
        val rating = fields.raw(when (service) {
            ScrobblerService.KITSU -> "ratingTwenty"
            ScrobblerService.MANGABAKA -> "rating"
            else -> "score"
        })
        val notes = fields.raw(when (service) {
            ScrobblerService.MAL -> "comments"
            ScrobblerService.SHIKIMORI -> "text"
            ScrobblerService.MANGABAKA -> "note"
            else -> "notes"
        })
        return TrackerRemoteEntry(TrackerReference(TrackerOwner(service.name, owner), mediaId, id), title, status,
            if (number == null && volume == null) null else TrackerRawProgress(number, volume), rating, notes,
            fields.raw(if (service in setOf(ScrobblerService.ANILIST, ScrobblerService.KITSU)) "updatedAt" else "updated_at"))
    }
}
