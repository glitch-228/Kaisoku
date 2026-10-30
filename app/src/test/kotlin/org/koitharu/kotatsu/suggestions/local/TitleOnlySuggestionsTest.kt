package org.koitharu.kotatsu.suggestions.local

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

class TitleOnlySuggestionsTest {
    private val seed = title(1, "Moon voyage", "Adventure")
    private val now = 1_900_000_000_000L

    @Test fun strictScoresReasonsAndOrderIgnoreAllPersonalInputsAndEverySliderPosition() {
        val candidates = listOf(card(title(2, "Sailing", "Adventure")), card(title(3, "Office", "Romance")),
            card(title(4, "Moon voyage sequel")), card(title(5, "Wanderer", "Adventure")))
        val expected = strict(candidates)
        assertEquals(setOf(2L, 4L, 5L), expected.map { it.manga.id }.toSet())
        val tastes = listOf(emptyList(), listOf(TasteTitle(title(20, "Office", "Romance"), true, now, Long.MAX_VALUE)),
            (30L..100L).map { TasteTitle(title(it, "Moon voyage", "Adventure"), it % 2 == 0L, now - it * 86400000L, 100000) })
        for (taste in tastes) for (slider in 0..100) {
            assertEquals(expected, strict(candidates.reversed(), taste, slider))
            assertTrue(expected.all { it.because == null && it.score.isFinite() && it.confidence in 0.0..1.0 })
        }
    }

    @Test fun exploreKeepsItsExactExistingAlgorithmWithTheNewFlagEnabled() {
        val candidates = (2L..25L).map { card(title(it, "Book $it", if (it % 2 == 0L) "Adventure" else "Romance")) }
        val taste = listOf(TasteTitle(seed, true, now))
        for (slider in listOf(0, 25, 50, 75, 100)) assertEquals(
            RecommendationRanker.rank(candidates, taste, RecommendationRequest.Explore, slider, now),
            RecommendationRanker.rank(candidates, taste, RecommendationRequest.Explore, slider, now, titleOnlyDetails = true),
        )
    }

    @Test fun switchingOffRestoresTheExistingPersonalContributionAndExploration() {
        val candidates = listOf(card(title(2, "Sailing", "Adventure")), card(title(3, "Office", "Romance")))
        val taste = listOf(TasteTitle(title(20, "Office", "Romance"), true, now))
        val current = RecommendationRanker.rank(candidates, taste, RecommendationRequest.Details(seed), 100, now)
        assertEquals(2, current.size)
        assertTrue(current.single { it.manga.id == 3L }.score > 0.0)
        assertEquals(listOf(2L), strict(candidates, taste, 100).map { it.manga.id })
        assertEquals(current, RecommendationRanker.rank(candidates, taste, RecommendationRequest.Details(seed), 100, now,
            titleOnlyDetails = false))
    }

    @Test fun noUnrelatedFillerIsAddedEvenForColdStartAndWild() {
        val candidates = (10L..30L).map { card(title(it, "Office $it", "Romance")) }
        assertTrue(strict(candidates, slider = 100).isEmpty())
        assertTrue(RecommendationRanker.rank(candidates, emptyList(), RecommendationRequest.Details(title(99, "Unknown")),
            100, now, titleOnlyDetails = true).isEmpty())
    }

    @Test fun onlyTheActualSeedsWitnessedRelationsCanAdmitSparseResults() {
        val unrelated = card(title(3, "Different"))
        val genuine = unrelated.copy(provenance = RecommendationProvenance.RELATED).withRelationTo(seed)
        assertEquals(RecommendationReason.TITLE_RELATION, strict(listOf(genuine)).single().reason)
        assertEquals(.08, strict(listOf(genuine)).single().score, .00001)
        assertTrue(strict(listOf(genuine.withRelationTo(title(88, "Other")).copy(
            relatedTo = setOf("source:${seed.source.name}:88")))).isEmpty())
        assertTrue(strict(listOf(unrelated.copy(provenance = RecommendationProvenance.RELATED))).isEmpty())
        assertEquals(RecommendationReason.TITLE_RELATION,
            strict(listOf(genuine.copy(provenance = RecommendationProvenance.CACHE))).single().reason)
    }

    @Test fun publicRelationWitnessRequiresTheCurrentlyEnabledConfirmedIdentity() {
        val link = RecommendationProviderLink(seed.recommendationSourceKey(), "ANILIST", "42", "MANGA")
        val candidate = card(title(3, "Different")).withRelationTo(seed, link)
        assertTrue(strict(listOf(candidate)).isEmpty())
        assertEquals(1, RecommendationRanker.rank(listOf(candidate), emptyList(), RecommendationRequest.Details(seed),
            100, now, titleOnlyDetails = true, publicRelationKeys = setOf(link.relationKey(seed))).size)
        assertTrue(RecommendationRanker.rank(listOf(candidate), emptyList(), RecommendationRequest.Details(seed),
            100, now, titleOnlyDetails = true, publicRelationKeys = setOf(link.copy(identifier = "43").relationKey(seed))).isEmpty())
    }

    @Test fun sourceRelationsSurviveDedupWithRicherMetadataAndNeverTransferByName() {
        val sparse = card(title(2, "Duplicate")).withRelationTo(seed)
        val richer = card(title(2, "Duplicate", "Drama"))
        val other = card(title(3, "Duplicate"))
        val values = RecommendationIdentity.deduplicate(listOf(richer, sparse, other))
        assertEquals(1, strict(values).size)
        assertEquals(2L, strict(values).single().manga.id)
        assertTrue(values.single { it.manga.id == 3L }.relatedTo.isEmpty())
    }

    @Test fun confirmedPublicIdentityTransfersRelationEvidenceButNotBetweenAdaptations() {
        val readable = card(title(2, "Readable")).copy(trustedIdentifiers = mapOf("ANILIST" to "42"))
        val public = card(title(42, "Public")).let { it.copy(target = RecommendationTarget.PublicTitle("ANILIST", "42",
            it.manga.copy(source = PublicRecommendationSource("ANILIST", RecommendationKind.MANGA))),
            trustedIdentifiers = mapOf("ANILIST" to "42")) }.withRelationTo(seed)
        assertEquals(setOf(seed.recommendationSourceKey()), RecommendationIdentity.deduplicate(listOf(readable, public)).single().relatedTo)
        val novel = public.copy(target = RecommendationTarget.PublicTitle("ANILIST", "42",
            public.manga.copy(source = PublicRecommendationSource("ANILIST", RecommendationKind.NOVEL))))
        assertTrue(RecommendationIdentity.deduplicate(listOf(readable, novel)).first().relatedTo.isEmpty())
    }

    @Test fun oldCachesNeverInventRelationshipEvidenceAndNewProofRoundTrips() {
        val value = card(title(2, "Different")).withRelationTo(seed)
        val json = RecommendationCodec.encode(value)
        assertEquals(value.relatedTo, RecommendationCodec.decode(json)!!.relatedTo)
        json.remove("relatedTo")
        assertTrue(RecommendationCodec.decode(json)!!.relatedTo.isEmpty())
        assertTrue(strict(listOf(RecommendationCodec.decode(json)!!)).isEmpty())
    }

    @Test fun relationshipEvidenceIsBoundedAndInvalidCacheKeysAreIgnored() {
        val value = card(title(2, "Different")).copy(relatedTo = (1L..100L).mapTo(linkedSetOf()) { "source:fixture:$it" })
        assertEquals(MAX_RECOMMENDATION_RELATION_SEEDS, RecommendationCodec.decode(RecommendationCodec.encode(value))!!.relatedTo.size)
        val json = RecommendationCodec.encode(value).put("relatedTo", org.json.JSONArray(listOf("nonsense", "source:" + "a".repeat(2000))))
        assertTrue(RecommendationCodec.decode(json)!!.relatedTo.isEmpty())
        assertEquals(MAX_RECOMMENDATION_RELATION_SEEDS, RecommendationIdentity.deduplicate((1L..100L).map {
            card(title(2, "Different")).withRelationTo(title(it, "Seed"))
        }).single().relatedTo.size)
    }

    @Test fun genresAuthorsAndAliasesGiveSeedBasedReasons() {
        assertEquals(RecommendationReason.GENRES, strict(listOf(card(title(2, "Other", "Adventure")))).single().reason)
        val authorSeed = seed.copy(tags = emptySet(), authors = setOf("Author"))
        val author = title(2, "Other").copy(authors = setOf("Author"))
        assertEquals(RecommendationReason.AUTHOR, strict(listOf(card(author)), opened = authorSeed).single().reason)
        val alias = title(2, "Other").copy(altTitles = setOf(seed.title))
        assertEquals(RecommendationReason.TITLE_TERMS, strict(listOf(card(alias))).single().reason)
    }

    @Test fun cjkRtlAndNovelSeedsUseTheirOwnMetadataAndKeepMediaSeparated() {
        for ((opened, related) in listOf("我的狐仙老婆" to "我的狐仙续篇", "حكاية الليل" to "رحلة الليل")) {
            assertEquals(2L, strict(listOf(card(title(2, related)), card(title(3, "Other"))),
                opened = title(1, opened)).single().manga.id)
        }
        val novelSource = LnReaderMangaSource("fixture", "Novel")
        val novelSeed = seed.copy(source = novelSource)
        val novel = title(2, "Moon voyage").copy(source = novelSource)
        assertEquals(listOf(2L), strict(listOf(card(seed), card(novel)), opened = novelSeed).map { it.manga.id })
        assertTrue(strict(listOf(card(novel).withRelationTo(seed))).isEmpty())
    }

    @Test fun strictRankingPreservesInputsAndItsLimit() {
        val candidates = listOf(card(seed), card(title(2, "Related", "Adventure")), card(title(3, "Other", "Adventure")))
        val before = candidates.toList()
        assertEquals(1, RecommendationRanker.rank(candidates, emptyList(), RecommendationRequest.Details(seed), 100, now,
            limit = 1, titleOnlyDetails = true).size)
        assertEquals(before, candidates)
        assertTrue(candidates.all { it.manga.chapters == null })
    }

    private fun strict(candidates: List<Recommendation>, taste: List<TasteTitle> = emptyList(), slider: Int = 50,
        opened: Manga = seed) = RecommendationRanker.rank(candidates, taste, RecommendationRequest.Details(opened),
        slider, now, titleOnlyDetails = true)

    private fun card(manga: Manga) = Recommendation(RecommendationTarget.SourceTitle(manga))
    private fun title(id: Long, name: String, vararg genres: String) = Manga(id, name, emptySet(), "/$id",
        "https://fixture.invalid/$id", -1f, null, null, genres.mapTo(linkedSetOf()) { MangaTag(it, it, TestMangaSource) },
        null, emptySet(), source = TestMangaSource)
}
