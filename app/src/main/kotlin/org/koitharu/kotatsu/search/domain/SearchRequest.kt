package org.koitharu.kotatsu.search.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SourceScopeKind { ENABLED, PINNED, SINGLE }

@Serializable
data class ReadingSourceScope(
    val kind: SourceScopeKind = SourceScopeKind.ENABLED,
    val sourceName: String? = null,
) {
    init {
        require((kind == SourceScopeKind.SINGLE) == (sourceName != null))
        require(sourceName == null || sourceName.isNotBlank() && sourceName.length <= 1024)
    }

    fun contains(source: String, enabled: Set<String>, pinned: Set<String>): Boolean = when (kind) {
        SourceScopeKind.ENABLED -> source in enabled
        SourceScopeKind.PINNED -> source in pinned
        SourceScopeKind.SINGLE -> source == sourceName
    }
}

@Serializable
data class SearchTrackerAccount(
    val provider: String,
    val account: String,
    val revision: Long,
    val localAccount: Long,
) {
    init {
        require(provider in setOf("ANILIST", "MAL", "KITSU", "SHIKIMORI", "MANGABAKA"))
        require(account.isNotBlank() && account.length <= 512 && revision >= 0)
    }
}

@Serializable
data class SearchRequest(
    val query: String,
    val kind: SearchKind = SearchKind.SIMPLE,
    val sources: ReadingSourceScope = ReadingSourceScope(),
    val tracker: SearchTrackerAccount? = null,
    val trackerTarget: String? = null,
) {
    init {
        require(query.length <= 8192)
        require(trackerTarget == null || tracker != null && trackerTarget.isNotBlank() && trackerTarget.length <= 512)
    }

    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        const val EXTRA = "scoped_search_request"
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(value: String?): SearchRequest? = value?.takeIf { it.length <= 32_768 }?.let {
            runCatching { codec.decodeFromString(serializer(), it) }.getOrNull()
        }
    }
}
