package org.koitharu.kotatsu.suggestions.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag

class RecommendationCodecTest {
    @Test fun exactReleaseProofRoundTripsAndOldPayloadsNeverInventAReaderBoundary() {
        val value = ChapterBaseline(null, 134, "digest", 134f, 99, 100, 34, 50,
            (101L..134L).toSet(), true, "generation", 1, "manifest")
        assertEquals(value, RecommendationCodec.decodeBaseline(RecommendationCodec.encodeBaseline(value)))
        val old = JSONObject(RecommendationCodec.encodeBaseline(value)).apply {
            remove("releaseIds"); remove("readerBoundary"); remove("exactGeneration"); remove("releaseIdentityVersion")
        }
        val decoded = RecommendationCodec.decodeBaseline(old.toString())!!
        assertFalse(decoded.confirmedReadingBoundary)
        assertTrue(decoded.releaseChapterIds.isEmpty())
        assertNull(decoded.exactGeneration)
        assertEquals(0, decoded.releaseIdentityVersion)
    }
    @Test fun cachePreservesExtensionIdentitiesAndMetadataWithoutChapterLists() {
        for (source in listOf(MangaParserSource.entries.first(), MihonMangaSource("fixture.package", 42), LnReaderMangaSource("fixture", "Novel"))) {
            val title = Manga(12, "我的狐仙老婆", setOf("حكاية الليل"), "/12", "https://fixture.invalid/12", -1f, null, null,
                setOf(MangaTag("Adventure", "adventure", source)), null, setOf("Author"), source = source)
            val value = Recommendation(RecommendationTarget.SourceTitle(title), trustedIdentifiers = mapOf("ANILIST" to "123"))
            val restored = RecommendationCodec.decode(RecommendationCodec.encode(value))!!
            assertEquals(source.name, restored.manga.source.name)
            assertEquals(title.id, restored.manga.id)
            assertEquals(title.altTitles, restored.manga.altTitles)
            assertEquals(title.tags.map { it.title }, restored.manga.tags.map { it.title })
            assertEquals(value.trustedIdentifiers, restored.trustedIdentifiers)
            assertNull(restored.manga.chapters)
            assertNull(restored.manga.description)
        }
    }

    @Test fun malformedCandidatesAndBaselinesDoNotCrashCacheLoading() {
        assertNull(RecommendationCodec.decode(JSONObject("{\"id\":3}")))
        assertNull(RecommendationCodec.decodeBaseline("broken"))
        assertEquals(emptyList<Recommendation>(), RecommendationCodec.decodeList("{\"wrong\":true}"))
    }

    @Test fun baselineRoundTripPreservesReleaseAndSessionEvidence() {
        val value = ChapterBaseline("Branch", 134, "digest", 134f, 99, 100, 34, 50)
        assertEquals(value, RecommendationCodec.decodeBaseline(RecommendationCodec.encodeBaseline(value)))
    }
}
