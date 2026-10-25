package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Credential ownership is injected; the API cannot invent or derive an owner from a token. */
class ComickAccess(
    val connection: TrackerConnection,
    internal val accessToken: String,
) {
    override fun toString(): String = "ComickAccess(owner=${connection.owner}, revision=${connection.revision})"
}

interface ComickAccessStore {
    suspend fun current(): ComickAccess
    fun check(access: ComickAccess)
    fun checkConnection(connection: TrackerConnection)
}

/** API adapter for an already verified native connection. OAuth/session setup is a separate explicit workflow. */
class ComickProvider(
    private val wire: TrackerWire,
    private val accessStore: ComickAccessStore,
) : TrackerProvider {
    override val key = ComickCodec.PROVIDER
    override val capabilities = setOf(TrackerCapability.SEARCH, TrackerCapability.METADATA,
        TrackerCapability.LIBRARY, TrackerCapability.PROGRESS_READ, TrackerCapability.PROGRESS_WRITE,
        TrackerCapability.STATUS_WRITE, TrackerCapability.RATING_WRITE)
    private val mutations = Mutex()

    override suspend fun connection(): TrackerConnection = accessStore.current().connection

    override fun checkConnection(connection: TrackerConnection) {
        require(connection.owner.provider == key)
        accessStore.checkConnection(connection)
    }

    override suspend fun search(connection: TrackerConnection, query: String): List<TrackerTitle> {
        require(query.length in 2..100) { "Comick search requires 2–100 characters" }
        val url = "${ComickOAuth.RESOURCE}/titles".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("media_type", "manga")
            .addQueryParameter("limit", "20").build()
        val json = call(connection, Request.Builder().url(url).get())
        return json.getJSONArray("data").objects().map(ComickCodec::title)
    }

    override suspend fun metadata(connection: TrackerConnection, mediaId: String): TrackerTitle {
        val json = call(connection, Request.Builder().url(titleUrl(mediaId)).get())
        val title = ComickCodec.title(json.getJSONObject("data"))
        check(title.mediaId == mediaId) { "Comick returned a different title" }
        return title
    }

    override suspend fun libraryPage(connection: TrackerConnection, cursor: String?): TrackerLibraryPage {
        val url = "${ComickOAuth.RESOURCE}/me/library".toHttpUrl().newBuilder()
            .addQueryParameter("media_type", "manga").addQueryParameter("limit", "100")
            .apply { if (cursor != null) addQueryParameter("cursor", cursor) }.build()
        return ComickCodec.page(call(connection, Request.Builder().url(url).get()), connection.owner)
    }

    override suspend fun entry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry? {
        val response = response(connection, Request.Builder().url(entryUrl(mediaId)).get(), allowMissing = true)
        if (response.code == 404) return null
        val json = response.requireSuccess(key)
        return ComickCodec.entry(json.getJSONObject("data"), connection.owner).also {
            check(it.reference.mediaId == mediaId) { "Comick returned a different list entry" }
        }
    }

    override suspend fun patch(
        connection: TrackerConnection,
        mediaId: String,
        patch: TrackerPatch,
        allowCreate: Boolean,
    ): TrackerRemoteEntry = mutations.withLock {
        require("library:write" in connection.scopes) { "Reconnect Comick and explicitly grant library writes" }
        val before = entry(connection, mediaId)
        require(allowCreate || before != null) { "This Comick title is not on your list; confirm adding it first" }
        if (patch.progress is TrackerField.Set) checkTrackerProgressWrite(before)
        val payload = ComickCodec.patch(patch, allowCreate)
        val request = Request.Builder().url(entryUrl(mediaId))
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        if (allowCreate) request.put(body) else request.patch(body)
        val result = ComickCodec.entry(call(connection, request, write = true).getJSONObject("data"), connection.owner)
        check(result.reference.mediaId == mediaId) { "Comick returned a different list entry" }
        // The server response is authoritative; a separate readback also detects reconciliation races.
        val verified = entry(connection, mediaId)
            ?: throw IllegalStateException("Comick entry disappeared during readback")
        verifyPatch(before, verified, patch)
        verified
    }

    private suspend fun call(connection: TrackerConnection, request: Request.Builder, write: Boolean = false): JSONObject =
        response(connection, request, write).requireSuccess(key)

    private suspend fun response(
        connection: TrackerConnection,
        request: Request.Builder,
        write: Boolean = false,
        allowMissing: Boolean = false,
    ): TrackerWireResponse {
        val access = accessStore.current()
        require(access.connection.owner == connection.owner && access.connection.revision == connection.revision) {
            "Comick account changed; review this action again"
        }
        require("library:read" in access.connection.scopes)
        if (write) require("library:write" in access.connection.scopes)
        accessStore.check(access)
        val response = wire.execute(request.header("Authorization", "Bearer ${access.accessToken}")
            .header("Accept", "application/json").build())
        accessStore.check(access)
        if (!response.successful && !(allowMissing && response.code == 404)) {
            response.requireSuccess(key, listOf(access.accessToken))
        }
        return response
    }

    private fun titleUrl(hid: String) = "${ComickOAuth.RESOURCE}/titles/${ComickCodec.requireHid(hid)}"
    private fun entryUrl(hid: String) = "${ComickOAuth.RESOURCE}/me/library/${ComickCodec.requireHid(hid)}"

    private fun verifyPatch(before: TrackerRemoteEntry?, after: TrackerRemoteEntry, patch: TrackerPatch) {
        fun <T> expected(field: TrackerField<T>, previous: T?): T? = when (field) {
            TrackerField.Unchanged -> previous
            is TrackerField.Set -> field.value
        }
        check(after.status == expected(patch.status, before?.status)) { "Comick status changed during verification" }
        if (patch.rating is TrackerField.Set) {
            check(after.rating?.toBigDecimalOrNull()?.stripTrailingZeros() ==
                patch.rating.value?.toBigDecimalOrNull()?.stripTrailingZeros()) { "Comick rating was not verified" }
        } else if (before != null) {
            check(after.rating == before.rating) { "Comick rating changed during verification" }
        }
        if (patch.progress is TrackerField.Set) {
            val progress = patch.progress.value
            check(when {
                progress == null -> after.progress == null
                progress.entryId != null -> after.progress?.entryId == progress.entryId
                else -> after.progress?.number == progress.number &&
                    (progress.volume == null || after.progress?.volume == progress.volume)
            }) { "Comick progress was not verified" }
        } else if (before != null) {
            check(after.progress == before.progress) { "Comick progress changed during verification" }
        }
    }
}
