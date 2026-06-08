package org.koitharu.kotatsu.backups.mihon

import dagger.Reusable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.parser.PluginMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonSourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import javax.inject.Inject

private const val LONG_HASH_SEED = 1125899906842597L

/**
 * Kaisoku manga/chapter id for a given source name and url. Mirrors `MangaParser.generateUid(url)`
 * and Mihon `MihonMangaRepository.stableId(url)` (byte-identical), so converted entries link to the
 * exact ids the app derives when it later loads the manga from its source.
 */
fun kaisokuUid(sourceName: String, url: String): Long {
	var h = LONG_HASH_SEED
	sourceName.forEach { h = 31 * h + it.code }
	url.forEach { h = 31 * h + it.code }
	return h
}

/**
 * Bridges Mihon numeric source ids and Kaisoku source-name strings for `.tachibk` import/export.
 */
@Reusable
class MihonSourceMatcher @Inject constructor(
	private val mihonExtensionManager: MihonExtensionManager,
	private val repositoryFactory: MangaRepository.Factory,
) {

	private fun installedSources(): List<MihonMangaSource> = mihonExtensionManager.getInstalledSources()

	/** Mihon backup source id -> installed `mihon:pkg/id` source name, or null if not installed. */
	fun mihonIdToSourceName(id: Long): String? =
		installedSources().firstOrNull { it.sourceId == id }?.name

	/** Base url ("https://host/") of an installed source, for reconstructing public urls on import. */
	fun baseUrlOf(sourceName: String): String? =
		MihonSourceRegistry.getDefaultReferer(MangaSource(sourceName))

	/** Display name of an installed Mihon source by id, for the exported `backupSources` list. */
	fun mihonDisplayName(id: Long): String? =
		installedSources().firstOrNull { it.sourceId == id }?.displayName

	/**
	 * Matches a backup source's display name (e.g. "AllHentai") to a Kaisoku built-in parser by
	 * its title/name. Lets a backed-up source whose extension isn't installed move to the built-in
	 * equivalent on import. Returns the built-in source name, or null if there's no match.
	 */
	fun nameToBuiltinSource(name: String?): String? {
		val target = normalizeName(name ?: return null).ifEmpty { return null }
		return MangaParserSource.entries.firstOrNull { src ->
			normalizeName(src.title) == target || normalizeName(src.name) == target
		}?.name
	}

	private fun normalizeName(value: String): String =
		value.lowercase().filter { it.isLetterOrDigit() }

	/**
	 * Kaisoku source name -> Mihon numeric source id for export. `mihon:pkg/id` reads the id
	 * directly; a built-in/plugin source is matched by site domain to an installed Mihon source.
	 * Returns null when no Mihon source applies (caller skips the manga).
	 */
	fun sourceNameToMihonId(sourceName: String, sampleUrl: String?): Long? {
		if (sourceName.startsWith("mihon:")) {
			return sourceName.substringAfterLast('/').toLongOrNull()
		}
		val host = kaisokuSourceHost(sourceName, sampleUrl) ?: return null
		return installedSources().firstOrNull { src ->
			val sh = mihonHost(src)
			sh != null && hostsMatch(host, sh)
		}?.sourceId
	}

	private fun kaisokuSourceHost(sourceName: String, sampleUrl: String?): String? {
		runCatching {
			when (val repo = repositoryFactory.create(MangaSource(sourceName))) {
				is ParserMangaRepository -> repo.domain
				is PluginMangaRepository -> repo.domain
				else -> null
			}
		}.getOrNull()?.let { return normalizeHost(it) }
		return normalizeHost(hostOf(sampleUrl))
	}

	private fun mihonHost(src: MihonMangaSource): String? =
		normalizeHost(hostOf(MihonSourceRegistry.getDefaultReferer(src)))

	private fun hostsMatch(a: String, b: String): Boolean =
		a == b || a.endsWith(".$b") || b.endsWith(".$a")

	private fun hostOf(url: String?): String? {
		val raw = url?.takeIf { it.isNotBlank() } ?: return null
		val parsed = raw.toHttpUrlOrNull() ?: "https://${raw.trimStart('/')}".toHttpUrlOrNull() ?: return null
		return parsed.host
	}

	private fun normalizeHost(host: String?): String? =
		host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
}
