package org.koitharu.kotatsu.scrobbling.common.data

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser

interface ScrobblerRepository {

    val supportsRawWrites: Boolean get() = false

    /** Explicit reviewed raw mutations; numeric caches/links and outgoing progress are untouched. */
    suspend fun mutateRawEntry(
        expected: org.koitharu.kotatsu.scrobbling.v2.TrackerReference,
        patch: org.koitharu.kotatsu.scrobbling.v2.TrackerPatch = org.koitharu.kotatsu.scrobbling.v2.TrackerPatch(),
        allowCreate: Boolean = false,
    ): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry {
        throw UnsupportedOperationException("This tracker does not support lossless mutations")
    }

    /** Verified public identity for lossless reads, independent of legacy numeric projections. */
    suspend fun readRawOwner(): org.koitharu.kotatsu.scrobbling.v2.TrackerOwner {
        val session = accountSession()
        val user = requireNotNull(cachedUser) { "Tracker account is not verified" }
        storage.checkSession(session)
        require(user.id == session.accountId) { "Tracker account changed" }
        return org.koitharu.kotatsu.scrobbling.v2.TrackerOwner(user.service.name, user.id.toString())
    }

    /** Read-only pages; never refreshes legacy links, caches or outgoing progress workers. */
    suspend fun readRawLibraryPage(cursor: String? = null): org.koitharu.kotatsu.scrobbling.v2.TrackerLibraryPage {
        throw UnsupportedOperationException("This tracker does not support lossless library pages")
    }

    /** Explicit lossless read lane; never refreshes local links or schedules progress workers. */
    suspend fun readRawEntry(mediaId: Long): org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry? {
        throw UnsupportedOperationException("This tracker does not support incoming progress")
    }

    suspend fun fetchLibrary(): List<org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry>

    val storage: ScrobblerStorage

    val oauthUrl: String

    val isAuthorized: Boolean

    val cachedUser: ScrobblerUser?

    suspend fun authorize(code: String?)

    suspend fun loadUser(): ScrobblerUser

    fun logout()

    suspend fun unregister(mangaId: Long)

    suspend fun findManga(query: String, offset: Int): List<ScrobblerManga>

    suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo

    /** @return true when an existing remote entry was adopted without overwriting it. */
    suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean = false): Boolean

    suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int)

    /** Field-preserving status updates must never reuse the rating/comment editor mutation. */
    suspend fun updateStatus(rateId: Int, mangaId: Long, status: String) {
        throw UnsupportedOperationException("This tracker does not support a status-only update")
    }

    suspend fun updateRate(
        rateId: Int,
        mangaId: Long,
        rating: Float,
        status: String?,
        comment: String?,
        setStartDate: Boolean,
    )
}
