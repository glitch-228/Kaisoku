package org.koitharu.kotatsu.core.parser.lnreader

import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * An `lnreader:<id>` source whose plugin is no longer installed.
 * Keeps persisted favourites/history resolvable instead of degrading to UNKNOWN.
 */
class UnresolvedLnReaderSource(
	val pluginId: String,
) : MangaSource {

	override val name: String
		get() = LnReaderMangaSource.NAME_PREFIX + pluginId

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		return name == other.name
	}

	override fun hashCode(): Int = name.hashCode()

	override fun toString(): String = name
}
