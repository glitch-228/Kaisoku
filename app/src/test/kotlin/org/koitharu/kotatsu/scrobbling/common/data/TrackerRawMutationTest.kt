package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.v2.*
import java.io.IOException

class TrackerRawMutationTest {
    private fun owner(service: ScrobblerService) = TrackerOwner(service.name, if (service == ScrobblerService.MANGABAKA) "opaque-account" else "42")
    private fun reference(service: ScrobblerService, entry: String? = "9007199254740993") =
        TrackerReference(owner(service), "481", if (service == ScrobblerService.MAL) entry?.let { "481" } else entry)
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
    private fun body(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    private fun rejected(block: () -> Unit) = assertNotNull(runCatching(block).exceptionOrNull())
    private fun paused(service: ScrobblerService) = when (service) {
        ScrobblerService.ANILIST -> "PAUSED"
        ScrobblerService.MANGABAKA -> "paused"
        else -> "on_hold"
    }

    @Test fun eachProviderUsesItsActualCreateContractWithoutProgressRatingOrNotes() {
        for (service in ScrobblerService.entries) {
            val request = TrackerRawMutation.request(service, reference(service, null),
                TrackerPatch.statusOnly(TrackerRawMutation.planning(service)), true)
            assertEquals("https", request.url.scheme)
            assertEquals(if (service == ScrobblerService.MAL) "PUT" else "POST", request.method)
            val data = body(request)
            listOf("progress", "num_chapters_read", "chapters", "rating", "score", "notes", "comments").forEach {
                assertFalse("$service unexpectedly sent $it", data.contains(it))
            }
            if (service == ScrobblerService.SHIKIMORI) {
                val fields = (json(data) as JsonObject).obj("user_rate")
                assertEquals(setOf("user_id", "target_id", "target_type"), fields.keys)
                assertEquals("Manga", fields.raw("target_type"))
            } else if (service == ScrobblerService.MANGABAKA) {
                assertEquals("/v1/my/library/481", request.url.encodedPath)
                assertEquals("plan_to_read", (json(data) as JsonObject).raw("state"))
            }
        }
    }

    @Test fun selectedStatusAndProgressBodiesOmitEveryUnrelatedField() {
        for (service in ScrobblerService.entries) {
            val ref = reference(service, if (service == ScrobblerService.ANILIST) "123" else "9007199254740993")
            val request = TrackerRawMutation.request(service, ref, TrackerPatch.statusOnly(paused(service)), false)
            val data = body(request)
            listOf("progress", "chapters", "volumes", "rating", "score", "notes", "comments", "date").forEach {
                assertFalse("$service sent unrelated $it", data.contains(it))
            }
            val progress = TrackerRawMutation.request(service, ref,
                TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("13"))), false)
            val value = body(progress)
            assertFalse(value.contains("status")); assertFalse(value.contains("state"))
            assertFalse(value.contains("volume")); assertFalse(value.contains("score")); assertFalse(value.contains("note"))
            if (service == ScrobblerService.MAL) {
                val form = progress.body as FormBody
                assertEquals(1, form.size); assertEquals("num_chapters_read", form.name(0)); assertEquals("13", form.value(0))
            }
        }
    }

    @Test fun opaqueKitsuEntryPathsAndJsonIdentityNeverNarrowOrBecomePathSyntax() {
        val ref = reference(ScrobblerService.KITSU, "entry/a?b#c%9223372036854775808")
        val request = TrackerRawMutation.request(ScrobblerService.KITSU, ref, TrackerPatch.statusOnly("on_hold"), false)
        assertEquals("entry/a?b#c%9223372036854775808", request.url.pathSegments.last())
        assertTrue(request.url.encodedPath.contains("%2F")); assertTrue(request.url.encodedPath.contains("%3F"))
        assertEquals(ref.listEntryId, (json(body(request)) as JsonObject).obj("data").raw("id"))
    }

    @Test fun numericProviderBoundsAreCheckedWithoutRoundingAndMangaBakaDecimalsStayExact() {
        val patch = TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("12.500000000000000001", "2.250000000000000001")))
        val request = TrackerRawMutation.request(ScrobblerService.MANGABAKA, reference(ScrobblerService.MANGABAKA), patch, false)
        assertEquals("PATCH", request.method); assertEquals("/v1/my/library/481", request.url.encodedPath)
        val fields = json(body(request)) as JsonObject
        assertEquals("12.500000000000000001", fields.raw("progress_chapter"))
        assertEquals("2.250000000000000001", fields.raw("progress_volume"))
        for (service in ScrobblerService.entries.filter { it != ScrobblerService.MANGABAKA }) {
            rejected { TrackerRawMutation.request(service, reference(service), patch, false) }
        }
        for (number in listOf("0", "0.0000", "10000.000000000000001", "-1", "1e99999999", "NaN")) {
            rejected { TrackerRawMutation.request(ScrobblerService.MANGABAKA, reference(ScrobblerService.MANGABAKA),
                TrackerPatch(progress = TrackerField.Set(TrackerRawProgress(number))), false) }
        }
        rejected { TrackerRawMutation.request(ScrobblerService.ANILIST, reference(ScrobblerService.ANILIST), TrackerPatch.statusOnly("PAUSED"), false) }
    }

    @Test fun unsupportedClearsUnitsFieldsAndCreationPayloadsFailBeforeAnyRead() = runBlocking {
        val service = ScrobblerService.MAL
        val invalid = listOf(TrackerPatch(notes = TrackerField.Set("change")), TrackerPatch(rating = TrackerField.Set("8.5")),
            TrackerPatch(status = TrackerField.Set(null)), TrackerPatch(progress = TrackerField.Set(null)),
            TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("2", unit = "page"))),
            TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("2", entryId = "opaque-chapter"))))
        for (patch in invalid) {
            var calls = 0
            val error = runCatching { TrackerRawMutation.mutate(service, owner(service), reference(service), patch, false,
                TrackerRawMutationWire { calls++; error("No HTTP expected") }) }.exceptionOrNull()
            assertNotNull(error); assertEquals(0, calls)
        }
        rejected { TrackerRawMutation.request(service, reference(service, null), TrackerPatch.statusOnly("reading"), true) }
    }

    @Test fun existingAbsenceRaceIsAdoptedWithoutResettingStatusOrOtherFields() = runBlocking {
        for (service in ScrobblerService.entries) {
            val fixture = Wire(service)
            val adopted = TrackerRawMutation.mutate(service, owner(service), reference(service, null),
                TrackerPatch.statusOnly(TrackerRawMutation.planning(service)), true, fixture)
            assertEquals(fixture.initialStatus, adopted.status)
            assertEquals("12.50", adopted.progress!!.number)
            assertEquals("Keep notes", adopted.notes)
            assertEquals(0, fixture.mutations)
        }
    }

    @Test fun absenceRequiresSeparateConsentAndExistingReferencesCannotBeRecreated() = runBlocking {
        val fixture = Wire(ScrobblerService.MAL).apply { present = false }
        val noConsent = runCatching { TrackerRawMutation.mutate(ScrobblerService.MAL, owner(ScrobblerService.MAL),
            reference(ScrobblerService.MAL, null), TrackerPatch(), false, fixture) }.exceptionOrNull()
        assertTrue(noConsent is TrackerEntryCreationRequired)
        assertNotNull(runCatching { TrackerRawMutation.mutate(ScrobblerService.MAL, owner(ScrobblerService.MAL),
            reference(ScrobblerService.MAL), TrackerPatch(), true, fixture) }.exceptionOrNull())
        assertEquals(0, fixture.mutations)
    }

    @Test fun statusOnlyAndProgressOnlyReadbacksRetainOpaqueIdentityRatingNotesAndOmittedVolume() = runBlocking {
        val fixture = Wire(ScrobblerService.MANGABAKA)
        val entry = TrackerRawMutation.mutate(ScrobblerService.MANGABAKA, owner(ScrobblerService.MANGABAKA), reference(ScrobblerService.MANGABAKA),
            TrackerPatch(progress = TrackerField.Set(TrackerRawProgress("13.500000000000000001"))), false, fixture)
        assertEquals("9007199254740993", entry.reference.listEntryId)
        assertEquals("13.500000000000000001", entry.progress!!.number)
        assertEquals("2.50", entry.progress!!.volume); assertEquals("8.2500", entry.rating); assertEquals("Keep notes", entry.notes)
        assertEquals(1, fixture.mutations)
        val again = TrackerRawMutation.mutate(ScrobblerService.MANGABAKA, owner(ScrobblerService.MANGABAKA), reference(ScrobblerService.MANGABAKA),
            TrackerPatch.statusOnly("paused"), false, fixture)
        assertEquals("paused", again.status); assertEquals(entry.progress, again.progress)
        assertEquals(entry.rating, again.rating); assertEquals(entry.notes, again.notes)
    }

    @Test fun identityMismatchAndKnownProviderCallbackSideEffectsStopBeforeMutation() = runBlocking {
        val wrong = Wire(ScrobblerService.MANGABAKA).apply { account = "another-account" }
        assertNotNull(runCatching { TrackerRawMutation.mutate(ScrobblerService.MANGABAKA, owner(ScrobblerService.MANGABAKA),
            reference(ScrobblerService.MANGABAKA), TrackerPatch.statusOnly("paused"), false, wrong) }.exceptionOrNull())
        assertEquals(0, wrong.mutations)
        for ((service, status) in listOf(ScrobblerService.KITSU to "completed", ScrobblerService.SHIKIMORI to "completed",
            ScrobblerService.SHIKIMORI to "rewatching")) {
            val fixture = Wire(service)
            assertNotNull(runCatching { TrackerRawMutation.mutate(service, owner(service), reference(service),
                TrackerPatch.statusOnly(status), false, fixture) }.exceptionOrNull())
            assertEquals(0, fixture.mutations)
        }
    }

    @Test fun failuresAndCancellationNeverRetryAMutatingRequestOrReturnAPartialSuccess() = runBlocking {
        for (failure in listOf(IOException("unknown response"), CancellationException("fixture cancel"),
            TrackerHttpException(401, 0, "unauthorized"), TrackerHttpException(403, 0, "forbidden"), TrackerHttpException(429, 90_000, "limited"))) {
            val fixture = Wire(ScrobblerService.MAL).apply { mutationFailure = failure }
            assertSame(failure, runCatching { TrackerRawMutation.mutate(ScrobblerService.MAL, owner(ScrobblerService.MAL),
                reference(ScrobblerService.MAL), TrackerPatch.statusOnly("on_hold"), false, fixture) }.exceptionOrNull())
            assertEquals(1, fixture.mutations)
        }
        val drift = Wire(ScrobblerService.MAL).apply { changeNotesOnWrite = true }
        assertNotNull(runCatching { TrackerRawMutation.mutate(ScrobblerService.MAL, owner(ScrobblerService.MAL),
            reference(ScrobblerService.MAL), TrackerPatch.statusOnly("on_hold"), false, drift) }.exceptionOrNull())
        assertEquals(1, drift.mutations)
    }

    @Test fun everyProviderCompletesASelectedStatusMutationThroughFreshReadAndReadback() = runBlocking {
        for (service in ScrobblerService.entries) {
            val fixture = Wire(service).apply { if (service == ScrobblerService.ANILIST) entryId = "123" }
            val ref = reference(service, fixture.entryId)
            val after = TrackerRawMutation.mutate(service, owner(service), ref,
                TrackerPatch.statusOnly(paused(service)), false, fixture)
            assertEquals(paused(service), after.status)
            assertEquals(ref, after.reference)
            assertEquals("12.50", after.progress!!.number)
            assertEquals("Keep notes", after.notes)
            assertEquals(1, fixture.mutations)
        }
    }

    @Test fun everyProviderCreatesOnlyAfterSeparateConsentAndVerifiesItsNewExactIdentity() = runBlocking {
        for (service in ScrobblerService.entries) {
            val fixture = Wire(service).apply { present = false; if (service == ScrobblerService.ANILIST) entryId = "123" }
            val after = TrackerRawMutation.mutate(service, owner(service), reference(service, null),
                TrackerPatch.statusOnly(TrackerRawMutation.planning(service)), true, fixture)
            assertEquals(TrackerRawMutation.planning(service), after.status)
            assertEquals(reference(service, fixture.entryId), after.reference)
            assertEquals(1, fixture.mutations)
        }
    }

    private inner class Wire(private val service: ScrobblerService) : TrackerRawMutationWire {
        var present = true
        var entryId = "9007199254740993"
        var account = owner(service).accountId
        var status = when (service) {
            ScrobblerService.ANILIST -> "CURRENT"
            ScrobblerService.KITSU -> "current"
            ScrobblerService.SHIKIMORI -> "watching"
            else -> "reading"
        }
        val initialStatus = status
        var number = "12.50"
        var volume = "2.50"
        var notes = "Keep notes"
        var mutations = 0
        var mutationFailure: Exception? = null
        var changeNotesOnWrite = false
        override suspend fun request(request: Request): JsonElement {
            val mutation = request.method != "GET" && !(service == ScrobblerService.ANILIST && body(request).contains("query {"))
            if (mutation) {
                mutations++
                mutationFailure?.let { throw it }
                val row = if (request.body is FormBody) {
                    val form = request.body as FormBody
                    JsonObject((0 until form.size).associate { form.name(it) to kotlinx.serialization.json.JsonPrimitive(form.value(it)) })
                } else json(body(request)) as JsonObject
                if (service == ScrobblerService.MAL) status = row.raw("status") ?: status
                if (service == ScrobblerService.MANGABAKA) {
                    status = row.raw("state") ?: status
                    number = row.raw("progress_chapter") ?: number
                    volume = row.raw("progress_volume") ?: volume
                }
                if (service == ScrobblerService.ANILIST) {
                    val query = row.raw("query").orEmpty()
                    status = Regex("status: ([A-Z_]+)").find(query)?.groupValues?.get(1) ?: status
                }
                if (service == ScrobblerService.KITSU) status = row.obj("data").obj("attributes").raw("status") ?: status
                if (service == ScrobblerService.SHIKIMORI) {
                    status = row.obj("user_rate").raw("status") ?: if (!present) "planned" else status
                }
                if (changeNotesOnWrite) notes = "Unselected server change"
                present = true
                return when (service) {
                    ScrobblerService.MAL -> json("""{"status":"$status"}""")
                    ScrobblerService.MANGABAKA -> json("""{"status":200,"data":true}""")
                    ScrobblerService.ANILIST -> json("""{"data":{"SaveMediaListEntry":{"id":"$entryId","userId":"$account","mediaId":481}}}""")
                    ScrobblerService.KITSU -> json("""{"data":{"id":"$entryId","type":"libraryEntries"}}""")
                    ScrobblerService.SHIKIMORI -> json("""{"id":"$entryId","user_id":"$account","target_id":481,"target_type":"Manga"}""")
                }
            }
            return when (service) {
                ScrobblerService.ANILIST -> if (!present) json("""{"data":{"MediaList":null}}""") else json("""
                    {"data":{"MediaList":{"id":"$entryId","userId":"$account","mediaId":481,"status":"$status",
                    "progress":$number,"progressVolumes":$volume,"score":8.2500,"notes":"$notes","media":{
                    "id":481,"type":"MANGA","chapters":100,"volumes":10,"title":{"userPreferred":"Fixture"}}}}}
                """.trimIndent())
                ScrobblerService.MAL -> json("""{"id":481,"title":"Fixture","num_chapters":100,"num_volumes":10,
                    "my_list_status":${if (!present) "null" else """{"status":"$status","num_chapters_read":$number,"num_volumes_read":$volume,"score":8.2500,"comments":"$notes"}"""}}""")
                ScrobblerService.KITSU -> if (request.url.encodedPath.endsWith("/manga/481")) json("""
                    {"data":{"id":"481","type":"manga","attributes":{"canonicalTitle":"Fixture","chapterCount":100}}}
                """.trimIndent()) else json("""{"data":${if (!present) "[]" else """[{"id":"$entryId","type":"libraryEntries",
                    "attributes":{"status":"$status","progress":$number,"ratingTwenty":8.2500,"notes":"$notes"},
                    "relationships":{"user":{"data":{"id":"$account"}},"manga":{"data":{"id":"481"}}}}]"""}}""")
                ScrobblerService.SHIKIMORI -> if (request.url.encodedPath.endsWith("/mangas/481"))
                    json("""{"id":481,"name":"Fixture","chapters":100,"volumes":10}""")
                else if (!present) JsonArray(emptyList()) else json("""[{"id":"$entryId","user_id":"$account","target_id":481,
                    "target_type":"Manga","status":"$status","chapters":$number,"volumes":$volume,"score":8.2500,"text":"$notes"}]""")
                ScrobblerService.MANGABAKA -> if (request.url.encodedPath.endsWith("/series/481"))
                    json("""{"data":{"id":481,"title":"Fixture","total_chapters":100}}""")
                else if (!present) throw TrackerHttpException(404, 0, "missing")
                else json("""{"data":{"id":"$entryId","user_id":"$account","series_id":481,"state":"$status",
                    "progress_chapter":$number,"progress_volume":$volume,"rating":8.2500,"note":"$notes"}}""")
            }
        }
    }
}
