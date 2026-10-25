package org.koitharu.kotatsu.remote.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.SourceContentClass
import org.koitharu.kotatsu.core.model.SourceContentPolicy
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

/** Captured once per query: classification changes never become a post-LIMIT filter. */
data class LibraryListContentPolicy(val allowedSql: String, val nsfwSql: String) {
    companion object {
        private const val MAX_RECEIPTS = 10_000
        private const val MAX_MEMBERSHIP_CHARS = 256 * 1024
        private const val TITLE_ADULT = "(CASE WHEN (SELECT content_rating_override FROM preferences p WHERE " +
            "p.manga_id=manga.manga_id) IS NULL THEN manga.nsfw=1 ELSE " +
            "(SELECT content_rating_override FROM preferences p WHERE p.manga_id=manga.manga_id)='ADULT' END)"

        fun legacy(restricted: Boolean = false): LibraryListContentPolicy = capture(
            SourceContentPolicy(false, SourceContentClass.entries.toSet(), restricted),
            RemoteLibrarySourceRegistry.acquiredContent(),
        )

        internal fun capture(policy: SourceContentPolicy,
                             acquired: Map<Pair<Long, String>, RemoteLibraryContent>): LibraryListContentPolicy {
            require(acquired.size <= MAX_RECEIPTS) { "Too many local library acquisition identities" }
            val rows = acquired.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            fun matching(predicate: (RemoteLibraryContent) -> Boolean) = membership(
                rows.filter { predicate(it.value) }.map { it.key },
            )
            val all = matching { true }
            val macro = matching { if (policy.separateClasses) it == RemoteLibraryContent.ADULT else it != RemoteLibraryContent.SAFE }
            val disallowed = matching { content ->
                val kind = when (content) {
                    RemoteLibraryContent.SAFE -> SourceContentClass.SAFE
                    RemoteLibraryContent.ADULT -> SourceContentClass.ADULT
                    RemoteLibraryContent.MIXED, RemoteLibraryContent.UNKNOWN -> SourceContentClass.MIXED
                }
                !policy.allows(kind, content != RemoteLibraryContent.SAFE)
            }
            val allowAdult = if (policy.allows(SourceContentClass.ADULT, true)) "1" else "0"
            val allowed = if (rows.isEmpty() || (!policy.separateClasses && !policy.legacyRestricted)) "1" else
                "(NOT($all) OR CASE WHEN $TITLE_ADULT THEN $allowAdult ELSE NOT($disallowed) END)"
            return LibraryListContentPolicy(allowed, "($TITLE_ADULT OR $macro)")
        }

        private fun membership(keys: List<Pair<Long, String>>): String {
            if (keys.isEmpty()) return "0"
            var chars = 0L
            for ((_, uri) in keys) chars += uri.length + uri.count { it == '\'' } + 32L
            val expression = if (chars <= MAX_MEMBERSHIP_CHARS) keys.joinToString(
                prefix = "(manga.manga_id,manga.url) IN (VALUES ", postfix = ")",
            ) { (id, uri) -> "($id,'${uri.replace("'", "''")}')" } else keys.map { it.first }.distinct()
                .joinToString(prefix = "manga.manga_id IN (", postfix = ")")
            // An oversized URI snapshot falls back to conservative numeric identities. It may
            // hide a replaced local file with a colliding ID, but never grants a SAFE rating.
            return "(manga.source='LOCAL' AND $expression)"
        }
    }
}

@Singleton
class LibraryListContentPolicies @Inject constructor(
    private val persistence: RemoteLibraryPersistence,
    private val settings: AppSettings,
) {
    fun observe(): Flow<LibraryListContentPolicy> = flow {
        // Do not publish an initially empty registry before private receipts have been loaded.
        withContext(Dispatchers.IO) { persistence.initialize() }
        emitAll(combine(RemoteLibrarySourceRegistry.changes,
            settings.observeChanges().onStart { emit(null) }) { _, _ ->
            LibraryListContentPolicy.capture(settings.sourceContentPolicy, RemoteLibrarySourceRegistry.acquiredContent())
        })
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
}
