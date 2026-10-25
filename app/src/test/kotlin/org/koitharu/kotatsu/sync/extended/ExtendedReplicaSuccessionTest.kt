package org.koitharu.kotatsu.sync.extended

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.backups.mihon.ForeignImportEnvelope
import org.koitharu.kotatsu.backups.mihon.ForeignImportReceiptEntity
import org.koitharu.kotatsu.backups.mihon.PendingTrackerImportEntity
import org.koitharu.kotatsu.history.exact.ChapterReadAliasEntity
import org.koitharu.kotatsu.history.exact.ChapterReadBackup
import org.koitharu.kotatsu.history.exact.ChapterReadCountEntity
import org.koitharu.kotatsu.history.exact.ChapterReadEnvelope
import org.koitharu.kotatsu.history.exact.ChapterReadPolicyEntity
import org.koitharu.kotatsu.history.exact.ChapterReadRecordEntity
import org.koitharu.kotatsu.history.exact.ChapterReadTransfer
import org.koitharu.kotatsu.history.exact.ReadChapterReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerLinkEnvelope
import org.koitharu.kotatsu.scrobbling.v2.TrackerLinkReplica
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusCodec
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusConfiguration
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusMapping
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusRevision

/** Pure causal retention checks; no Room, Android storage, Drive or tracker account is opened. */
class ExtendedReplicaSuccessionTest {
    private val json = Json { encodeDefaults = true }
    private val owner = TrackerOwner("COMICK", "opaque-owner-uuid")
    private val refs = (1L..3L).map { ReadChapterReference("REMANGA", null, it, "/chapter/$it") }
    private val policy = ChapterReadPolicyEntity(481, "REMANGA", "/edition", "generation-one", true, false,
        "N", 100, 0, "replica-a", null)
    private val connectionId = "12345678-1234-4234-8234-123456789abc"

    @Test fun everyNamespaceRejectsOmissionInsteadOfTreatingAnEmptySuccessorAsDeletion() {
        val pairs = linkedMapOf(
            ExtendedReplicaPayloads.CHAPTERS to (chapters(chapter()) to chapters()),
            ExtendedReplicaPayloads.LINKS to (links(listOf(link())) to links()),
            ExtendedReplicaPayloads.METADATA to (metadata(field()) to metadata()),
            ExtendedReplicaPayloads.FOREIGN to (foreign(listOf(pending()), listOf(receipt())) to foreign()),
            ExtendedReplicaPayloads.LIBRARIES to (libraries() to libraries(emptyList(), emptyList())),
        )
        for ((namespace, pair) in pairs) {
            accepts(namespace, pair.first, pair.first.copyOf())
            rejects(namespace, pair.first, pair.second)
        }
    }

    @Test fun exactUnreadGapMustSurviveEvenWhenAggregateReadCountsWouldBeUnchanged() {
        val before = chapter().let { state -> state.copy(records = state.records.map {
            if (it.chapterId == 2L) it.copy(isRead = false, revisionWall = 200) else it
        }) }
        val omittedGap = before.copy(records = before.records.filter { it.isRead })
        assertEquals(before.records.count { it.isRead }, omittedGap.records.count { it.isRead })
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(omittedGap))
        accepts(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(before.copy(
            records = before.records.reversed(), counts = before.counts.map { it.copy(readCount = 2) })))
    }

    @Test fun newerExplicitUnreadAndResetBarrierAbsorbOldReadsWithoutResurrection() {
        val before = chapter()
        val unread = before.copy(records = before.records.map {
            if (it.chapterId == 2L) it.copy(isRead = false, revisionWall = 200, revisionActor = "replica-b") else it
        })
        accepts(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(unread))
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(unread), chapters(before))
        val reset = unread.copy(policy = unread.policy.copy(generation = "reset-two", deleted = true,
            revisionWall = 300, generationWall = 300), records = emptyList(),
            counts = unread.counts.map { it.copy(readCount = 0) })
        val disconnectedOldReads = before.copy(records = before.records.map { it.copy(revisionWall = 999_999) })
        accepts(ExtendedReplicaPayloads.CHAPTERS, chapters(disconnectedOldReads), chapters(reset))
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(reset), chapters(before))
    }

    @Test fun mangaIdsRemapByEditionWhileAliasesAndConfirmedManifestRemainNecessaryEvidence() {
        val alias = ChapterReadAliasEntity(481, refs.first().key, "REMANGA", "N", "/canonical/one")
        val before = chapter().copy(aliases = listOf(alias))
        accepts(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(before.remap(900)))
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(before),
            chapters(before.remap(900).copy(aliases = emptyList())))
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(before.copy(counts = emptyList())))
        val remappedRefs = refs.map { it.copy(chapterId = it.chapterId + 1000) }
        val remappedManifest = ChapterReadCountEntity(900, "N", 0, remappedRefs.size,
            ChapterReadTransfer.listRevision(remappedRefs), ChapterReadTransfer.encodeReferences(remappedRefs),
            400, 0, "replica-b")
        val successor = before.remap(900).copy(counts = listOf(remappedManifest))
        accepts(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(successor))
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(successor, before.remap(901)))
    }

    @Test fun differentExactEditionAndOlderManifestCannotReplaceRetainedFrontiers() {
        val before = chapter()
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(before.copy(
            policy = before.policy.copy(editionUrl = "/different-adaptation"))))
        rejects(ExtendedReplicaPayloads.CHAPTERS, chapters(before), chapters(before.copy(
            counts = before.counts.map { it.copy(revisionWall = 99) })))
    }

    @Test fun opaqueFractionalTrackerValuesRequireEqualOrNewerCausalVersionWithoutNumericProjection() {
        val before = link()
        accepts(ExtendedReplicaPayloads.LINKS, links(listOf(before)), links(listOf(before.copy(mangaId = 900))))
        for (changed in listOf(before.copy(progressNumber = "12"), before.copy(listEntryId = "9007199254740992"),
            before.copy(mediaId = "123"), before.copy(accountId = "another-owner"))) {
            rejects(ExtendedReplicaPayloads.LINKS, links(listOf(before)), links(listOf(changed)))
        }
        val newer = before.copy(progressNumber = "13.125", logical = before.logical + 1)
        accepts(ExtendedReplicaPayloads.LINKS, links(listOf(before)), links(listOf(newer)))
        rejects(ExtendedReplicaPayloads.LINKS, links(listOf(newer)), links(listOf(before)))
    }

    @Test fun trackerDeletionTombstoneDominatesOldValueButCannotDisappearOrRegress() {
        val before = link()
        val deleted = before.copy(deleted = true, wall = 200)
        accepts(ExtendedReplicaPayloads.LINKS, links(listOf(before)), links(listOf(deleted)))
        rejects(ExtendedReplicaPayloads.LINKS, links(listOf(deleted)), links())
        rejects(ExtendedReplicaPayloads.LINKS, links(listOf(deleted)), links(listOf(before)))
    }

    @Test fun categoryIntentNeedsAnExplicitNewerDeletionAndCannotBeDropped() {
        val mapping = configuration()
        val before = links(configurations = listOf(mapping))
        rejects(ExtendedReplicaPayloads.LINKS, before, links())
        rejects(ExtendedReplicaPayloads.LINKS, before,
            links(configurations = listOf(mapping.copy(requestedEnabled = false))))
        val deletion = mapping.copy(revision = CategoryStatusRevision(2, "replica-b"), requestedEnabled = false,
            mappings = emptyList(), deleted = true)
        accepts(ExtendedReplicaPayloads.LINKS, before, links(configurations = listOf(deletion)))
        rejects(ExtendedReplicaPayloads.LINKS, links(configurations = listOf(deletion)), before)
        val edited = mapping.copy(revision = CategoryStatusRevision(3, "replica-b"),
            mappings = listOf(CategoryStatusMapping(7, 70, "COMPLETED")))
        accepts(ExtendedReplicaPayloads.LINKS, before, links(configurations = listOf(edited)))
    }

    @Test fun unchangedCategoryIntentCannotLoseOrChangeItsExactIdentityWitness() {
        val configuration = configuration()
        val original = linkEnvelope(configurations = listOf(configuration))
        val bytes = encode(original)
        rejects(ExtendedReplicaPayloads.LINKS, bytes, encode(original.copy(categoryIdentities = emptyList())))
        rejects(ExtendedReplicaPayloads.LINKS, bytes, encode(original.copy(categoryIdentities =
            original.categoryIdentities.map { it.copy(uid = "category-v2-other") })))
        rejects(ExtendedReplicaPayloads.LINKS, bytes, encode(original.copy(categoryIdentities =
            original.categoryIdentities.map { it.copy(uid = "legacy-name") })))
        rejects(ExtendedReplicaPayloads.LINKS, bytes, encode(original.copy(categoryIdentities =
            original.categoryIdentities.map { it.copy(uid = null) })))
    }

    @Test fun metadataOmissionFailsEvenWhenFilteredReexportIsAByteFixedPoint() {
        val before = metadata(field("title", "Saved"), field("description", "Private synopsis"))
        val filtered = metadata(field("title", "Saved"))
        assertArrayEquals(filtered, MetadataOverrideCodec.encode(MetadataOverrideCodec.decode(filtered)))
        rejects(ExtendedReplicaPayloads.METADATA, before, filtered)
        val cleared = field("description", null).copy(revisionWall = 200)
        accepts(ExtendedReplicaPayloads.METADATA, before, metadata(field("title", "Saved"), cleared))
        rejects(ExtendedReplicaPayloads.METADATA, metadata(cleared), metadata())
        rejects(ExtendedReplicaPayloads.METADATA, metadata(cleared), metadata(field("description", "Private synopsis")))
    }

    @Test fun equalMetadataVersionsNeedEqualValuesWhileIdRemappingAndNewerClearsAreAccepted() {
        val before = field()
        accepts(ExtendedReplicaPayloads.METADATA, metadata(before), metadata(before.copy(mangaId = 999)))
        rejects(ExtendedReplicaPayloads.METADATA, metadata(before), metadata(before.copy(value = "Conflicting title")))
        rejects(ExtendedReplicaPayloads.METADATA, metadata(before), metadata(before.copy(editionUrl = "/other")))
        accepts(ExtendedReplicaPayloads.METADATA, metadata(before),
            metadata(before.copy(value = null, revisionWall = 101)))
    }

    @Test fun immutableForeignPendingEvidenceAllowsIdRemappingAndEarlierAcceptanceOnly() {
        val row = pending()
        val before = foreign(listOf(row))
        accepts(ExtendedReplicaPayloads.FOREIGN, before, foreign(listOf(row.copy(mangaId = 900, acceptedAt = 80))))
        for (changed in listOf(row.copy(acceptedAt = 101), row.copy(rawProgress = "12"),
            row.copy(libraryId = "9007199254740992"), row.copy(rawPayload = "{\"changed\":true}"),
            row.copy(editionUrl = "/different"))) {
            rejects(ExtendedReplicaPayloads.FOREIGN, before, foreign(listOf(changed)))
        }
        rejects(ExtendedReplicaPayloads.FOREIGN, before, foreign())
    }

    @Test fun receiptKeepsEarliestAcceptanceAndUniqueExactEditionAndCannotBeOmitted() {
        val row = receipt()
        val before = foreign(receipts = listOf(row))
        accepts(ExtendedReplicaPayloads.FOREIGN, before,
            foreign(receipts = listOf(row.copy(mangaId = 900, acceptedAt = 50))))
        rejects(ExtendedReplicaPayloads.FOREIGN, before, foreign(receipts = listOf(row.copy(acceptedAt = 101))))
        rejects(ExtendedReplicaPayloads.FOREIGN, before, foreign(receipts = listOf(row.copy(editionUrl = "/other"))))
        rejects(ExtendedReplicaPayloads.FOREIGN, before, foreign(receipts = listOf(row, row.copy(mangaId = 900))))
        rejects(ExtendedReplicaPayloads.FOREIGN, before, foreign())
    }

    @Test fun libraryScopeAndAcquisitionEvidenceSurviveWhileLocalEnablementAndLabelsMayDiffer() {
        val before = libraries()
        val renamedDisabled = libraryConnection() + mapOf("enabled" to JsonPrimitive(false),
            "name" to JsonPrimitive("Local label"))
        accepts(ExtendedReplicaPayloads.LIBRARIES, before, libraries(listOf(JsonObject(renamedDisabled))))
        for ((key, value) in listOf("scopeUrl" to "https://library.invalid/other/", "account" to "another-account",
            "catalogUrl" to "https://another.invalid/catalog.xml", "content" to "SAFE", "kind" to "KOMGA")) {
            rejects(ExtendedReplicaPayloads.LIBRARIES, before,
                libraries(listOf(JsonObject(libraryConnection() + (key to JsonPrimitive(value))))))
        }
        rejects(ExtendedReplicaPayloads.LIBRARIES, before, libraries(acquisitions = emptyList()))
        for ((key, value) in listOf("publicationId" to "other-publication", "sha256" to "b".repeat(64),
            "localUrl" to "file:///different/book.epub", "format" to "CBZ")) {
            rejects(ExtendedReplicaPayloads.LIBRARIES, before,
                libraries(acquisitions = listOf(JsonObject(acquisition() + (key to JsonPrimitive(value))))))
        }
        rejects(ExtendedReplicaPayloads.LIBRARIES, before, libraries(acquisitions = listOf(
            JsonObject(acquisition() + ("localMangaId" to JsonPrimitive(999))))))
    }

    @Test fun malformedJsonDuplicateKeysAndUnknownNamespaceNeverAuthorizeCompaction() {
        val bytes = metadata(field())
        rejects("legacy_unknown", bytes, bytes)
        rejects(ExtendedReplicaPayloads.METADATA, bytes, "{\"version\":1,\"version\":1,\"fields\":[]}".toByteArray())
        rejects(ExtendedReplicaPayloads.METADATA, bytes, byteArrayOf(0xc0.toByte(), 0x80.toByte()))
        rejects(ExtendedReplicaPayloads.LIBRARIES, libraries(),
            "{\"version\":2,\"connections\":[],\"acquisitions\":[]}".toByteArray())
    }

    private fun chapter() = ChapterReadBackup(policy, refs.map { ref -> ChapterReadRecordEntity(
        481, policy.generation, ref.key, ref.source, ref.branchKey, ref.chapterId, ref.url, true,
        100, 0, "replica-a") },
        emptyList(), listOf(ChapterReadCountEntity(481, "N", 3, 3, ChapterReadTransfer.listRevision(refs),
            ChapterReadTransfer.encodeReferences(refs), 100, 0, "replica-a")))

    private fun link() = TrackerLinkReplica(481, owner.provider, owner.accountId,
        "REMANGA", "/edition", "hid-opaque-A7",
        "9007199254740993123", "12.750", "2.50", "entry/12.750", "chapter", "CURRENT", "raw-time",
        false, 100, 1, "replica-a")

    private fun configuration() = CategoryStatusConfiguration(owner, CategoryStatusRevision(1, "replica-a"), true,
        listOf(CategoryStatusMapping(7, 70, "CURRENT")))

    private fun field(name: String = "title", value: String? = "Saved") = MetadataOverrideReplica(481, "REMANGA",
        "/edition", name, value, 100, 0, "replica-a")

    private fun pending() = PendingTrackerImportEntity("a".repeat(64), "b".repeat(64), 481, "REMANGA", "/edition",
        "COMICK", 123, "hid-opaque-A7", "9007199254740993123", "12.750", "CURRENT", "8.125", 100,
        "{\"progress\":\"12.750\",\"reference\":\"opaque\"}")

    private fun receipt() = ForeignImportReceiptEntity("b".repeat(64), 481, "REMANGA", "/edition", 100)
    private fun chapters(vararg titles: ChapterReadBackup) = encode(ChapterReadEnvelope(titles = titles.toList()))
    private fun links(records: List<TrackerLinkReplica> = emptyList(),
        configurations: List<CategoryStatusConfiguration>? = null) = encode(linkEnvelope(records, configurations))

    private fun linkEnvelope(records: List<TrackerLinkReplica> = emptyList(),
        configurations: List<CategoryStatusConfiguration>? = null) = ExtendedTrackerLinks(
        links = TrackerLinkEnvelope(links = records),
        categories = configurations?.let(CategoryStatusCodec::encodeTransfer),
        categoryIdentities = configurations.orEmpty().flatMap { it.mappings }.distinctBy { it.categoryId }.map {
            ExtendedCategoryIdentity(it.categoryId, it.categoryCreatedAt, "category-v2-fixture-" + it.categoryId)
        })

    private fun metadata(vararg fields: MetadataOverrideReplica) =
        MetadataOverrideCodec.encode(MetadataOverrideEnvelope(fields = fields.toList()))
    private fun foreign(pending: List<PendingTrackerImportEntity> = emptyList(),
        receipts: List<ForeignImportReceiptEntity> = emptyList()) =
        encode(ForeignImportEnvelope(pending = pending, receipts = receipts))

    private fun libraryConnection() = buildJsonObject {
        put("id", connectionId); put("name", "Fixture library")
        put("catalogUrl", "https://library.invalid/books/catalog.xml")
        put("scopeUrl", "https://library.invalid/books/")
        put("kind", "OPDS"); put("account", "opaque-library-account"); put("content", "UNKNOWN")
        put("enabled", true); put("allowCleartextLan", false)
    }

    private fun acquisition() = buildJsonObject {
        put("connectionId", connectionId); put("publicationId", "urn:uuid:opaque-publication")
        put("localMangaId", 481); put("localUrl", "file:///owned/books/one.epub")
        put("sha256", "a".repeat(64)); put("format", "EPUB")
        put("metadata", buildJsonObject {
            put("title", "Owned title"); put("authors", JsonArray(listOf(JsonPrimitive("Author"))))
            put("subjects", JsonArray(emptyList())); put("content", "UNKNOWN")
        })
    }

    private fun libraries(connections: List<JsonObject> = listOf(libraryConnection()),
        acquisitions: List<JsonObject> = listOf(acquisition())) = buildJsonObject {
        put("version", 1); put("connections", JsonArray(connections)); put("acquisitions", JsonArray(acquisitions))
    }.toString().toByteArray(Charsets.UTF_8)

    private inline fun <reified T> encode(value: T) = json.encodeToString(value).toByteArray(Charsets.UTF_8)
    private fun accepts(namespace: String, predecessor: ByteArray, candidate: ByteArray) {
        ExtendedReplicaSuccession.validate(namespace, predecessor, candidate)
    }
    private fun rejects(namespace: String, predecessor: ByteArray, candidate: ByteArray) {
        assertTrue("$namespace unexpectedly authorized predecessor compaction",
            runCatching { ExtendedReplicaSuccession.validate(namespace, predecessor, candidate) }.isFailure)
    }
}
