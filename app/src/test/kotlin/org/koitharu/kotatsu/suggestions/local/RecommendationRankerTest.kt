package org.koitharu.kotatsu.suggestions.local

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

class RecommendationRankerTest {
    private val now = 1_900_000_000_000L

    @Test fun favoriteGenresOutweighIncidentalTitleWords() {
        val favorite = title(1, "Old voyage", setOf("Adventure"))
        val candidates = listOf(title(2, "Old voyage two", setOf("Romance")), title(3, "New journey", setOf("Adventure")))
        assertEquals(3L, rank(candidates, listOf(TasteTitle(favorite, true, now)), 0).first().manga.id)
    }

    @Test fun favoriteCategoriesCountOneTitleOnce() {
        val favorite = TasteTitle(title(1, "Journey", setOf("Adventure")), true, now)
        val candidates = listOf(title(2, "A", setOf("Adventure")), title(3, "B", setOf("Drama")))
        assertEquals(rank(candidates, listOf(favorite), 0), rank(candidates, listOf(favorite, favorite, favorite), 0))
    }

    @Test fun unstartedFavoriteReasonsDoNotClaimThatItWasRead() {
        val result = rank(listOf(title(2, "Journey two", setOf("Adventure"))),
            listOf(TasteTitle(title(1, "Journey", setOf("Adventure")), true, 0)), 0)
        assertEquals(RecommendationReason.BECAUSE_FAVORITE, result.single().reason)
    }

    @Test fun RecentHistoryOutweighsHistoryFromYearsAgo() {
        val taste = listOf(TasteTitle(title(1, "A", setOf("Drama")), false, now),
            TasteTitle(title(2, "B", setOf("Romance")), false, now - 720 * 86400000L))
        val result = rank(listOf(title(3, "C", setOf("Drama")), title(4, "D", setOf("Romance"))), taste, 0)
        assertEquals(3L, result.first().manga.id)
    }

    @Test fun ReadingTimeIsCappedAndCannotOverflow() {
        val manga = title(1, "A", setOf("Drama"))
        val candidates = listOf(title(2, "B", setOf("Drama")), title(3, "C", setOf("Romance")))
        assertEquals(rank(candidates, listOf(TasteTitle(manga, false, now, Long.MAX_VALUE)), 0),
            rank(candidates, listOf(TasteTitle(manga, false, now, 36_000_000)), 0))
    }

    @Test fun MissingMetadataProducesZeroSimilarityRatherThanNaN() {
        val result = rank(listOf(title(2, "Unrelated")), listOf(TasteTitle(title(1, "Different"), true, now)), 0)
        assertEquals(0.0, result.single().score, 0.00001)
        assertTrue(result.single().confidence.isFinite())
    }

    @Test fun ColdStartOrderingIsStableForEverySliderPosition() {
        val candidates = (1..50).map { title(it.toLong(), "Title $it", setOf("Genre ${it % 4}")) }
        for (slider in listOf(0, 25, 50, 75, 100)) {
            val first = rank(candidates, emptyList(), slider)
            assertEquals(first, rank(candidates.reversed(), emptyList(), slider))
            assertEquals(first.size, first.map { it.key }.distinct().size)
            assertTrue(first.all { it.score.isFinite() && it.score in 0.0..1.0 })
        }
    }

    @Test fun DenseTasteProfileDoesNotInventNovelSimilarity() {
        val favorite = title(1, "Journey", setOf("Adventure"))
        val novel = title(2, "Journey", setOf("Adventure")).copy(source = LnReaderMangaSource("fixture", "Novel"))
        val result = rank(listOf(novel), listOf(TasteTitle(favorite, true, now)), 0)
        assertEquals(0.0, result.single().score, 0.0)
    }

    @Test fun SourceRelationshipsReceiveOnlyABoundedBonus() {
        val candidate = Recommendation(RecommendationTarget.SourceTitle(title(2, "Different")), provenance = RecommendationProvenance.RELATED)
        val result = RecommendationRanker.rank(listOf(candidate), emptyList(), RecommendationRequest.Details(title(1, "Seed")), 0, now)
        assertEquals(.08, result.single().score, .00001)
        assertEquals(RecommendationReason.RELATED, result.single().reason)
    }

    @Test fun CjkAndRtlNormalizationRetainsDistinctiveTerms() {
        assertEquals("我的狐仙老婆", RecommendationRanker.normalize(" 我的狐仙老婆 "))
        assertEquals("حكاية الليل", RecommendationRanker.normalize("حكاية—الليل"))
        val seed = title(1, "我的狐仙老婆")
        val result = RecommendationRanker.rank(listOf(title(2, "我的狐仙续篇"), title(3, "陌生故事")).map { Recommendation(RecommendationTarget.SourceTitle(it)) },
            emptyList(), RecommendationRequest.Details(seed), 0, now)
        assertEquals(2L, result.first().manga.id)
    }

    @Test fun ExplorationIncludesDifferentGenresWhileSimilarRemainsClose() {
        val taste = listOf(TasteTitle(title(1, "Favorite", setOf("Adventure")), true, now))
        val candidates = (2..21).map { title(it.toLong(), "Journey $it", setOf("Adventure")) } +
            (30..49).map { title(it.toLong(), "Romance $it", setOf("Romance")) }
        val similar = rank(candidates, taste, 0).take(10)
        val wild = rank(candidates, taste, 100).take(10)
        assertTrue(similar.all { it.manga.tags.single().title == "Adventure" })
        assertTrue(wild.any { it.manga.tags.single().title == "Romance" })
    }

    @Test fun EqualNamesDoNotMergeDifferentSourceIdentitiesOrEditions() {
        val source2 = object : org.koitharu.kotatsu.parsers.model.MangaSource { override val name = "other" }
        val candidates = listOf(title(1, "Same"), title(2, "Same"), title(1, "Same").copy(source = source2))
        assertEquals(3, rank(candidates, emptyList(), 50).size)
    }

    @Test fun ProfileAndCandidateInputsAreNeverMutated() {
        val favorites = listOf(TasteTitle(title(1, "A", setOf("Drama")), true, now))
        val candidates = listOf(title(2, "B", setOf("Drama")))
        val before = candidates.toList()
        rank(candidates, favorites, 50)
        assertEquals(before, candidates)
        assertTrue(candidates.single().chapters == null)
    }

    @Test fun TitlesOpenedBarelyReadAndLeftForWeeksNoLongerShapeTheProfile() {
        val dropped = TasteTitle(title(1, "Dropped", setOf("Horror")), false, now - 30 * 86400000L, progress = 0.03f)
        val kept = TasteTitle(title(2, "Kept", setOf("Drama")), false, now - 30 * 86400000L, progress = 0.8f)
        val candidates = listOf(title(3, "H", setOf("Horror")), title(4, "D", setOf("Drama")))
        val result = rank(candidates, listOf(dropped, kept), 0)
        assertEquals(4L, result.first().manga.id)
        assertEquals(0.0, result.single { it.manga.id == 3L }.score, 0.0)
    }

    @Test fun DroppedRequiresKnownProgressWeeksOfSilenceAndNoFavouriteMark() {
        val old = now - 30 * 86400000L
        fun dropped(favorite: Boolean = false, lastRead: Long = old, progress: Float = 0.05f) =
            RecommendationRanker.isDropped(TasteTitle(title(1, "A"), favorite, lastRead, progress = progress), now)
        assertTrue(dropped())
        assertFalse(dropped(favorite = true))
        assertFalse(dropped(lastRead = now - 5 * 86400000L))
        assertFalse(dropped(progress = -1f))
        assertFalse(dropped(progress = 0.5f))
        assertFalse(dropped(lastRead = 0))
    }

    @Test fun ReadingTimeIsEstimatedFromProgressWhenStatisticsAreOff() {
        val recorded = TasteTitle(title(1, "A"), false, now, durationMillis = 90_000)
        assertEquals(90_000L, RecommendationRanker.engagementMillis(recorded))
        val estimated = TasteTitle(title(1, "A"), false, now, progress = 0.5f, historyChapters = 10)
        assertEquals((0.5f * 10 * 25 * 20_000L).toLong(), RecommendationRanker.engagementMillis(estimated))
        assertEquals(0L, RecommendationRanker.engagementMillis(TasteTitle(title(1, "A"), false, now)))
        assertEquals(0L, RecommendationRanker.engagementMillis(TasteTitle(title(1, "A"), false, now, progress = 0.5f)))
    }

    @Test fun ALongReadWithoutStatisticsOutweighsAJustOpenedTitle() {
        val long = TasteTitle(title(1, "Long", setOf("Drama")), false, now, progress = 0.9f, historyChapters = 80)
        val opened = TasteTitle(title(2, "Opened", setOf("Romance")), false, now, progress = 0.12f, historyChapters = 1)
        val result = rank(listOf(title(3, "D", setOf("Drama")), title(4, "R", setOf("Romance"))), listOf(long, opened), 0)
        assertEquals(3L, result.first().manga.id)
    }

    @Test fun VeryOldHistoryStillCountsSomething() {
        val old = TasteTitle(title(1, "A", setOf("Drama")), false, now - 5 * 365 * 86400000L)
        val result = rank(listOf(title(2, "B", setOf("Drama"))), listOf(old), 0)
        assertTrue(result.single().score > 0.0)
    }

    private fun rank(manga: List<Manga>, taste: List<TasteTitle>, slider: Int) = RecommendationRanker.rank(
        manga.map { Recommendation(RecommendationTarget.SourceTitle(it)) }, taste, RecommendationRequest.Explore, slider, now)

    private fun title(id: Long, name: String, genres: Set<String> = emptySet()): Manga = Manga(id, name, emptySet(), "/$id", "https://fixture.invalid/$id",
        -1f, null, null, genres.mapTo(linkedSetOf()) { MangaTag(it, it, TestMangaSource) }, null, emptySet(), source = TestMangaSource)
}
