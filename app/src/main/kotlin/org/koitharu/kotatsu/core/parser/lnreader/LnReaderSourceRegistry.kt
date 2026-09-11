package org.koitharu.kotatsu.core.parser.lnreader

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory registry of installed LNReader plugin sources.
 * Populated on app start / after plugin install changes from the DB by
 * [org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceManager].
 * Mirrors the role MangaSourceRegistry plays for jar plugins.
 */
object LnReaderSourceRegistry {

	val sources: MutableList<LnReaderMangaSource> = CopyOnWriteArrayList()

	fun peek(pluginId: String): LnReaderMangaSource? =
		sources.firstOrNull { it.pluginId == pluginId }

	fun replaceAll(value: Collection<LnReaderMangaSource>) {
		sources.clear()
		sources.addAll(value)
	}
}
