package org.koitharu.kotatsu.community.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CommunityScoreCache(private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    @Volatile private var scores: Map<String, CommunitySourceScore> = emptyMap()
    @Volatile private var loadedAt: Long? = null

    suspend fun refresh(force: Boolean, fetch: suspend () -> Map<String, CommunitySourceScore>): Map<String, CommunitySourceScore> =
        mutex.withLock {
            val lastLoad = loadedAt
            if (!force && lastLoad != null && now() - lastLoad in 0 until CACHE_MS) return@withLock scores
            try {
                scores = fetch()
                loadedAt = now()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Optional ranking must never make the source catalog unavailable.
            }
            scores
        }

    fun get(source: String): CommunitySourceScore? = scores[source]

    companion object {
        private const val CACHE_MS = 24 * 60 * 60 * 1000L
    }
}
