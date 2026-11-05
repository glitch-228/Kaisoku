package org.koitharu.kotatsu.top.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.suggestions.domain.TagsBlacklist
import org.koitharu.kotatsu.top.data.TopCache
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A list as far as it has got. [isFinal] false means more sources are still answering. */
data class TopSection(val manga: List<Manga>, val isFinal: Boolean)

/**
 * The most popular titles of the day, week or month, none of them adult whatever the other settings say.
 *
 * Built from what the reader's own enabled sources say is popular, so no new server is involved and nothing
 * about the reader leaves the phone: each source is asked for its popularity chart and the answers are combined
 * by rank. A chart is kept for a few hours.
 */
@Singleton
class TopRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val sourcesRepository: MangaSourcesRepository,
    private val repositoryFactory: MangaRepository.Factory,
    private val history: HistoryRepository,
    private val cache: TopCache,
) {

    private val sourceLocks = ConcurrentHashMap<String, Mutex>()
    private val sourcesLock = Mutex()

    @Volatile
    private var sources: Timed<List<MangaSource>>? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val blacklist: TagsBlacklist
        get() = TagsBlacklist(settings.suggestionsTagsBlacklist)

    suspend fun hasSources(): Boolean = chartSources().isNotEmpty()

    /** What the reader reads most first, then the genres everyone looks for. */
    fun getGenres(): List<String> = COMMON_GENRES

    /** Titles the reader asked not to see here again. They stay on this phone. */
    fun hide(manga: Collection<Manga>): Set<String> {
        val keys = manga.mapTo(LinkedHashSet()) { TopRanking.titleKey(it.title) }
        prefs.edit { putStringSet(KEY_HIDDEN, getHidden() + keys) }
        return keys
    }

    fun unhide(keys: Collection<String>) {
        prefs.edit { putStringSet(KEY_HIDDEN, getHidden() - keys.toSet()) }
    }

    fun clearHidden() {
        prefs.edit { remove(KEY_HIDDEN) }
    }

    fun hiddenCount(): Int = getHidden().size

    private fun getHidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, null).orEmpty()

    /** Asks for everything again, however recently it was fetched. */
    fun invalidate() {
        sources = null
    }

    fun observeTop(window: TopWindow, filter: TopFilter, force: Boolean): Flow<TopSection> =
        cachedSection("top$MAX_TOP:${window.name}:${filter.type}:${filter.genre}", MAX_AGE_MS, force) { emit ->
            val lists = ArrayList<RankedList>()
            val sourceCharts = ArrayList<SourceChart>()
            val mutex = Mutex()
            suspend fun add(list: RankedList) {
                if (list.manga.isEmpty()) return
                val partial = mutex.withLock {
                    lists += list
                    TopRanking.fuse(lists)
                }
                emit(partial)
            }
            coroutineScope {
                val semaphore = Semaphore(MAX_PARALLELISM)
                chartSources().map { source ->
                    launch {
                        semaphore.withPermit {
                            fetchSourceChart(source, window, filter)?.let { sourceChart ->
                                mutex.withLock { sourceCharts += sourceChart }
                                add(sourceChart.shown)
                            }
                        }
                    }
                }.joinAll()
                // Too few sites answered, or they all list the same titles: further down those lists, one site at
                // a time and no further than it takes, as this is requests a fuller answer would not have needed
                for (sourceChart in sourceCharts) {
                    var pages = 0
                    while (mutex.withLock { TopRanking.fuse(lists).size } < MAX_TOP && pages <= MAX_EXTRA_PAGES) {
                        val previous = sourceChart.shown
                        val hasMore = sourceChart.goFurther(filter, isFirstStep = pages == 0)
                        if (sourceChart.shown !== previous) {
                            val partial = mutex.withLock {
                                lists[lists.indexOf(previous)] = sourceChart.shown
                                TopRanking.fuse(lists)
                            }
                            emit(partial)
                        }
                        if (!hasMore) break
                        pages++
                    }
                }
            }
            mutex.withLock { TopRanking.fuse(lists).take(MAX_TOP) }
        }

    /**
     * Shows what is cached straight away; if that is missing, out of date or a refresh was asked for, then
     * fetches. A section with nothing cached fills in as sources answer, while one replacing an older list
     * does it in one go, so the list does not shrink to one source's answer and grow back. A fetch that
     * finds nothing at all keeps the older list: a bad connection should not empty the screen.
     */
    private fun cachedSection(
        key: String,
        maxAgeMs: Long,
        force: Boolean,
        fetch: suspend (emit: suspend (List<Manga>) -> Unit) -> List<Manga>,
    ): Flow<TopSection> = flow {
        val cached = cache.get(key)
        val isFresh = cached != null && System.currentTimeMillis() - cached.savedAt < maxAgeMs
        if (cached != null) {
            emit(TopSection(cached.manga.applyReaderFilters(), isFinal = isFresh && !force))
        }
        if (isFresh && !force) {
            return@flow
        }
        val showPartial = cached == null
        val partials = Channel<List<Manga>>(Channel.CONFLATED)
        val result = coroutineScope {
            val job = async {
                try {
                    fetch { partials.trySend(it) }
                } finally {
                    partials.close()
                }
            }
            for (partial in partials) {
                if (showPartial) {
                    emit(TopSection(partial.applyReaderFilters().take(MAX_TOP), isFinal = false))
                }
            }
            job.await()
        }
        if (result.isNotEmpty()) {
            cache.put(key, result)
            emit(TopSection(result.applyReaderFilters(), isFinal = true))
        } else {
            emit(TopSection(cached?.manga?.applyReaderFilters().orEmpty(), isFinal = true))
        }
    }

    /** Applied on the way out, not when saved: changing these settings takes effect without a refetch. */
    private fun List<Manga>.applyReaderFilters(): List<Manga> {
        val tagsBlacklist = blacklist
        val hidden = getHidden()
        return filter { manga ->
            !TopRanking.isAdult(manga) && !manga.source.isNsfw() && manga !in tagsBlacklist &&
                TopRanking.titleKey(manga.title) !in hidden
        }
    }

    private suspend fun fetchSourceChart(
        source: MangaSource,
        window: TopWindow,
        filter: TopFilter,
    ): SourceChart? = fetchFrom(source) { repository ->
        val order = TopRanking.orderFor(window, repository.sortOrders) ?: return@fetchFrom null
        val list = repository.getFiltered(source, order.order, filter) ?: return@fetchFrom null
        SourceChart(source, order.order, order.weight, list)
    }

    /** One site's chart. Only the top of what it answered counts at first, so no site outweighs the others. */
    private inner class SourceChart(
        private val source: MangaSource,
        private val order: SortOrder,
        private val weight: Double,
        private var fetched: List<Manga>,
    ) {

        var shown = RankedList(fetched.take(MAX_PER_SOURCE), weight)
            private set

        /**
         * Adds what was already fetched if some was left out, as that costs nothing, and the next page otherwise.
         *
         * @return whether there may be more
         */
        suspend fun goFurther(filter: TopFilter, isFirstStep: Boolean): Boolean {
            if (isFirstStep && fetched.size > shown.manga.size) {
                shown = RankedList(fetched, weight)
                return true
            }
            val next = fetchFrom(source) { repository ->
                repository.getFiltered(source, order, filter, offset = fetched.size)
            }.orEmpty()
            if (next.isEmpty()) {
                return false
            }
            fetched = fetched + next
            shown = RankedList(fetched, weight)
            return true
        }
    }

    /** One slow or blocked site must only cost the list its own entries. */
    private suspend fun <T : Any> fetchFrom(source: MangaSource, block: suspend (MangaRepository) -> T?): T? =
        sourceLocks.computeIfAbsent(source.name) { Mutex() }.withLock {
            runCatchingCancellable {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    block(repositoryFactory.create(source))
                }
            }.onFailure {
                it.printStackTraceDebug()
            }.getOrNull()
        }

    /** @return null if this source has no way of answering the filter, rather than an unfiltered list */
    private suspend fun MangaRepository.getFiltered(
        source: MangaSource,
        order: SortOrder,
        filter: TopFilter,
        offset: Int = 0,
    ): List<Manga>? {
        val tags = HashSet<MangaTag>()
        val types = HashSet<ContentType>()
        if (filter.genre != null || filter.type != null) {
            val options = getFilterOptions()
            if (filter.genre != null) {
                tags += TopRanking.findTag(options.availableTags, filter.genre, TAG_MATCH_THRESHOLD)
                    ?: return null
            }
            if (filter.type != null) {
                if (filter.type in options.availableContentTypes) {
                    types += filter.type
                } else if (!source.servesOnly(filter.type)) {
                    return null
                }
            }
        }
        return getList(offset = offset, order = order, filter = MangaListFilter(tags = tags, types = types))
    }

    /**
     * A source that cannot filter by type still answers when it only carries that type. "Manga" is the
     * parsers' default, which also covers mixed catalogues, so that alone counts as an answer for manga.
     */
    private fun MangaSource.servesOnly(type: ContentType): Boolean {
        val own = (unwrap() as? MangaParserSource)?.contentType ?: return false
        return own == type
    }

    /** The sources the reader actually reads on first: what is there is what they would open anyway. */
    private suspend fun chartSources(): List<MangaSource> = sourcesLock.withLock {
        sources?.takeIf { it.isYoungerThan(SOURCES_MAX_AGE_MS) }?.value?.let { return it }
        val enabled = sourcesRepository.getEnabledSources()
            .filterNot { it.isNsfw() || it.unwrap() == LocalMangaSource || it.unwrap() is LnReaderMangaSource }
        val used = runCatchingCancellable { history.getPopularSources(MAX_SOURCES) }.getOrDefault(emptyList())
        // By name: enabled sources come wrapped with their settings, so they never equal the plain ones from history
        val enabledByName = enabled.associateBy { it.name }
        (used.mapNotNull { enabledByName[it.name] } + enabled).distinctBy { it.name }.take(MAX_SOURCES)
            .also { sources = if (it.isEmpty()) null else Timed(it) }
    }

    private class Timed<T>(val value: T) {
        private val takenAt = System.currentTimeMillis()

        fun isYoungerThan(ms: Long) = System.currentTimeMillis() - takenAt < ms
    }

    private companion object {

        const val PREFS_NAME = "top_hidden"
        const val KEY_HIDDEN = "titles"

        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
        const val SOURCES_MAX_AGE_MS = 5 * 60 * 1000L

        /** Long enough for a site that is merely slow. A blocked one would otherwise hold up the finished list. */
        const val SOURCE_TIMEOUT_MS = 8_000L

        /** Each is a different website, so this burdens none of them, and the first answers show up sooner. */
        const val MAX_PARALLELISM = 6

        /** Every one costs a request per chart, so this is what the first open of the screen costs. */
        const val MAX_SOURCES = 8
        const val MAX_PER_SOURCE = 24
        const val MAX_TOP = 100

        /** How far down one site's chart to go for a list that is still short. */
        const val MAX_EXTRA_PAGES = 6
        const val TAG_MATCH_THRESHOLD = 0.4f

        val COMMON_GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Isekai", "Reincarnation",
            "Martial arts", "Slice of life", "School life", "Mystery", "Horror", "Psychological",
            "Supernatural", "Sci-fi", "Sports", "Historical", "Thriller", "Villainess",
        )
    }
}
