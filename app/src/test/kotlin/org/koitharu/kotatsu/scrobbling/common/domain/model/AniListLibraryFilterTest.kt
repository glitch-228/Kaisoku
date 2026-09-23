package org.koitharu.kotatsu.scrobbling.common.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AniListLibraryFilterTest {

	private val entries = listOf(
		entry("Zulu", "CURRENT", updatedAt = 20),
		entry("Alpha", "CURRENT", updatedAt = 10),
		entry("Beta", "COMPLETED", updatedAt = 30),
	)

	@Test
	fun statusAndSearchFiltersCombineLocally() {
		val result = AniListLibraryFilter.apply(
			entries = entries,
			status = "CURRENT",
			query = "alp",
			sortByTitle = false,
		)

		assertEquals(listOf("Alpha"), result.map { it.title })
	}

	@Test
	fun titleAndRecentlyUpdatedSortsAreAppliedToFilteredEntries() {
		assertEquals(
			listOf("Alpha", "Zulu"),
			AniListLibraryFilter.apply(entries, "CURRENT", "", sortByTitle = true).map { it.title },
		)
		assertEquals(
			listOf("Zulu", "Alpha"),
			AniListLibraryFilter.apply(entries, "CURRENT", "", sortByTitle = false).map { it.title },
		)
	}

	@Test
	fun statusCountsIncludeAllAndEmptyGroups() {
		assertEquals(3, AniListLibraryFilter.count(entries, null))
		assertEquals(2, AniListLibraryFilter.count(entries, "CURRENT"))
		assertEquals(0, AniListLibraryFilter.count(entries, "DROPPED"))
	}

	private fun entry(title: String, status: String, updatedAt: Long) = AniListLibraryEntry(
		listEntryId = updatedAt,
		mediaId = updatedAt,
		title = title,
		coverUrl = "",
		url = "",
		status = status,
		progress = 0,
		chapters = null,
		score = 0f,
		notes = null,
		updatedAt = updatedAt,
	)
}
