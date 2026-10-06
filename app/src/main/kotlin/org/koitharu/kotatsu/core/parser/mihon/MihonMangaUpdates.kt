package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import kotlin.coroutines.Continuation

/** Uses custom 1.6 updates while retaining the older details/chapter fallback behavior. */
internal class MihonMangaUpdates(
    private val source: Source,
    private val mapFailure: (Throwable) -> Throwable,
) {

    private val usesCombinedUpdate = source.javaClass.getMethod(
        "getMangaUpdate",
        SManga::class.java,
        List::class.java,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Continuation::class.java,
    ).declaringClass.let { owner ->
        when (owner) {
            Source::class.java, HttpSource::class.java -> false
            else -> {
                // Kotlin's compatibility forwarders are ordinary JVM methods, but are not declared
                // in the class's Kotlin metadata. Do not mistake them for a source implementation.
                val metadata = owner.getAnnotation(Metadata::class.java)
                metadata == null || "getMangaUpdate" in metadata.data2
            }
        }
    }

    private val updateMutex = MultiMutex<String>()

    suspend fun load(seed: SManga): SMangaUpdate {
        val seedUrl = runCatching { seed.url }.getOrDefault("")
        val seedTitle = runCatching { seed.title }.getOrDefault("")
        val seedThumbnail = runCatching { seed.thumbnail_url }.getOrNull()
        if (usesCombinedUpdate) {
            // Details refreshes and page-cache misses can request the same title simultaneously.
            return updateMutex.withLock(seedUrl) {
                runCatchingCancellable {
                    source.getMangaUpdate(seed, emptyList(), fetchDetails = true, fetchChapters = true)
                }.getOrElse { throw mapFailure(it) }.also { update ->
                    update.manga.fillMissingDetails(seedUrl, seedTitle, seedThumbnail)
                }
            }
        }

        val details = runCatchingCancellable { source.getMangaDetails(seed) }.getOrElse { error ->
            when (val mapped = mapFailure(error)) {
                is AuthRequiredException -> throw mapped
                else -> seed
            }
        }
        details.fillMissingDetails(seedUrl, seedTitle, seedThumbnail)
        return SMangaUpdate(details, loadLegacyChapters(seed, details))
    }

    private suspend fun loadLegacyChapters(seed: SManga, details: SManga): List<SChapter> {
        val candidates = buildList {
            if (details !== seed) add(details)
            add(seed)
        }
        var hadSuccessfulLoad = false
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val result = runCatchingCancellable { source.getChapterList(candidate) }
            val chapters = result.getOrNull()
            if (chapters != null) {
                hadSuccessfulLoad = true
                if (chapters.isNotEmpty()) return chapters
            } else {
                lastError = result.exceptionOrNull()
            }
        }
        if (hadSuccessfulLoad) return emptyList()
        throw mapFailure(lastError ?: IllegalStateException("Unable to load chapters"))
    }

    private fun SManga.fillMissingDetails(seedUrl: String, seedTitle: String, seedThumbnail: String?) {
        if (runCatching { url }.getOrDefault("").isBlank() && seedUrl.isNotBlank()) url = seedUrl
        if (runCatching { title }.getOrDefault("").isBlank() && seedTitle.isNotBlank()) title = seedTitle
        if (runCatching { thumbnail_url }.getOrNull().isNullOrBlank() && !seedThumbnail.isNullOrBlank()) {
            thumbnail_url = seedThumbnail
        }
    }
}
