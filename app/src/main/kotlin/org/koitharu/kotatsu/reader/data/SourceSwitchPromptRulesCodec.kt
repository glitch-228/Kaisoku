package org.koitharu.kotatsu.reader.data

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Rule
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Scope

/** Small, versioned settings payload. An invalid entry cannot turn into an indefinite mute. */
internal object SourceSwitchPromptRulesCodec {
    const val MAX_RULES = 512
    const val MAX_PAYLOAD_LENGTH = 256 * 1024

    fun decode(value: String?): List<Rule> {
        if (value.isNullOrBlank() || value.length > MAX_PAYLOAD_LENGTH) return emptyList()
        val root = runCatching { JSONObject(value) }.getOrNull() ?: return emptyList()
        if (root.optInt("version") != 1) return emptyList()
        val entries = root.optJSONArray("rules") ?: return emptyList()
        if (entries.length() > MAX_RULES) return emptyList()
        val rules = LinkedHashMap<String?, Rule>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (!entry.has("source") || !entry.has("expiresAt")) continue
            val source = if (entry.isNull("source")) null else entry.opt("source") as? String ?: continue
            if (source != null && source.length !in 1..1024) continue
            val scope = runCatching { Scope.valueOf(entry.getString("scope")) }.getOrNull() ?: continue
            val rawExpiry = entry.opt("expiresAt")
            val expiry = if (entry.isNull("expiresAt")) null else when (rawExpiry) {
                is Int -> rawExpiry.toLong().takeIf { it > 0 } ?: continue
                is Long -> rawExpiry.takeIf { it > 0 } ?: continue
                else -> continue
            }
            rules[source] = Rule(source, scope, expiry)
        }
        return rules.values.toList()
    }

    fun encode(rules: List<Rule>): String {
        require(rules.size <= MAX_RULES)
        val entries = JSONArray()
        for (rule in rules) {
            entries.put(JSONObject().apply {
                put("source", rule.sourceName ?: JSONObject.NULL)
                put("scope", rule.scope.name)
                put("expiresAt", rule.expiresAt ?: JSONObject.NULL)
            })
        }
        return JSONObject().put("version", 1).put("rules", entries).toString().also {
            require(it.length <= MAX_PAYLOAD_LENGTH)
        }
    }
}
