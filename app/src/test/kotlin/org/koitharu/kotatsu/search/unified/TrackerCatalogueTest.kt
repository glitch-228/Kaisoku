package org.koitharu.kotatsu.search.unified

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.SourceContentClass
import org.koitharu.kotatsu.core.model.SourceContentPolicy
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class TrackerCatalogueTest {
    @Test fun allFiveSearchRequestsUseFixedOriginsAndReadEndpoints() {
        val hosts = listOf("graphql.anilist.co", "api.myanimelist.net", "kitsu.app", "shikimori.io", "api.mangabaka.org")
        val services = listOf(ScrobblerService.ANILIST, ScrobblerService.MAL, ScrobblerService.KITSU,
            ScrobblerService.SHIKIMORI, ScrobblerService.MANGABAKA)
        services.zip(hosts).forEach { (service, host) ->
            val request = TrackerCatalogue.request(service, "我的 & Fox", null)
            assertEquals(host, request.url.host)
            assertTrue(request.url.isHttps)
            assertEquals(if (service == ScrobblerService.ANILIST) "POST" else "GET", request.method)
            assertNull(request.header("Authorization"))
            if (service == ScrobblerService.ANILIST) {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val text = JSONObject(buffer.readUtf8()).getString("query")
                assertFalse(text.contains("mutation")); assertTrue(text.contains("isAdult")); assertTrue(text.contains("format"))
            }
        }
    }

    @Test fun anilistPagePreservesAliasesNovelTypeAndExplicitAdultRating() {
        val page = TrackerCatalogue.decode(ScrobblerService.ANILIST, "Fox", null, JSONObject("""
            {"data":{"Page":{"pageInfo":{"hasNextPage":true},"media":[
              {"id":123,"title":{"userPreferred":"Fox","native":"狐"},"synonyms":["ثعلب"],
               "format":"NOVEL","isAdult":true,"chapters":365,"coverImage":{"large":"https://example.test/c"}}
            ]}}}
        """))
        val work = page.titles.single()
        assertEquals("123", work.id); assertEquals(setOf("狐", "ثعلب"), work.aliases)
        assertEquals(true, work.isNovel); assertEquals(CatalogueContent.ADULT, work.content)
        assertEquals("365", work.chapters); assertNotNull(page.nextCursor)
        val second = TrackerCatalogue.request(ScrobblerService.ANILIST, "Fox", page.nextCursor)
        val buffer = okio.Buffer(); second.body!!.writeTo(buffer); assertTrue(buffer.readUtf8().contains("page: 2"))
        assertThrows(IllegalArgumentException::class.java) { TrackerCatalogue.request(ScrobblerService.ANILIST, "Other", page.nextCursor) }
        assertThrows(IllegalArgumentException::class.java) { TrackerCatalogue.request(ScrobblerService.MAL, "Fox", page.nextCursor) }
    }

    @Test fun providerSpecificCataloguesRetainClassificationAndNeverFollowForeignNextUrls() {
        val samples = mapOf(
            ScrobblerService.MAL to JSONObject("""{"data":[{"node":{"id":1,"title":"A","media_type":"manga","nsfw":"white"}}],"paging":{"next":"https://evil.invalid/steal"}}"""),
            ScrobblerService.KITSU to JSONObject("""{"data":[{"id":"9007199254740993123","attributes":{"canonicalTitle":"A","titles":{"en":"Alias"},"subtype":"manga","ageRating":"PG"}}],"links":{"next":"https://evil.invalid"}}"""),
            ScrobblerService.SHIKIMORI to JSONArray("""[{"id":1,"name":"A","russian":"А","kind":"manga","rating":"pg_13"}]"""),
            ScrobblerService.MANGABAKA to JSONObject("""{"data":[{"id":"opaque-work","titles":[{"title":"A"}],"type":"manga","content_rating":"safe"}],"pagination":{"next":"https://evil.invalid"}}"""),
        )
        samples.forEach { (service, json) ->
            val page = TrackerCatalogue.decode(service, "AA", null, json)
            assertEquals(CatalogueContent.SAFE, page.titles.single().content)
            assertEquals(false, page.titles.single().isNovel)
            page.nextCursor?.let { assertFalse(TrackerCatalogue.request(service, "AA", it).url.host.contains("evil")) }
        }
        assertEquals("9007199254740993123", TrackerCatalogue.decode(ScrobblerService.KITSU, "AA", null,
            samples.getValue(ScrobblerService.KITSU)).titles.single().id)
        val oel = samples.getValue(ScrobblerService.KITSU) as JSONObject
        oel.getJSONArray("data").getJSONObject(0).getJSONObject("attributes").put("subtype", "oel")
        assertEquals(false, TrackerCatalogue.decode(ScrobblerService.KITSU, "AA", null, oel).titles.single().isNovel)
        assertEquals("opaque-work", TrackerCatalogue.decode(ScrobblerService.MANGABAKA, "AA", null,
            samples.getValue(ScrobblerService.MANGABAKA)).titles.single().id)
    }

    @Test fun unknownRatingsNeverPassActiveRestrictionsAndExplicitAdultCanBeExcluded() {
        val safeOnly = SourceContentPolicy(true, setOf(SourceContentClass.SAFE), false)
        assertTrue(CatalogueContent.SAFE.allowed(safeOnly))
        assertFalse(CatalogueContent.UNKNOWN.allowed(safeOnly))
        assertFalse(CatalogueContent.ADULT.allowed(safeOnly))
        assertFalse(CatalogueContent.MIXED.allowed(safeOnly))
        val legacy = SourceContentPolicy(false, SourceContentClass.entries.toSet(), true)
        assertFalse(CatalogueContent.UNKNOWN.allowed(legacy)); assertFalse(CatalogueContent.ADULT.allowed(legacy))
        assertTrue(CatalogueContent.UNKNOWN.allowed(legacy.copy(legacyRestricted = false)))
        ScrobblerService.entries.forEach { assertEquals(CatalogueContent.UNKNOWN, TrackerCatalogue.classify(it, JSONObject())) }
    }

    @Test fun duplicateWorksAndGraphqlErrorsFailInsteadOfFabricatingEmptySuccess() {
        assertThrows(IllegalArgumentException::class.java) { TrackerCatalogue.decode(ScrobblerService.MAL, "AA", null,
            JSONObject("""{"data":[{"node":{"id":1,"title":"A"}},{"node":{"id":1,"title":"A"}}]}""")) }
        assertThrows(IllegalArgumentException::class.java) { TrackerCatalogue.decode(ScrobblerService.ANILIST, "AA", null,
            JSONObject("""{"errors":[{"message":"Too many requests"}],"data":null}""")) }
    }
}
