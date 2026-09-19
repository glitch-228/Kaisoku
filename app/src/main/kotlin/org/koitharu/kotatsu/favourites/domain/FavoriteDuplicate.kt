package org.koitharu.kotatsu.favourites.domain

import org.koitharu.kotatsu.core.model.identityName
import org.koitharu.kotatsu.core.model.isSameEntryAs
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Finds a favorite that represents the same work as [candidate].
 *
 * Alternate titles are deliberately not compared across sources. A parser can expose the
 * title of an unrelated work as an alternate title, and treating that as a duplicate prevents
 * users from adding the work they actually selected. Identity and tracker matches remain safe;
 * the canonical title fallback is limited to the same source.
 */
internal fun findFavoriteDuplicate(
	candidate: Manga,
	favorites: Collection<Manga>,
	trackerDuplicateId: Long?,
): Manga? {
	return favorites.firstOrNull { it.id == trackerDuplicateId }
		?: favorites.firstOrNull { favorite ->
			favorite.id != candidate.id && favorite.isSameEntryAs(candidate)
		}
		?: favorites.firstOrNull { favorite ->
			favorite.id != candidate.id &&
			favorite.source.identityName() == candidate.source.identityName() &&
			favorite.title.equals(candidate.title, ignoreCase = true)
		}
}
