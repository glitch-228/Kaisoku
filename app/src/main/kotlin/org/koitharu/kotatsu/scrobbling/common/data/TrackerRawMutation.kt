package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.LegacyStatusPatch
import org.koitharu.kotatsu.scrobbling.v2.TrackerField
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerPatch
import org.koitharu.kotatsu.scrobbling.v2.TrackerRawProgress
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import org.koitharu.kotatsu.scrobbling.v2.TrackerTitle
import org.koitharu.kotatsu.scrobbling.v2.checkTrackerProgressWrite
import org.koitharu.kotatsu.scrobbling.v2.privateTrackerClient
import java.io.IOException
import java.math.BigDecimal

internal fun interface TrackerRawMutationWire {
    suspend fun request(request: Request): JsonElement
}

/** No numeric cache, local link/history write, worker scheduling, retry or implicit account mutation. */
internal object TrackerRawMutation {
    suspend fun execute(
        repository: ScrobblerRepository,
        service: ScrobblerService,
        baseClient: OkHttpClient,
        expected: TrackerReference,
        patch: TrackerPatch,
        allowCreate: Boolean,
    ): TrackerRemoteEntry = repository.storage.mutationMutex.withLock {
        val session = repository.accountSession()
        val client = privateTrackerClient(baseClient)
        repository.storage.withSession(session) {
            withTrackerRequestGuard({ repository.storage.checkSession(session) }) {
                val owner = repository.readRawOwner()
                require(owner == expected.owner && owner.provider == service.name) { "Tracker account changed" }
                val wire = TrackerRawMutationWire { original ->
                    currentCoroutineContext().ensureActive()
                    repository.storage.checkSession(session)
                    val request = original.newBuilder().header("Authorization", "Bearer ${session.token}")
                        .header("Accept", if (service == ScrobblerService.KITSU) JSON_API else "application/json")
                        .header("Cache-Control", "no-store").header("Pragma", "no-cache")
                        .header("User-Agent", "Kaisoku").build()
                    client.trackerCall(request, repository.storage).rawTrackerElement().also {
                        repository.storage.checkSession(session)
                        currentCoroutineContext().ensureActive()
                    }
                }
                mutate(service, owner, expected, patch, allowCreate, wire).also { repository.storage.checkSession(session) }
            }
        }
    }

    internal data class Snapshot(val entry: TrackerRemoteEntry?, val chapters: BigDecimal?, val volumes: BigDecimal?)

    /** The wire seam is for deterministic protocol fixtures; production always uses execute's private TLS transport. */
    internal suspend fun mutate(service: ScrobblerService, owner: TrackerOwner, expected: TrackerReference,
        patch: TrackerPatch, allowCreate: Boolean, wire: TrackerRawMutationWire): TrackerRemoteEntry {
        require(owner == expected.owner && owner.provider == service.name) { "Tracker account changed" }
        numericId(expected.mediaId)
        validatePatch(service, patch)
        val before = read(service, owner, expected.mediaId, wire)
        val current = before.entry
        if (current != null) {
            require(current.reference.owner == owner && current.reference.mediaId == expected.mediaId)
            if (expected.listEntryId == null) {
                require(patch.isEmpty || allowCreate && planningOnly(service, patch)) {
                    "Confirm the current tracker entry before changing it"
                }
                return current // Absence raced with a different client: adopt; never reset that client's status.
            }
            require(current.reference == expected) { "Tracker entry changed; review its identity again" }
            if (patch.isEmpty || alreadyMatches(current, patch)) return current
            requireSafeCallbacks(service, before, patch)
            if (patch.progress is TrackerField.Set) checkTrackerProgressWrite(current)
        } else {
            require(expected.listEntryId == null) { "The confirmed tracker entry was removed; review it again" }
            if (!allowCreate) throw TrackerEntryCreationRequired()
            require(patch.isEmpty || planningOnly(service, patch)) { "New entries can only be added to Planning" }
        }
        currentCoroutineContext().ensureActive()
        val creating = current == null
        val operation = if (creating) TrackerPatch.statusOnly(planning(service)) else patch
        val request = request(service, expected, operation, creating)
        val response = wire.request(request)
        val writtenIdentity = acknowledge(service, owner, expected.mediaId, expected.listEntryId, creating, response)
        currentCoroutineContext().ensureActive()
        val after = read(service, owner, expected.mediaId, wire).entry
            ?: throw IOException("Tracker mutation needs readback: the entry is not visible yet")
        require(after.reference.owner == owner && after.reference.mediaId == expected.mediaId &&
            (writtenIdentity == null || after.reference.listEntryId == writtenIdentity) &&
            (creating || after.reference == expected)) { "Tracker mutation needs readback: entry identity changed" }
        if (creating) {
            check(after.status == planning(service)) { "Tracker creation needs review: provider returned another status" }
        } else verifyReadback(requireNotNull(current), after, patch)
        return after
    }

    internal fun planning(service: ScrobblerService): String = when (service) {
        ScrobblerService.ANILIST -> "PLANNING"
        ScrobblerService.MAL, ScrobblerService.MANGABAKA -> "plan_to_read"
        ScrobblerService.KITSU, ScrobblerService.SHIKIMORI -> "planned"
    }

    private fun planningOnly(service: ScrobblerService, patch: TrackerPatch): Boolean =
        patch == TrackerPatch.statusOnly(planning(service))

    private fun validatePatch(service: ScrobblerService, patch: TrackerPatch) {
        require(patch.rating == TrackerField.Unchanged && patch.notes == TrackerField.Unchanged) {
            "Raw tracker mutations support only status and progress"
        }
        (patch.status as? TrackerField.Set)?.let {
            require(it.value != null && it.value in LegacyStatusPatch.statuses(service)) { "Unsupported tracker status" }
        }
        (patch.progress as? TrackerField.Set)?.let { selected ->
            val value = requireNotNull(selected.value) { "Clearing tracker progress needs a separate review" }
            require(value.unit == "chapter" && value.entryId == null && (value.number != null || value.volume != null)) {
                "Unsupported tracker progress unit or reference"
            }
            if (service == ScrobblerService.KITSU) require(value.volume == null) { "Kitsu does not expose volume reading progress" }
            listOfNotNull(value.number, value.volume).forEach { scalar(service, it) }
        }
    }

    internal fun request(service: ScrobblerService, reference: TrackerReference, patch: TrackerPatch, creating: Boolean): Request {
        validatePatch(service, patch)
        require(!creating || reference.listEntryId == null && planningOnly(service, patch)) { "Creation requires Planning only" }
        val media = numericId(reference.mediaId)
        val fields = linkedMapOf<String, JsonPrimitive>()
        (patch.status as? TrackerField.Set)?.value?.let { status ->
            LegacyStatusPatch.fields(service, status).forEach { (key, value) -> fields[key] = when (value) {
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            } }
        }
        (patch.progress as? TrackerField.Set)?.value?.let { progress ->
            progress.number?.let { fields[when (service) {
                ScrobblerService.MAL -> "num_chapters_read"
                ScrobblerService.SHIKIMORI -> "chapters"
                ScrobblerService.MANGABAKA -> "progress_chapter"
                else -> "progress"
            }] = JsonPrimitive(scalar(service, it)) }
            progress.volume?.let { fields[when (service) {
                ScrobblerService.ANILIST -> "progressVolumes"
                ScrobblerService.MAL -> "num_volumes_read"
                ScrobblerService.SHIKIMORI -> "volumes"
                ScrobblerService.MANGABAKA -> "progress_volume"
                ScrobblerService.KITSU -> error("No Kitsu volume progress")
            }] = JsonPrimitive(scalar(service, it)) }
        }
        val builder = Request.Builder()
        val body: JsonObject
        when (service) {
            ScrobblerService.ANILIST -> {
                require(media <= Int.MAX_VALUE.toLong()) { "AniList media identity exceeds GraphQL Int" }
                val args = linkedMapOf<String, JsonElement>("mediaId" to JsonPrimitive(media))
                if (!creating) args["id"] = JsonPrimitive(numericId(requireNotNull(reference.listEntryId)).also {
                    require(it <= Int.MAX_VALUE.toLong()) { "AniList list identity exceeds GraphQL Int" }
                })
                args.putAll(fields)
                val query = "mutation { SaveMediaListEntry(${args.entries.joinToString(",") { (key, value) ->
                    "$key: ${if (key == "status") (value as JsonPrimitive).content else value.toString()}"
                }}) { id userId mediaId } }"
                body = buildJsonObject { put("query", query) }
                return builder.url("https://graphql.anilist.co").post(body.toBody()).build()
            }
            ScrobblerService.MAL -> {
                if (!creating) require(reference.listEntryId == reference.mediaId) { "MAL entry identity changed" }
                val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value.content) } }.build()
                return builder.url("https://api.myanimelist.net/v2/manga/$media/my_list_status").put(form).build()
            }
            ScrobblerService.KITSU -> {
                val data = buildJsonObject {
                    put("type", "libraryEntries")
                    if (!creating) put("id", requireNotNull(reference.listEntryId))
                    put("attributes", JsonObject(fields))
                    if (creating) put("relationships", buildJsonObject {
                        put("user", relationship("users", reference.owner.accountId))
                        put("manga", relationship("manga", reference.mediaId))
                    })
                }
                body = buildJsonObject { put("data", data) }
                val url = "https://kitsu.app/api/edge/library-entries".toHttpUrl().newBuilder().apply {
                    if (!creating) addPathSegment(requireNotNull(reference.listEntryId))
                    addQueryParameter("include", "manga,user")
                }.build()
                return builder.url(url).method(if (creating) "POST" else "PATCH", body.toBody(JSON_API)).build()
            }
            ScrobblerService.SHIKIMORI -> {
                val row = if (creating) buildJsonObject {
                    put("user_id", numericId(reference.owner.accountId)); put("target_id", media); put("target_type", "Manga")
                    // Database default is planned. Omitting status makes a duplicate POST an empty safe update.
                } else JsonObject(fields)
                body = buildJsonObject { put("user_rate", row) }
                val url = "https://shikimori.io/api/v2/user_rates".toHttpUrl().newBuilder().apply {
                    if (!creating) addPathSegment(numericId(requireNotNull(reference.listEntryId)).toString())
                }.build()
                return builder.url(url).method(if (creating) "POST" else "PATCH", body.toBody()).build()
            }
            ScrobblerService.MANGABAKA -> {
                body = JsonObject(fields)
                return builder.url("https://api.mangabaka.org/v1/my/library/$media")
                    .method(if (creating) "POST" else "PATCH", body.toBody()).build()
            }
        }
    }

    private fun relationship(type: String, id: String) = buildJsonObject {
        put("data", buildJsonObject { put("type", type); put("id", id) })
    }

    private fun JsonObject.toBody(type: String = "application/json") = toString().toRequestBody(type.toMediaType())

    private fun numericId(value: String): Long = value.toLongOrNull()?.takeIf { it > 0 && it.toString() == value }
        ?: throw IllegalArgumentException("Tracker work identity cannot use this numeric provider endpoint")

    private fun scalar(service: ScrobblerService, value: String): BigDecimal {
        require(value.length <= 128 && NUMBER.matches(value)) { "Invalid raw tracker progress" }
        val number = value.toBigDecimalOrNull() ?: error("Invalid tracker progress")
        require(number.precision() <= 32 && number.scale() in -18..18 && number.signum() >= 0) { "Tracker progress exceeds safe precision" }
        if (service == ScrobblerService.MANGABAKA) {
            require(number.signum() > 0 && number <= BigDecimal("10000")) {
                "MangaBaka stores zero as unknown; use a representable positive progress value"
            }
        } else {
            require(runCatching { number.toBigIntegerExact() }.isSuccess && number <=
                BigDecimal(if (service == ScrobblerService.SHIKIMORI) "9999" else Int.MAX_VALUE.toString())) {
                "This tracker requires bounded whole-number progress; no rounding was applied"
            }
        }
        return number
    }

    private fun sameNumber(a: String?, b: String?): Boolean = if (a == null || b == null) a == b else
        TrackerRawProgress(a).numericNumber()?.let { x -> TrackerRawProgress(b).numericNumber()?.let { y -> x.compareTo(y) == 0 } } ?: (a == b)

    private fun alreadyMatches(entry: TrackerRemoteEntry, patch: TrackerPatch): Boolean {
        val status = (patch.status as? TrackerField.Set)?.value
        val progress = (patch.progress as? TrackerField.Set)?.value
        return (status == null || status == entry.status) &&
            (progress?.number == null || sameNumber(progress.number, entry.progress?.number)) &&
            (progress?.volume == null || sameNumber(progress.volume, entry.progress?.volume))
    }

    private fun verifyReadback(before: TrackerRemoteEntry, after: TrackerRemoteEntry, patch: TrackerPatch) {
        val status = (patch.status as? TrackerField.Set)?.value ?: before.status
        val change = (patch.progress as? TrackerField.Set)?.value
        check(after.status == status && sameNumber(after.progress?.number, change?.number ?: before.progress?.number) &&
            sameNumber(after.progress?.volume, change?.volume ?: before.progress?.volume) &&
            sameNumber(after.rating, before.rating) && after.notes == before.notes) {
            "Tracker readback needs review: the provider changed an unselected field or did not apply the requested value"
        }
    }

    private suspend fun read(service: ScrobblerService, owner: TrackerOwner, mediaId: String,
        wire: TrackerRawMutationWire): Snapshot {
        val id = numericId(mediaId)
        var chapters: BigDecimal? = null
        var volumes: BigDecimal? = null
        val row: JsonObject?
        val title: TrackerTitle
        when (service) {
            ScrobblerService.ANILIST -> {
                require(id <= Int.MAX_VALUE.toLong() && numericId(owner.accountId) <= Int.MAX_VALUE.toLong()) {
                    "AniList identity exceeds GraphQL Int"
                }
                val query = "query { MediaList(mediaId: $id, userId: ${owner.accountId}, type: MANGA) { " +
                    "id userId mediaId status progress progressVolumes score(format: POINT_100) notes updatedAt " +
                    "media { id type chapters volumes title { userPreferred romaji english native } coverImage { large } siteUrl format description } } }"
                val root = wire.request(Request.Builder().url("https://graphql.anilist.co")
                    .post(buildJsonObject { put("query", query) }.toBody()).build()).objectValue()
                val errors = root["errors"] as? JsonArray
                if (!errors.isNullOrEmpty()) {
                    if (errors.all { (it as? JsonObject)?.raw("status") == "404" }) return Snapshot(null, null, null)
                    throw IOException("AniList rejected the fresh entry query")
                }
                row = root.obj("data")["MediaList"] as? JsonObject ?: return Snapshot(null, null, null)
                require(row.raw("userId") == owner.accountId && row.raw("mediaId") == mediaId) { "Tracker returned another account or work" }
                val media = row.obj("media")
                require(media.raw("id") == mediaId && media.raw("type") == "MANGA") { "Tracker returned another adaptation" }
                val names = media.obj("title")
                title = TrackerTitle(mediaId, names.raw("userPreferred") ?: names.raw("romaji") ?: "#$id",
                    coverUrl = media["coverImage"].objectOrNull()?.raw("large"), description = media.raw("description"),
                    url = "https://anilist.co/manga/$id", format = media.raw("format"))
                chapters = metadataNumber(media, "chapters"); volumes = metadataNumber(media, "volumes")
            }
            ScrobblerService.MAL -> {
                val url = "https://api.myanimelist.net/v2/manga/$id".toHttpUrl().newBuilder()
                    .addQueryParameter("fields", "my_list_status{status,score,num_chapters_read,num_volumes_read,comments,updated_at,is_rereading},synopsis,num_chapters,num_volumes,media_type").build()
                val media = wire.request(Request.Builder().url(url).get().build()).objectValue()
                require(media.raw("id") == mediaId) { "Tracker returned another work" }
                title = TrackerTitle(mediaId, media.raw("title") ?: "#$id",
                    coverUrl = media["main_picture"].objectOrNull()?.raw("large"), description = media.raw("synopsis"),
                    url = "https://myanimelist.net/manga/$id", format = media.raw("media_type"))
                row = media["my_list_status"] as? JsonObject
                chapters = metadataNumber(media, "num_chapters"); volumes = metadataNumber(media, "num_volumes")
            }
            ScrobblerService.KITSU -> {
                val media = wire.request(Request.Builder().url("https://kitsu.app/api/edge/manga/$id").get().build())
                    .objectValue().obj("data")
                require(media.raw("id") == mediaId && media.raw("type") == "manga") { "Tracker returned another work" }
                val attrs = media.obj("attributes")
                require(attrs.containsKey("chapterCount")) { "Kitsu progress limits are unavailable" }
                chapters = metadataNumber(attrs, "chapterCount")
                title = TrackerTitle(mediaId, attrs.raw("canonicalTitle") ?: "#$id",
                    coverUrl = attrs["posterImage"].objectOrNull()?.raw("medium"), description = attrs.raw("description"),
                    url = "https://kitsu.app/manga/$id", format = attrs.raw("subtype"))
                val url = "https://kitsu.app/api/edge/library-entries".toHttpUrl().newBuilder()
                    .addQueryParameter("filter[user_id]", owner.accountId).addQueryParameter("filter[manga_id]", mediaId)
                    .addQueryParameter("include", "manga,user").addQueryParameter("page[limit]", "2").build()
                val result = wire.request(Request.Builder().url(url).get().build()).objectValue()
                require((result["errors"] as? JsonArray).isNullOrEmpty()) { "Kitsu rejected the fresh entry query" }
                val rows = result.array("data")
                require(rows.size <= 1) { "Several tracker entries match; review the account" }
                row = rows.singleOrNull()?.also {
                    require(it.raw("type") == "libraryEntries")
                    val relations = it.obj("relationships")
                    require(relations.obj("user").obj("data").raw("id") == owner.accountId &&
                        relations.obj("manga").obj("data").raw("id") == mediaId) { "Tracker returned another account or work" }
                }
            }
            ScrobblerService.SHIKIMORI -> {
                val media = wire.request(Request.Builder().url("https://shikimori.io/api/mangas/$id").get().build()).objectValue()
                require(media.raw("id") == mediaId && media.containsKey("chapters") && media.containsKey("volumes")) {
                    "Shikimori work identity or progress limits are unavailable"
                }
                chapters = metadataNumber(media, "chapters"); volumes = metadataNumber(media, "volumes")
                require(chapters != null && volumes != null) { "Shikimori progress limits are unavailable" }
                title = TrackerTitle(mediaId, media.raw("name") ?: "#$id", description = media.raw("description"),
                    url = "https://shikimori.io/mangas/$id", format = media.raw("kind"))
                val url = "https://shikimori.io/api/v2/user_rates".toHttpUrl().newBuilder()
                    .addQueryParameter("user_id", owner.accountId).addQueryParameter("target_id", mediaId)
                    .addQueryParameter("target_type", "Manga").build()
                val rows = wire.request(Request.Builder().url(url).get().build()) as? JsonArray
                    ?: error("Invalid Shikimori entry response")
                require(rows.size <= 1) { "Several tracker entries match; review the account" }
                row = rows.singleOrNull()?.objectValue()?.also {
                    require(it.raw("user_id") == owner.accountId && it.raw("target_id") == mediaId &&
                        it.raw("target_type") == "Manga") { "Tracker returned another account or work" }
                }
            }
            ScrobblerService.MANGABAKA -> {
                val media = wire.request(Request.Builder().url("https://api.mangabaka.org/v1/series/$id").get().build())
                    .objectValue().obj("data")
                require(media.raw("id") == mediaId) { "Tracker returned another work" }
                title = TrackerTitle(mediaId, media.raw("title") ?: media.raw("romanized_title") ?: media.raw("native_title") ?: "#$id",
                    description = media.raw("description"), url = "https://mangabaka.org/$id", format = media.raw("type"))
                val result = try { wire.request(Request.Builder().url("https://api.mangabaka.org/v1/my/library/$id").get().build()) }
                    catch (error: TrackerHttpException) { if (error.code == 404) return Snapshot(null, null, null) else throw error }
                row = result.objectValue().obj("data")
                require(row.raw("user_id") == owner.accountId && row.raw("series_id") == mediaId) {
                    "Tracker returned another account or work"
                }
                require(!row.raw("id").isNullOrBlank()) { "MangaBaka did not return a lossless entry identity" }
                chapters = metadataNumber(media, "total_chapters")
            }
        }
        val entry = row?.let { TrackerRawRead.entry(service, owner.accountId, mediaId, title, it)
            .copy(total = chapters?.toPlainString()) }
        return Snapshot(entry, chapters, volumes)
    }

    private fun acknowledge(service: ScrobblerService, owner: TrackerOwner, media: String, entry: String?, creating: Boolean,
        value: JsonElement): String? {
        val root = value.objectValue()
        val row = when (service) {
            ScrobblerService.ANILIST -> {
                require((root["errors"] as? JsonArray).isNullOrEmpty()) { "AniList mutation needs readback: GraphQL rejected it" }
                root.obj("data").obj("SaveMediaListEntry").also {
                    require(it.raw("userId") == owner.accountId && it.raw("mediaId") == media) { "Tracker returned another account or work" }
                }
            }
            ScrobblerService.MAL -> return media
            ScrobblerService.KITSU -> root.obj("data").also { require(it.raw("type") == "libraryEntries") }
            ScrobblerService.SHIKIMORI -> root.also {
                require(it.raw("user_id") == owner.accountId && it.raw("target_id") == media && it.raw("target_type") == "Manga") {
                    "Tracker returned another account or work"
                }
            }
            ScrobblerService.MANGABAKA -> {
                require(root.raw("data") == "true") { "MangaBaka mutation needs readback: no acknowledgement" }
                return if (creating) null else entry
            }
        }
        val identity = requireNotNull(row.raw("id")) { "Tracker mutation did not return an entry identity" }
        require(identity.isNotBlank() && identity.length <= 512 && (creating || identity == entry)) {
            "Tracker mutation returned another entry"
        }
        return identity
    }

    private fun metadataNumber(row: JsonObject, key: String): BigDecimal? = row.raw(key)?.let { value ->
        require(value.length <= 128 && NUMBER.matches(value)) { "Invalid tracker progress limit" }
        value.toBigDecimal().also { require(it.signum() >= 0 && it.precision() <= 32 && it.scale() in -18..18) }
    }

    /** Provider callbacks can change fields absent from the request. Refuse known destructive cases before HTTP. */
    private fun requireSafeCallbacks(service: ScrobblerService, snapshot: Snapshot, patch: TrackerPatch) {
        val before = requireNotNull(snapshot.entry)
        val status = (patch.status as? TrackerField.Set)?.value ?: before.status
        val progress = (patch.progress as? TrackerField.Set)?.value
        val oldNumber = before.progress?.numericNumber()
        val oldVolume = before.progress?.volume?.let { TrackerRawProgress(it).numericNumber() }
        val number = progress?.numericNumber() ?: oldNumber
        val volume = progress?.volume?.let { TrackerRawProgress(it).numericNumber() } ?: oldVolume
        val changedNumber = !sameNumber(number?.toPlainString(), oldNumber?.toPlainString())
        val changedVolume = !sameNumber(volume?.toPlainString(), oldVolume?.toPlainString())
        if (service == ScrobblerService.KITSU) {
            fun base(value: String?) = if (value == "reconsuming") "current" else value
            val statusChanged = base(status) != base(before.status)
            val cap = snapshot.chapters
            if (statusChanged && base(status) == "completed" && cap != null) {
                require(number != null && number.compareTo(cap) == 0) { "Kitsu Completed would change unselected chapter progress" }
            }
            if (changedNumber && !statusChanged) {
                val resulting = if (cap != null && number != null && number.compareTo(cap) == 0) "completed" else "current"
                require(base(status) == resulting) { "Kitsu progress would change an unselected reading status" }
            }
            if (changedNumber && cap != null && cap.signum() > 0) require(number != null && number <= cap) {
                "Kitsu progress exceeds the confirmed work length"
            }
        } else if (service == ScrobblerService.SHIKIMORI) {
            require(oldNumber != null && oldVolume != null && number != null && volume != null) {
                "Shikimori counters are unknown; use the provider's editor"
            }
            val cap = requireNotNull(snapshot.chapters)
            val volumeCap = requireNotNull(snapshot.volumes)
            val statusChanged = status != before.status
            if (statusChanged && status == "completed") {
                require((cap.signum() == 0 || number.compareTo(cap) == 0) &&
                    (volumeCap.signum() == 0 || volume.compareTo(volumeCap) == 0)) {
                    "Shikimori Completed would change unselected chapter or volume progress"
                }
            }
            if (statusChanged && status == "rewatching") require(number.signum() == 0 && volume.signum() == 0) {
                "Shikimori rereading would reset unselected chapter or volume progress"
            }
            if (changedNumber || changedVolume) {
                require((cap.signum() == 0 || number <= cap) && (volumeCap.signum() == 0 || volume <= volumeCap)) {
                    "Shikimori would clamp the requested progress"
                }
                require((!changedNumber || number.signum() != 0 || volume.signum() == 0) &&
                    (!changedVolume || volume.signum() != 0 || number.signum() == 0)) {
                    "Shikimori resetting one counter would reset the other"
                }
                if (!statusChanged) {
                    val atEnd = changedNumber && cap.signum() > 0 && number.compareTo(cap) == 0 ||
                        changedVolume && volumeCap.signum() > 0 && volume.compareTo(volumeCap) == 0
                    require(!atEnd || status == "completed" && number.compareTo(cap) == 0 && volume.compareTo(volumeCap) == 0) {
                        "Shikimori completing one counter would change an unselected status or counter"
                    }
                    val toZero = changedNumber && number.signum() == 0 || changedVolume && volume.signum() == 0
                    require(!toZero || status in setOf("planned", "rewatching")) { "Shikimori zero progress would change reading status" }
                    val started = changedNumber && oldNumber.signum() == 0 && number.signum() > 0 ||
                        changedVolume && oldVolume.signum() == 0 && volume.signum() > 0
                    require(!started || status in setOf("watching", "rewatching", "completed", "dropped")) {
                        "Shikimori starting progress would change reading status"
                    }
                }
            }
        }
    }

    private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: error("Invalid tracker object")
    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private const val JSON_API = "application/vnd.api+json"
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
