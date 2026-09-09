package org.koitharu.kotatsu.core.parser.lnreader

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * A novel source backed by an installed LNReader JS plugin.
 * Name scheme: `lnreader:<pluginId>` — parsed back by the [org.koitharu.kotatsu.core.model.MangaSource]
 * factory so persisted favourites/history survive plugin reinstalls keyed by plugin id.
 */
class LnReaderMangaSource(
	val pluginId: String,
	val displayName: String,
	val lang: String? = null,
	val site: String? = null,
) : MangaSource {

	override val name: String
		get() = "$NAME_PREFIX$pluginId"

	val contentType: ContentType
		get() = ContentType.NOVEL

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		return name == other.name
	}

	override fun hashCode(): Int = name.hashCode()

	override fun toString(): String = name

	companion object {
		const val NAME_PREFIX = "lnreader:"

		fun extractPluginId(name: String): String? =
			name.takeIf { it.startsWith(NAME_PREFIX) }?.removePrefix(NAME_PREFIX)?.takeIf { it.isNotBlank() }
	}
}
