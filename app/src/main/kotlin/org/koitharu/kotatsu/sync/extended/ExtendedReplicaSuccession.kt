package org.koitharu.kotatsu.sync.extended

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koitharu.kotatsu.backups.mihon.ForeignImportEnvelope
import org.koitharu.kotatsu.core.util.BoundedJson
import org.koitharu.kotatsu.history.exact.ChapterReadBackup
import org.koitharu.kotatsu.history.exact.ChapterReadEnvelope
import org.koitharu.kotatsu.history.exact.ChapterReadTransfer
import org.koitharu.kotatsu.scrobbling.v2.TrackerLinkTransfer
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusCodec

/** Proves retained causal state before immutable replicas may replace an older snapshot. */
internal object ExtendedReplicaSuccession {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun validate(namespace: String, predecessor: ByteArray, candidate: ByteArray) {
        val old = BoundedJson.validateObject(predecessor, ExtendedReplicaPayloads.MAX_BYTES)
        val next = BoundedJson.validateObject(candidate, ExtendedReplicaPayloads.MAX_BYTES)
        when (namespace) {
            ExtendedReplicaPayloads.CHAPTERS -> chapters(old, next)
            ExtendedReplicaPayloads.LINKS -> links(old, next)
            ExtendedReplicaPayloads.METADATA -> metadata(predecessor, candidate)
            ExtendedReplicaPayloads.FOREIGN -> foreign(old, next)
            ExtendedReplicaPayloads.LIBRARIES -> libraries(old, next)
            else -> error("Unsupported extended namespace")
        }
    }

    private fun chapters(old: String, next: String) {
        val before = json.decodeFromString<ChapterReadEnvelope>(old).also { it.validate() }
        val after = json.decodeFromString<ChapterReadEnvelope>(next).also { it.validate() }
        val editions = after.titles.groupBy { it.policy.source to it.policy.editionUrl }
        for (title in before.titles) {
            val successor = editions[title.policy.source to title.policy.editionUrl]?.singleOrNull()
                ?: error("Successor omits an exact chapter edition or its mapping is ambiguous")
            val current = successor.remap(1)
            val merged = ChapterReadTransfer.merge(current, title.remap(1))
            require(canonical(merged) == canonical(ChapterReadTransfer.merge(current, current))) {
                "Successor does not retain the chapter reset, unread gaps or identity evidence"
            }
        }
    }

    private fun canonical(value: ChapterReadBackup): ChapterReadBackup = value.copy(
        records = value.records.filter { it.generation == value.policy.generation }
            .sortedWith(compareBy({ it.generation }, { it.referenceId })),
        aliases = value.aliases.distinct().sortedWith(compareBy({ it.referenceId }, { it.source }, { it.branchKey }, { it.url })),
        counts = value.counts.sortedBy { it.branchKey }, manga = null,
    )

    private fun links(old: String, next: String) {
        val before = json.decodeFromString<ExtendedTrackerLinks>(old)
        val after = json.decodeFromString<ExtendedTrackerLinks>(next)
        require(before.version == 1 && after.version == 1)
        before.links.validate(); after.links.validate()
        fun witnesses(value: ExtendedTrackerLinks): Map<Pair<Long, Long>, ExtendedCategoryIdentity> {
            val required = value.categories?.let(CategoryStatusCodec::decodeTransfer).orEmpty()
                .flatMap { it.mappings }.map { it.categoryId to it.categoryCreatedAt }.toSet()
            val identities = value.categoryIdentities.associateBy { it.id to it.createdAt }
            require(identities.size <= 2_000 && identities.size == value.categoryIdentities.size && required == identities.keys &&
                identities.values.all { it.id > 0 && it.createdAt >= 0 && it.uid != null &&
                    it.uid.isNotBlank() && it.uid.length <= 256 && !it.uid.startsWith("legacy-") }) {
                "Tracker category identity evidence is missing or requires review"
            }
            return identities
        }
        val previousIdentities = witnesses(before)
        val nextIdentities = witnesses(after)
        val byIdentity = after.links.links.associateBy { it.key }
        for (link in before.links.links) {
            val successor = byIdentity[link.key] ?: error("Successor omits a tracker edition or tombstone")
            require(TrackerLinkTransfer.newer(successor, link).sameValue(successor)) {
                "Successor loses a tracker link version"
            }
        }
        val mappings = after.categories?.let(CategoryStatusCodec::decodeTransfer).orEmpty().associateBy { it.owner }
        for (configuration in before.categories?.let(CategoryStatusCodec::decodeTransfer).orEmpty()) {
            val successor = mappings[configuration.owner] ?: error("Successor omits tracker category intent")
            require(successor.revision > configuration.revision || successor == configuration) {
                "Successor loses tracker category mapping intent or its deletion"
            }
            if (successor.revision == configuration.revision) {
                require(configuration.mappings.all { mapping ->
                    val key = mapping.categoryId to mapping.categoryCreatedAt
                    previousIdentities[key]?.uid == nextIdentities[key]?.uid
                }) { "Successor changes an unchanged tracker category identity" }
            }
        }
    }

    private fun metadata(old: ByteArray, next: ByteArray) {
        val before = MetadataOverrideCodec.decode(old)
        val after = MetadataOverrideCodec.decode(next).fields.associateBy { it.key }
        for (field in before.fields) {
            val successor = after[field.key] ?: error("Successor omits a saved metadata field; review archive privacy or edition mapping")
            require(successor.revision > field.revision || successor.revision == field.revision &&
                successor.value == field.value && successor.coverSha256 == field.coverSha256) {
                "Successor loses a saved metadata field version"
            }
        }
    }

    private fun foreign(old: String, next: String) {
        val before = json.decodeFromString<ForeignImportEnvelope>(old).also { it.validate() }
        val after = json.decodeFromString<ForeignImportEnvelope>(next).also { it.validate() }
        val pending = after.pending.associateBy { it.importId }
        for (row in before.pending) {
            val successor = pending[row.importId] ?: error("Successor omits an unresolved foreign tracker reference")
            require(successor.acceptedAt <= row.acceptedAt &&
                successor.copy(mangaId = row.mangaId, acceptedAt = row.acceptedAt) == row) {
                "Successor changes immutable foreign tracker evidence"
            }
        }
        val receipts = after.receipts.groupBy { Triple(it.importDigest, it.source, it.editionUrl) }
        for (row in before.receipts) {
            val successor = receipts[Triple(row.importDigest, row.source, row.editionUrl)]?.singleOrNull()
                ?: error("Successor omits an import receipt or its edition is ambiguous")
            require(successor.acceptedAt <= row.acceptedAt) { "Successor loses an earlier import receipt" }
        }
    }

    private fun libraries(old: String, next: String) {
        val before = json.parseToJsonElement(old) as JsonObject
        val after = json.parseToJsonElement(next) as JsonObject
        require(before["version"]?.jsonPrimitive?.content == "1" && after["version"]?.jsonPrimitive?.content == "1")
        fun records(root: JsonObject, key: String): List<JsonObject> = (root[key] as? JsonArray
            ?: error("Missing remote-library replica records")).map { it as? JsonObject ?: error("Invalid library record") }
        fun identity(row: JsonObject, keys: List<String>) = keys.map {
            row[it]?.jsonPrimitive?.content ?: error("Missing library record identity")
        }
        for ((name, keys) in listOf("connections" to listOf("id"),
            "acquisitions" to listOf("connectionId", "publicationId", "localMangaId", "localUrl"))) {
            val candidates = records(after, name).groupBy { identity(it, keys) }
            for (row in records(before, name)) {
                val successor = candidates[identity(row, keys)]?.singleOrNull()
                    ?: error("Successor omits a private library reference or acquisition")
                val ignored = if (name == "connections") setOf("enabled", "name") else emptySet()
                require(row.filterKeys { it !in ignored } == successor.filterKeys { it !in ignored }) {
                    "Successor changes private-library scope or acquisition evidence"
                }
            }
        }
    }
}
