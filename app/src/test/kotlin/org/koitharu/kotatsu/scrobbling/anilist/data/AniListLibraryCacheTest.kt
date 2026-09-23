package org.koitharu.kotatsu.scrobbling.anilist.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListLibraryCacheTest {

	@Test
	fun emptyLibraryStillCountsAsFreshWhenAnEmptyCacheWasStored() {
		assertTrue(isAniListLibraryCacheFresh(hasCache = true, fetchedAt = 900L, now = 1_000L, ttl = 500L))
	}

	@Test
	fun missingExpiredOrFutureCacheTimestampsAreStale() {
		assertFalse(isAniListLibraryCacheFresh(hasCache = false, fetchedAt = 900L, now = 1_000L, ttl = 500L))
		assertFalse(isAniListLibraryCacheFresh(hasCache = true, fetchedAt = 100L, now = 1_000L, ttl = 500L))
		assertFalse(isAniListLibraryCacheFresh(hasCache = true, fetchedAt = 1_100L, now = 1_000L, ttl = 500L))
	}
}
