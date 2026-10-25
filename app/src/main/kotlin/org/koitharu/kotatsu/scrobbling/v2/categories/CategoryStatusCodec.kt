package org.koitharu.kotatsu.scrobbling.v2.categories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koitharu.kotatsu.scrobbling.v2.TrackerConnection
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference

internal object CategoryStatusCodec {
    fun encodeTransfer(configs: List<CategoryStatusConfiguration>): String = buildJsonObject {
        put("format", "kaisoku.category-status")
        put("version", 1)
        put("configurations", JsonArray(configs.sortedWith(compareBy({ it.owner.provider }, { it.owner.accountId }))
            .map(::configuration)))
    }.toString()

    fun decodeTransfer(value: String): List<CategoryStatusConfiguration> = root(value).let { root ->
        require(root.text("format") == "kaisoku.category-status" && root.number("version") == 1L)
        root.array("configurations", CategoryStatusLimits.ACCOUNTS).map { configuration(it.jsonObject) }.also {
            require(it.map { config -> config.owner }.distinct().size == it.size) { "Duplicate category mapping accounts" }
        }
    }

    fun encodeJournal(value: CategoryStatusJournal): String = buildJsonObject {
        put("format", "kaisoku.category-status-journal")
        put("version", 1)
        put("replica", value.replicaId)
        put("clock", value.clock)
        put("configurations", JsonArray(value.configurations.values.map(::configuration)))
        put("armed", JsonArray(value.armed.map { (owner, revision) -> buildJsonObject {
            put("owner", owner(owner)); put("revision", revision(revision))
        } }))
        put("baselines", JsonArray(value.baselines.values.map { baseline -> buildJsonObject {
            put("owner", owner(baseline.key.owner)); put("manga", baseline.key.mangaId)
            put("identity", baseline.identity); put("memberships", baseline.memberships)
            put("revision", revision(baseline.configuration))
        } }))
        put("pending", JsonArray(value.pending.values.map(::pending)))
    }.toString()

    fun decodeJournal(value: String): CategoryStatusJournal = root(value).let { root ->
        require(root.text("format") == "kaisoku.category-status-journal" && root.number("version") == 1L)
        val configs = root.array("configurations", CategoryStatusLimits.ACCOUNTS)
            .map { configuration(it.jsonObject) }
        val armed = root.array("armed", CategoryStatusLimits.ACCOUNTS).map { item -> item.jsonObject.let {
            owner(it.obj("owner")) to revision(it.obj("revision"))
        } }
        val baselines = root.array("baselines", CategoryStatusLimits.BASELINES).map { item -> item.jsonObject.let {
            val key = CategoryStatusKey(owner(it.obj("owner")), it.number("manga"))
            key to CategoryStatusBaseline(key, it.text("identity"), it.text("memberships"), revision(it.obj("revision")))
        } }
        val pending = root.array("pending", CategoryStatusLimits.PENDING).map { item -> pending(item.jsonObject).let {
            CategoryStatusKey(it.guard.owner, it.guard.mangaId) to it
        } }
        require(configs.map { it.owner }.distinct().size == configs.size && armed.toMap().size == armed.size &&
            baselines.toMap().size == baselines.size && pending.toMap().size == pending.size) { "Duplicate journal records" }
        CategoryStatusJournal(root.text("replica"), root.number("clock"), configs.associateBy { it.owner },
            armed.toMap(), baselines.toMap(), pending.toMap()).also { it.validate() }
    }

    private fun configuration(value: CategoryStatusConfiguration) = buildJsonObject {
        put("owner", owner(value.owner)); put("revision", revision(value.revision))
        put("enabled", value.requestedEnabled); put("deleted", value.deleted)
        put("mappings", JsonArray(value.mappings.sortedBy { it.categoryId }.map { mapping -> buildJsonObject {
            put("category", mapping.categoryId); put("created", mapping.categoryCreatedAt); put("status", mapping.status)
        } }))
    }

    private fun configuration(value: JsonObject) = CategoryStatusConfiguration(owner(value.obj("owner")),
        revision(value.obj("revision")), value.flag("enabled"), value.array("mappings", CategoryStatusLimits.MAPPINGS)
            .map { item -> item.jsonObject.let { CategoryStatusMapping(it.number("category"), it.number("created"),
                it.text("status")) } }, value.flag("deleted"))

    private fun owner(value: TrackerOwner) = buildJsonObject { put("provider", value.provider); put("account", value.accountId) }
    private fun owner(value: JsonObject) = TrackerOwner(value.text("provider"), value.text("account"))
    private fun revision(value: CategoryStatusRevision) = buildJsonObject {
        put("counter", value.counter); put("replica", value.replicaId)
    }
    private fun revision(value: JsonObject) = CategoryStatusRevision(value.number("counter"), value.text("replica"))

    private fun guard(value: CategoryStatusGuard) = buildJsonObject {
        put("owner", owner(value.owner)); put("connectionRevision", value.connection.revision)
        put("nickname", value.connection.nickname?.let(::JsonPrimitive) ?: JsonNull)
        put("scopes", JsonArray(value.connection.scopes.sorted().map(::JsonPrimitive)))
        put("manga", value.mangaId); put("source", value.sourceName); put("url", value.sourceUrl)
        put("edition", value.editionKey?.let(::JsonPrimitive) ?: JsonNull)
        put("media", value.reference.mediaId); put("entry", value.reference.listEntryId?.let(::JsonPrimitive) ?: JsonNull)
        put("linkRevision", value.linkRevision); put("revision", revision(value.configRevision))
        put("memberships", value.membershipFingerprint)
    }

    private fun guard(value: JsonObject): CategoryStatusGuard {
        val owner = owner(value.obj("owner"))
        return CategoryStatusGuard(TrackerConnection(owner, value.number("connectionRevision"),
            value.array("scopes", 64).map { primitive(it).content }.toSet(),
            value["nickname"]?.takeUnless { it == JsonNull }?.let { primitive(it).content }), value.number("manga"), value.text("source"),
            value.text("url"), value.optional("edition"), TrackerReference(owner, value.text("media"), value.optional("entry")),
            value.text("linkRevision"), revision(value.obj("revision")), value.text("memberships"))
    }

    private fun binding(value: CategoryStatusBinding) = buildJsonObject {
        put("category", value.categoryId); put("created", value.categoryCreatedAt); put("title", value.title)
    }
    private fun binding(value: JsonObject) = CategoryStatusBinding(value.number("category"), value.number("created"),
        value.text("title"))

    private fun pending(value: CategoryStatusPending) = buildJsonObject {
        put("id", value.id); put("title", value.title); put("guard", guard(value.guard)); put("state", value.state.name)
        put("status", value.status?.let(::JsonPrimitive) ?: JsonNull)
        put("conflicts", buildJsonObject { value.conflicts.toSortedMap().forEach { (status, bindings) ->
            put(status, JsonArray(bindings.map(::binding)))
        } })
        put("error", value.error?.let(::JsonPrimitive) ?: JsonNull)
        put("retryAt", value.retryAtMillis); put("attempts", value.attempts)
    }

    private fun pending(value: JsonObject) = CategoryStatusPending(value.text("id"), value.text("title"),
        guard(value.obj("guard")), CategoryStatusActionState.valueOf(value.text("state")), value.optional("status"),
        value.obj("conflicts").also { require(it.size <= 32) }.mapValues { (_, entries) ->
            (entries as? JsonArray ?: error("Invalid category conflict")).also { require(it.size <= 2000) }
                .map { binding(it.jsonObject) }
        }, value.optional("error"), value.number("retryAt"), value.number("attempts").let {
            require(it in 0..10000); it.toInt()
        })

    private fun root(value: String): JsonObject {
        require(value.toByteArray(Charsets.UTF_8).size <= CategoryStatusLimits.BYTES) { "Category mapping transfer is too large" }
        org.koitharu.kotatsu.core.util.BoundedJson.validateObject(value.toByteArray(Charsets.UTF_8),
            CategoryStatusLimits.BYTES)
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in value) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "Category mapping nesting limit exceeded" } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
        return Json.parseToJsonElement(value).jsonObject
    }

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.array(name: String, limit: Int): JsonArray = (getValue(name) as? JsonArray
        ?: error("Invalid category mapping array")).also { require(it.size <= limit) }
    private fun JsonObject.text(name: String): String = primitive(getValue(name)).also { require(it.isString) }.content
    private fun JsonObject.optional(name: String): String? = getValue(name).takeUnless { it == JsonNull }
        ?.let { primitive(it).also { text -> require(text.isString) }.content }
    private fun JsonObject.number(name: String): Long = requireNotNull(primitive(getValue(name)).longOrNull)
    private fun JsonObject.flag(name: String): Boolean = requireNotNull(primitive(getValue(name)).booleanOrNull)
    private fun primitive(value: JsonElement): JsonPrimitive = value as? JsonPrimitive ?: error("Invalid category mapping value")
}
