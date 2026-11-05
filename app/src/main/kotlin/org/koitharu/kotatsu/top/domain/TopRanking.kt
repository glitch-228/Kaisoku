package org.koitharu.kotatsu.top.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals

enum class TopWindow(@StringRes val titleRes: Int) {
    DAY(R.string.top_today),
    WEEK(R.string.top_this_week),
    MONTH(R.string.top_this_month),
}

data class TopFilter(val type: ContentType? = null, val genre: String? = null)

/** A source's own idea of what is popular, and how far it is from what was asked. */
data class WeightedOrder(val order: SortOrder, val weight: Double)

/** One ranked answer: best first, and how much it counts for in the final list. */
class RankedList(val manga: List<Manga>, val weight: Double)

/**
 * No server knows what is popular across manga sources this week, but the sources know about themselves,
 * so charts are built by asking each of them and combining the answers.
 */
object TopRanking {

    private const val RANK_DAMPING = 30.0

    private val NOISE_TAGS = setOf(
        "adaptation", "web comic", "webcomic", "webtoon", "webtoons", "long strip", "full color", "full colour",
        "official colored", "manga", "manhwa", "manhua", "comic", "comics", "oneshot", "one shot", "4 koma", "4-koma",
        "doujinshi", "anthology", "award winning", "ongoing", "completed", "shounen", "shonen", "shoujo", "shojo",
        "seinen", "josei", "sexual violence", "gore",
    )

    /** Different sources' spelling of one genre, once a demographic marker like `shounen(b)` is gone. */
    private val TAG_ALIASES = mapOf(
        "school" to "school life",
        "sci fi" to "sci-fi",
        "science fiction" to "sci-fi",
        "scifi" to "sci-fi",
        "slice-of-life" to "slice of life",
        "martial art" to "martial arts",
        "super power" to "superpower",
        "reincarnated" to "reincarnation",
        "rom-com" to "romance",
    )

    private val ADULT_TAGS = setOf(
        "adult", "adults", "adulte", "adultes", "adulto", "adultos", "hentai", "smut", "ecchi", "mature", "erotica",
        "erotic", "erotique", "erotico", "érotique", "erótico", "nsfw", "18", "18plus", "adult18", "r18",
        "porn", "porno", "pornographic", "pornhwa", "pornographie", "sexual", "xxx",
    )

    /** The chart a source can offer for a window. A nearby period counts for less, all-time least. */
    fun orderFor(window: TopWindow, available: Set<SortOrder>): WeightedOrder? {
        val preferences = when (window) {
            TopWindow.DAY -> listOf(
                WeightedOrder(SortOrder.POPULARITY_TODAY, 1.0),
                WeightedOrder(SortOrder.POPULARITY_HOUR, 0.9),
                WeightedOrder(SortOrder.POPULARITY_WEEK, 0.6),
                WeightedOrder(SortOrder.POPULARITY_MONTH, 0.4),
                WeightedOrder(SortOrder.POPULARITY, 0.25),
            )

            TopWindow.WEEK -> listOf(
                WeightedOrder(SortOrder.POPULARITY_WEEK, 1.0),
                WeightedOrder(SortOrder.POPULARITY_TODAY, 0.8),
                WeightedOrder(SortOrder.POPULARITY_MONTH, 0.8),
                WeightedOrder(SortOrder.POPULARITY_YEAR, 0.4),
                WeightedOrder(SortOrder.POPULARITY, 0.3),
            )

            TopWindow.MONTH -> listOf(
                WeightedOrder(SortOrder.POPULARITY_MONTH, 1.0),
                WeightedOrder(SortOrder.POPULARITY_WEEK, 0.8),
                WeightedOrder(SortOrder.POPULARITY_YEAR, 0.6),
                WeightedOrder(SortOrder.POPULARITY, 0.35),
            )
        }
        return preferences.firstOrNull { it.order in available }
    }

    /** The same work on two sources is one entry, whatever punctuation each gives its title. */
    fun titleKey(title: String): String {
        val key = CharArray(title.length)
        var size = 0
        for (char in title) {
            when {
                char in 'a'..'z' || char in '0'..'9' -> key[size++] = char
                char in 'A'..'Z' -> key[size++] = char + ('a' - 'A')
                char.code < 128 -> Unit
                else -> return title.lowercase().filter { it.isLetterOrDigit() }
            }
        }
        return String(key, 0, size)
    }

    /**
     * Reciprocal rank fusion: a work scores for each list it is on, by how high. Absolute numbers from
     * different sites cannot be compared, while ranks can, and appearing on several at once is exactly
     * the agreement a chart should reward.
     */
    fun fuse(lists: List<RankedList>, hidden: Set<String> = emptySet()): List<Manga> {
        class Entry(val manga: Manga, var score: Double)

        val entries = LinkedHashMap<String, Entry>()
        val adult = HashSet<String>()
        // Heaviest first, so a merged entry opens on the source that answered the question most exactly
        for (list in lists.sortedByDescending { it.weight }) {
            list.manga.forEachIndexed { rank, manga ->
                val key = titleKey(manga.title)
                // One site listing no tags for it says nothing, while another saying it is adult settles it
                if (isAdult(manga)) adult += key
                val entry = entries.getOrPut(key) { Entry(manga, 0.0) }
                entry.score += list.weight / (RANK_DAMPING + rank)
            }
        }
        entries.keys.removeAll(adult)
        entries.keys.removeAll(hidden)
        return entries.values.sortedByDescending { it.score }.map { it.manga }
    }

    /** Whether a manga is adult, as far as it can be told from the manga. Many sources only say so in the tags. */
    fun isAdult(manga: Manga): Boolean =
        manga.contentRating == ContentRating.ADULT || manga.tags.any { isAdultTag(it.title) }

    fun isAdultTag(tag: String): Boolean =
        titleKey(tag) in ADULT_TAGS || titleKey(tag.substringBefore('(')) in ADULT_TAGS

    /** One genre however a source spells it, or null if a tag says nothing about what a manga is like. */
    fun canonicalTag(title: String): String? {
        val plain = title.trim().lowercase().substringBefore('(').trim()
        val tag = TAG_ALIASES[plain] ?: plain
        return tag.takeUnless { it.isEmpty() || it in NOISE_TAGS || isAdultTag(it) }
    }

    /** How a genre reads as a heading: `slice of life` is Slice of Life. */
    fun genreTitle(tag: String): String = tag.trim().split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }

    /**
     * A source's own tag for a genre. The same genre under any spelling comes first, since going straight by
     * how alike two words are finds Fiction when asked for Action.
     */
    fun findTag(available: Collection<MangaTag>, genre: String, threshold: Float): MangaTag? {
        val canonical = canonicalTag(genre)
        return available.find { canonical != null && canonicalTag(it.title) == canonical }
            ?: available.find { it.title.almostEquals(genre, threshold) }
    }
}
