package org.koitharu.kotatsu.scrobbling.v2

import org.json.JSONArray
import org.json.JSONObject

/** Pure mapping for the documented integrations API. HIDs and raw values never pass through numbers. */
object ComickCodec {
    const val PROVIDER = "COMICK"
    private val hidPattern = Regex("[A-Za-z0-9_-]{1,64}")

    fun requireHid(value: String): String = value.also {
        require(hidPattern.matches(it)) { "Invalid Comick public HID" }
    }

    fun title(json: JSONObject): TrackerTitle {
        val hid = requireHid(json.getString("hid"))
        require(json.optString("media_type", "manga") == "manga") { "Only manga can be tracked here" }
        val aliases = json.optJSONArray("titles")?.let { rows ->
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.stringOrNull("title") }.toSet()
        }.orEmpty()
        val externalIds = json.optJSONObject("external_ids")?.let { ids ->
            ids.keys().asSequence().mapNotNull { key -> ids.stringOrNull(key)?.let { key to it } }.toMap()
        }.orEmpty()
        return TrackerTitle(
            mediaId = hid,
            title = json.getString("title"),
            aliases = aliases,
            coverUrl = json.stringOrNull("cover_url"),
            description = json.stringOrNull("description"),
            url = json.stringOrNull("url") ?: "https://comick.dev/comic/${json.getString("slug")}",
            format = "MANGA",
            externalIds = externalIds,
        )
    }

    fun entry(json: JSONObject, owner: TrackerOwner): TrackerRemoteEntry {
        require(owner.provider == PROVIDER)
        val title = title(json)
        return TrackerRemoteEntry(
            reference = TrackerReference(owner, title.mediaId, title.mediaId),
            title = title,
            status = status(json.optInt("status", -1)),
            progress = json.optJSONObject("progress")?.let { progress ->
                TrackerRawProgress(
                    number = progress.stringOrNull("number"),
                    volume = progress.stringOrNull("volume"),
                    entryId = progress.stringOrNull("hid")?.let(::requireHid),
                    unit = progress.stringOrNull("unit") ?: "chapter",
                )
            },
            rating = json.stringOrNull("rating"),
            updatedAt = json.stringOrNull("updated_at"),
            // Latest release is deliberately not projected as a final manga chapter total.
            total = null,
        )
    }

    fun page(json: JSONObject, owner: TrackerOwner): TrackerLibraryPage {
        val rows = json.getJSONArray("data")
        val entries = (0 until rows.length()).mapNotNull { index ->
            rows.getJSONObject(index).takeIf { it.optString("media_type") == "manga" }?.let { entry(it, owner) }
        }
        return TrackerLibraryPage(entries, json.stringOrNull("next_cursor"))
    }

    fun patch(patch: TrackerPatch, allowCreate: Boolean): JSONObject {
        require(!patch.isEmpty) { "Select a tracker field to update" }
        require(patch.notes == TrackerField.Unchanged) { "Comick does not expose private notes" }
        if (allowCreate) require(patch.status is TrackerField.Set && patch.status.value != null) {
            "Adding a Comick entry requires an explicit status"
        }
        return JSONObject().apply {
            (patch.status as? TrackerField.Set)?.let { put("status", statusCode(requireNotNull(it.value))) }
            (patch.progress as? TrackerField.Set)?.let { field ->
                val progress = field.value
                if (progress == null) put("progress", JSONObject.NULL)
                else {
                    require(progress.unit == "chapter") { "Only chapter progress is supported" }
                    put("progress", JSONObject().apply {
                        if (progress.entryId != null) {
                            put("hid", requireHid(progress.entryId))
                        } else {
                            require(!progress.number.isNullOrBlank()) { "Choose an exact chapter or number" }
                            put("number", progress.number)
                            if (progress.volume != null) put("volume", progress.volume)
                        }
                    })
                }
            }
            (patch.rating as? TrackerField.Set)?.let { field ->
                val rating = field.value
                if (rating == null) put("rating", JSONObject.NULL)
                else {
                    val number = rating.toBigDecimalOrNull()?.let { runCatching { it.intValueExact() }.getOrNull() }
                    require(number != null && number in 1..10) { "Choose a whole Comick rating from 1 to 10" }
                    put("rating", number)
                }
            }
        }
    }

    fun status(code: Int): String? = when (code) {
        1 -> "CURRENT"
        2 -> "COMPLETED"
        3 -> "PAUSED"
        4 -> "DROPPED"
        5 -> "PLANNING"
        else -> null
    }

    fun statusCode(status: String): Int = when (status) {
        "CURRENT" -> 1
        "COMPLETED" -> 2
        "PAUSED" -> 3
        "DROPPED" -> 4
        "PLANNING" -> 5
        else -> throw IllegalArgumentException("Comick does not support this reading status")
    }
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else get(key).toString()

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
