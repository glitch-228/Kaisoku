package org.koitharu.kotatsu.remote.library

import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persisted favorites can reconstruct the connection identity even while it is disconnected. */
class RemoteLibrarySource(val connectionId: String, val displayName: String = connectionId,
                          val content: RemoteLibraryContent = RemoteLibraryContent.UNKNOWN) : MangaSource {
    init { require(UUID.fromString(connectionId).toString() == connectionId) }
    override val name: String get() = PREFIX + connectionId
    override fun equals(other: Any?): Boolean = other is MangaSource && name == other.name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name
    fun resolved(): RemoteLibrarySource = RemoteLibrarySourceRegistry.peek(connectionId) ?: this

    companion object {
        const val PREFIX = "library:"
        fun fromName(name: String): RemoteLibrarySource? = name.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)?.let { id -> RemoteLibrarySourceRegistry.peek(id) ?: runCatching { RemoteLibrarySource(id) }.getOrNull() }
    }
}

/** Atomic metadata snapshot for display/content getters. Never performs disk or network work. */
object RemoteLibrarySourceRegistry {
    private data class Snapshot(val sources: Map<String, RemoteLibrarySource>,
                                val acquired: Map<Pair<Long, String>, LibraryAcquiredMetadata>)
    @Volatile private var snapshot = Snapshot(emptyMap(), emptyMap())
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> get() = mutableChanges
    fun peek(connectionId: String): RemoteLibrarySource? = snapshot.sources[connectionId]
    fun acquiredMetadata(mangaId: Long, uri: String): LibraryAcquiredMetadata? = snapshot.acquired[mangaId to uri]
    internal fun acquiredContent(): Map<Pair<Long, String>, RemoteLibraryContent> =
        snapshot.acquired.mapValues { it.value.content }
    @Synchronized fun replaceAll(connections: Collection<RemoteLibraryConnection>,
                                acquisitions: Collection<LibraryAcquisitionRecord> = emptyList(),
                                verifiedAcquisitions: Collection<LibraryAcquisitionRecord> = emptyList()) {
        require(connections.size <= 32 && acquisitions.size <= 10_000)
        val verified = verifiedAcquisitions.toSet()
        replace(Snapshot(connections.associate { it.id to RemoteLibrarySource(it.id, it.name, it.content) },
            acquisitions.groupBy { it.localMangaId to it.localUrl }.mapNotNull { (key, records) ->
                acquiredMetadata(records, records.filter { it in verified })?.let { key to it }
            }.toMap()))
    }

    @Synchronized internal fun updateAcquiredMetadata(mangaId: Long, uri: String, metadata: LibraryAcquiredMetadata?) {
        val next = snapshot.acquired.toMutableMap()
        if (metadata == null) next.remove(mangaId to uri) else next[mangaId to uri] = metadata
        replace(snapshot.copy(acquired = next))
    }

    private fun replace(next: Snapshot) {
        if (snapshot.acquired == next.acquired && snapshot.sources.keys == next.sources.keys &&
            snapshot.sources.all { (id, source) -> next.sources[id]?.let {
                it.displayName == source.displayName && it.content == source.content
            } == true }) return
        snapshot = next
        mutableChanges.value += 1L
    }
}

/** Unknown/restored/colliding receipts constrain visibility but cannot supply another file's metadata or SAFE rating. */
internal fun acquiredMetadata(records: List<LibraryAcquisitionRecord>,
                              verified: List<LibraryAcquisitionRecord>): LibraryAcquiredMetadata? {
    if (records.isEmpty()) return null
    if (records.size == 1 && verified.singleOrNull() == records.single()) return records.single().metadata
    val content = if (records.any { it.metadata?.content == RemoteLibraryContent.ADULT }) RemoteLibraryContent.ADULT
        else RemoteLibraryContent.UNKNOWN
    return LibraryAcquiredMetadata("", emptySet(), emptySet(), null, content)
}
