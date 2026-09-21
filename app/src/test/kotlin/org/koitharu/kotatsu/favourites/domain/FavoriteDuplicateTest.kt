package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource

class FavoriteDuplicateTest {

	@Test
	fun `alternate title from another work does not block favorite`() {
		val candidate = manga(
			id = 2L,
			title = "The Apocalypse is Here",
			altTitles = setOf("44th Period Survival Class"),
			url = "/apocalypse",
		)
		val existing = manga(
			id = 1L,
			title = "44th Period Survival Class",
			url = "/survival-class",
		)

		assertNull(findFavoriteDuplicate(candidate, listOf(existing), trackerDuplicateId = null))
	}

	@Test
	fun `same source canonical title remains a duplicate`() {
		val candidate = manga(id = 2L, title = "The Apocalypse is Here", url = "/apocalypse")
		val existing = manga(id = 1L, title = "the apocalypse is here", url = "/other")

		assertEquals(existing, findFavoriteDuplicate(candidate, listOf(existing), trackerDuplicateId = null))
	}

	@Test
	fun `tracker mapping wins across sources`() {
		val candidate = manga(id = 2L, source = MangaParserSource.ATSUMOE)
		val existing = manga(id = 1L)

		assertEquals(existing, findFavoriteDuplicate(candidate, listOf(existing), trackerDuplicateId = existing.id))
	}

	@Test
	fun `matching titles across sources alone are not duplicates`() {
		val candidate = manga(id = 2L, source = MangaParserSource.ATSUMOE)
		assertNull(findFavoriteDuplicate(candidate, listOf(manga(id = 1L)), null))
	}

	@Test
	fun `same URL with changed title still identifies duplicate`() {
		val candidate = manga(id = 2L, title = "Renamed title")
		val existing = manga(id = 1L)
		assertEquals(existing, findFavoriteDuplicate(candidate, listOf(existing), null))
	}

	@Test
	fun `existing entry does not warn when adding another category`() {
		val candidate = manga(id = 1L)
		assertNull(findFavoriteDuplicate(candidate, listOf(candidate), null))
	}

	private fun manga(
		id: Long,
		title: String = "Title",
		altTitles: Set<String> = emptySet(),
		url: String = "/title",
		source: MangaSource = MangaParserSource.MANGAKIO,
	) = Manga(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = "https://example.org$url",
		rating = -1f,
		contentRating = ContentRating.SAFE,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)
}
