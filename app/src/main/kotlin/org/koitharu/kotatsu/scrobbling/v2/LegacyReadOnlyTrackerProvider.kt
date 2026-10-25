package org.koitharu.kotatsu.scrobbling.v2

import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.withSession
import org.koitharu.kotatsu.scrobbling.common.data.withTrackerRequestGuard

/** Reading the provider list never runs the old cache refresh or its outgoing-progress workers. */
internal class LegacyReadOnlyTrackerProvider(
    override val key: String,
    private val repository: ScrobblerRepository,
    private val connect: suspend () -> TrackerConnection,
    private val validate: (TrackerConnection) -> Unit,
) : TrackerProvider {
    override val capabilities: Set<TrackerCapability>
        get() = setOf(TrackerCapability.SEARCH, TrackerCapability.METADATA,
            TrackerCapability.LIBRARY, TrackerCapability.PROGRESS_READ) + if (repository.supportsRawWrites)
                setOf(TrackerCapability.PROGRESS_WRITE, TrackerCapability.STATUS_WRITE) else emptySet()

    override suspend fun connection(): TrackerConnection = connect()
    override fun checkConnection(connection: TrackerConnection) = validate(connection)

    private suspend fun <T> read(connection: TrackerConnection, block: suspend () -> T): T {
        checkConnection(connection)
        val session = repository.storage.snapshot()
        checkConnection(connection)
        return withTrackerRequestGuard({ checkConnection(connection); repository.storage.checkSession(session) }) {
            repository.storage.withSession(session) { block() }.also { checkConnection(connection) }
        }
    }

    override suspend fun search(connection: TrackerConnection, query: String): List<TrackerTitle> = read(connection) {
        require(query.trim().length in 2..100)
        repository.findManga(query, 0).also { require(it.size <= 100) }.map {
            require(it.id > 0)
            TrackerTitle(it.id.toString(), it.name, setOfNotNull(it.altName), it.cover, url = it.url)
        }
    }

    override suspend fun metadata(connection: TrackerConnection, mediaId: String): TrackerTitle = read(connection) {
        val value = repository.getMangaInfo(LegacyTrackerBridge.mediaId(mediaId))
        require(value.id.toString() == mediaId) { "Tracker returned another work" }
        TrackerTitle(mediaId, value.name, coverUrl = value.cover, description = value.descriptionHtml, url = value.url)
    }

    override suspend fun libraryPage(connection: TrackerConnection, cursor: String?): TrackerLibraryPage = read(connection) {
        repository.readRawLibraryPage(cursor).also { page ->
            require(page.entries.all { it.reference.owner == connection.owner }) { "Tracker returned another account" }
        }
    }

    override suspend fun entry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry? = read(connection) {
        repository.readRawEntry(LegacyTrackerBridge.mediaId(mediaId)).also {
            require(it == null || it.reference.owner == connection.owner && it.reference.mediaId == mediaId) {
                "Tracker returned another account or work"
            }
        }
    }

    override suspend fun patch(connection: TrackerConnection, mediaId: String, patch: TrackerPatch,
        allowCreate: Boolean): TrackerRemoteEntry {
        checkConnection(connection)
        if (!repository.supportsRawWrites) throw UnsupportedOperationException(
            "This Beta provider lane is read-only; add the title with its tracker first")
        return read(connection) {
            val before = entry(connection, mediaId)
            require(allowCreate || before != null) { "This title is not on your tracker list" }
            repository.mutateRawEntry(before?.reference ?: TrackerReference(connection.owner, mediaId),
                patch, allowCreate).also {
                require(it.reference.owner == connection.owner && it.reference.mediaId == mediaId) {
                    "Tracker returned another account or work"
                }
            }
        }
    }
}

/** The legacy numeric ID is device-local lookup data; it is never a replacement for an opaque owner. */
internal data class LegacyRawOwnerBinding(val owner: TrackerOwner, val localAccountId: Long, val revision: Long) {
    fun matches(connection: TrackerConnection, accountId: Long?, sessionRevision: Long): Boolean =
        owner == connection.owner && revision == connection.revision && revision == sessionRevision &&
            localAccountId == accountId
}
