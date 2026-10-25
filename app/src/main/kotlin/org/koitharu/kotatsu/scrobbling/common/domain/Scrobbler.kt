package org.koitharu.kotatsu.scrobbling.common.domain

import androidx.annotation.FloatRange
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.scrobbling.common.data.TrackerLinkRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.scrobbling.common.data.accountSession
import org.koitharu.kotatsu.scrobbling.common.data.withSession
import org.koitharu.kotatsu.scrobbling.common.data.withTrackerRequestGuard
import org.koitharu.kotatsu.scrobbling.common.data.requestSession
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
abstract class Scrobbler(
    protected val db: MangaDatabase,
    val scrobblerService: ScrobblerService,
    val trackerRepository: ScrobblerRepository,
    private val mangaRepositoryFactory: MangaRepository.Factory,
) {

    private val infoCache = ConcurrentHashMap<Long, ScrobblerMangaInfo>()
    protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

    val user: Flow<ScrobblerUser> = flow {
        trackerRepository.cachedUser?.let {
            emit(it)
        }
        runCatchingCancellable {
            trackerRepository.loadUser()
        }.onSuccess {
            emit(it)
        }.onFailure {
            it.printStackTraceDebug()
        }
    }

    fun remoteStatus(status: String): String? = when (status) {
        "PLANNING" -> statuses[ScrobblingStatus.PLANNED]
        "CURRENT" -> statuses[ScrobblingStatus.READING]
        "REPEATING" -> statuses[ScrobblingStatus.RE_READING] ?: statuses[ScrobblingStatus.READING]
        "COMPLETED" -> statuses[ScrobblingStatus.COMPLETED]
        "PAUSED" -> statuses[ScrobblingStatus.ON_HOLD]
        "DROPPED" -> statuses[ScrobblingStatus.DROPPED]
        else -> status
    }

    val isEnabled: Boolean
        get() = trackerRepository.isAuthorized

    suspend fun authorize(authCode: String): ScrobblerUser {
        trackerRepository.authorize(authCode)
        return trackerRepository.loadUser()
    }

    fun logout() {
        trackerRepository.logout()
    }

    suspend fun <T> withAccount(block: suspend () -> T): T {
        val session = trackerRepository.accountSession()
        return trackerRepository.storage.withSession(session) { block() }
    }

    suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> = withAccount {
        trackerRepository.findManga(query, offset)
    }

    suspend fun linkManga(mangaId: Long, targetId: Long, allowCreate: Boolean = false): Boolean = withAccount {
        trackerRepository.storage.mutationMutex.withLock {
            val adopted = withContext(TrackerLinkRequest(mangaId, targetId)) {
                trackerRepository.createRate(mangaId, targetId, allowCreate)
            }
            trackerRepository.storage.progress.invalidate(requireNotNull(trackerRepository.storage.requestSession().accountId), mangaId)
            trackerRepository.storage.invalidateLibrary(requireNotNull(trackerRepository.storage.requestSession().accountId))
            adopted
        }
    }

    suspend fun scrobble(manga: Manga, chapterId: Long) {
        if (!isEnabled) return
        withAccount { trackerRepository.storage.mutationMutex.withLock { scrobbleWithAccount(manga, chapterId) } }
    }

    private suspend fun scrobbleWithAccount(manga: Manga, chapterId: Long) {
        val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id,
            trackerRepository.storage.requestSession().accountId) ?: return
        val owner = outgoingRawOwner(entity.mangaId)
        if (!outgoingLinkCurrent(entity, owner)) return
        var chapters = manga.chapters
        if (chapters.isNullOrEmpty()) {
            chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
        }
        requireNotNull(chapters)
        val chapter = checkNotNull(chapters.findById(chapterId)) {
            "Chapter $chapterId not found in this manga"
        }
        val number = if (chapter.number > 0f) {
            chapter.number.toInt()
        } else {
            chapters = chapters.filter { x -> x.branch == chapter.branch }
            chapters.indexOf(chapter) + 1
        }
        withOutgoingLinkGuard(entity, owner) { trackerRepository.updateRate(entity.id, entity.mangaId, number) }

    }

    suspend fun syncProgress(pending: org.koitharu.kotatsu.scrobbling.common.data.PendingTrackerProgress): Boolean {
        if (!isEnabled) return false
        val session = trackerRepository.accountSession()
        if (session.accountId != pending.accountId) return false
        return trackerRepository.storage.withSession(session) {
            trackerRepository.storage.mutationMutex.withLock {
                val link = db.getScrobblingDao().find(scrobblerService.id, pending.mangaId, pending.accountId)
                if (link?.targetId != pending.targetId || link.id != pending.rateId) return@withLock false
                val owner = outgoingRawOwner(link.mangaId)
                if (!outgoingLinkCurrent(link, owner)) return@withLock false
                withOutgoingLinkGuard(link, owner) { trackerRepository.updateRate(link.id, link.mangaId, pending.chapter) }
                true
            }
        }
    }

    private suspend fun outgoingRawOwner(mangaId: Long): org.koitharu.kotatsu.scrobbling.v2.TrackerOwner? =
        if (db.getTrackerLinkV2Dao().providerManga(scrobblerService.name, mangaId).isEmpty()) null
        else trackerRepository.readRawOwner().also { require(it.provider == scrobblerService.name) }

    private suspend fun withOutgoingLinkGuard(entity: ScrobblingEntity,
        owner: org.koitharu.kotatsu.scrobbling.v2.TrackerOwner?, block: suspend () -> Unit) {
        check(outgoingLinkCurrent(entity, owner)) {
            "Tracker link changed; review its source edition before syncing progress"
        }
        if (owner == null) block() // Preserve the original numeric lane's authentication/retry behavior.
        else withTrackerRequestGuard({ check(outgoingLinkCurrent(entity, owner)) {
            "Tracker link changed; review its source edition before syncing progress"
        } }, block)
    }

    private suspend fun outgoingLinkCurrent(entity: ScrobblingEntity,
        owner: org.koitharu.kotatsu.scrobbling.v2.TrackerOwner?): Boolean {
        val session = trackerRepository.storage.requestSession()
        val latest = db.getScrobblingDao().find(scrobblerService.id, entity.mangaId, entity.accountId)
        if (session.accountId != entity.accountId || latest?.id != entity.id || latest.targetId != entity.targetId) return false
        val links = db.getTrackerLinkV2Dao().providerManga(scrobblerService.name, entity.mangaId)
        if (links.isEmpty()) return true
        if (owner == null) return false // A v2 correction arrived after the original session snapshot.
        val current = links.singleOrNull { it.accountId == owner.accountId }
        return org.koitharu.kotatsu.scrobbling.v2.LegacyOutgoingLinkPolicy.allows(current, entity,
            db.getMangaDao().find(entity.mangaId)?.manga)
    }

    suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
        if (!isEnabled) return null
        return withAccount {
            db.getScrobblingDao().find(scrobblerService.id, mangaId, trackerRepository.storage.requestSession().accountId)?.toScrobblingInfo()
        }
    }

    suspend fun updateScrobblingInfo(mangaId: Long, rating: Float, status: ScrobblingStatus?, comment: String?) =
        withAccount { trackerRepository.storage.mutationMutex.withLock {
            updateScrobblingInfoImpl(mangaId, rating, status, comment)
            trackerRepository.storage.invalidateLibrary(requireNotNull(trackerRepository.storage.requestSession().accountId))
        } }

    suspend fun updateStatusOnly(mangaId: Long, status: ScrobblingStatus) = withAccount {
        trackerRepository.storage.mutationMutex.withLock {
            val owner = requireNotNull(trackerRepository.storage.requestSession().accountId)
            val link = db.getScrobblingDao().find(scrobblerService.id, mangaId, owner)
                ?: throw IllegalStateException("Confirm this tracker link before changing status")
            val remote = requireNotNull(statuses[status]) { "This tracker does not support that status" }
            trackerRepository.updateStatus(link.id, mangaId, remote)
            trackerRepository.storage.invalidateLibrary(owner)
        }
    }

    protected abstract suspend fun updateScrobblingInfoImpl(
        mangaId: Long,
        @FloatRange(from = 0.0, to = 1.0) rating: Float,
        status: ScrobblingStatus?,
        comment: String?,
    )

    fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> {
        return trackerRepository.storage.sessionChanges.flatMapLatest {
            val owner = trackerRepository.cachedUser?.id
            if (!isEnabled || owner == null) flowOf(null)
            else db.getScrobblingDao().observe(scrobblerService.id, mangaId, owner).map { it?.toScrobblingInfo() }
        }
    }

    fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> {
        return trackerRepository.storage.sessionChanges.flatMapLatest {
            val owner = trackerRepository.cachedUser?.id
            if (!isEnabled || owner == null) flowOf(emptyList<ScrobblingInfo>())
            else db.getScrobblingDao().observeAll(scrobblerService.id, owner).mapLatest { entities ->
                coroutineScope {
                    entities.map {
                        async {
                            it.toScrobblingInfo()
                        }
                    }.awaitAll()
                }.filterNotNull()
            }
        }
    }

    suspend fun unregisterScrobbling(mangaId: Long) {
        withAccount { trackerRepository.storage.mutationMutex.withLock {
            trackerRepository.unregister(mangaId)
            trackerRepository.storage.progress.invalidate(requireNotNull(trackerRepository.storage.requestSession().accountId), mangaId, manual = true)
            trackerRepository.storage.invalidateLibrary(requireNotNull(trackerRepository.storage.requestSession().accountId))
        } }
    }

    protected suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
        return withAccount { trackerRepository.getMangaInfo(id) }
    }

    private suspend fun ScrobblingEntity.toScrobblingInfo(): ScrobblingInfo? {
        if (!isEnabled || trackerRepository.cachedUser?.id != accountId) return null
        val mangaInfo = infoCache[targetId] ?: run {
            runCatchingCancellable {
                getMangaInfo(targetId)
            }.onFailure {
                it.printStackTraceDebug()
            }.onSuccess {
                infoCache.put(targetId, it)
            }.getOrNull() ?: return null
        }
        if (!isEnabled || trackerRepository.cachedUser?.id != accountId) return null
        return ScrobblingInfo(
            scrobbler = scrobblerService,
            mangaId = mangaId,
            targetId = targetId,
            status = statuses.findKeyByValue(status),
            chapter = chapter,
            comment = comment,
            rating = rating,
            title = mangaInfo.name,
            coverUrl = mangaInfo.cover,
            description = mangaInfo.descriptionHtml.parseAsHtml().sanitize(),
            externalUrl = mangaInfo.url,
        )
    }
}

suspend fun Scrobbler.tryScrobble(manga: Manga, chapterId: Long): Boolean {
    return runCatchingCancellable {
        scrobble(manga, chapterId)
    }.onFailure {
        it.printStackTraceDebug()
    }.isSuccess
}
