package org.koitharu.kotatsu.backups.domain

import dagger.Reusable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.UnresolvedMangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.parser.PluginMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonSourceRegistry
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject

/**
 * Works out, for each source string found in a backup, which installed sources could serve that
 * site — so the user (or a global preference) can pick where titles are restored when the same
 * site is available in several places (built-in parser vs an installed extension/plugin).
 *
 * Matching: exact source-name resolution first, then a domain match between the source's site and
 * installed extensions. A source group is *ambiguous* when two or more distinct sources qualify.
 */
@Reusable
class SourceRemapResolver @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val repositoryFactory: MangaRepository.Factory,
) {

	data class SourceCandidates(
		val original: String,
		val candidates: List<MangaSource>,
		val isOriginalInstalled: Boolean = true,
	) {
		val isAmbiguous: Boolean
			get() = candidates.distinctBy { it.name }.size >= 2

		/** Original source isn't installed/resolvable but an alternative (e.g. built-in) exists. */
		val needsResolution: Boolean
			get() = !isOriginalInstalled && candidates.isNotEmpty()
	}

	/**
	 * @param sourceSamples map of backup source string -> a sample manga public url for that source.
	 */
	suspend fun buildPlan(sourceSamples: Map<String, String?>): Map<String, SourceCandidates> {
		if (sourceSamples.isEmpty()) return emptyMap()
		val extensions = sourcesRepository.getInstalledExtensions()
		val extByHost = HashMap<String, MutableList<MangaSource>>()
		for (ext in extensions) {
			for (host in domainsOf(ext)) {
				extByHost.getOrPut(host) { ArrayList() }.add(ext)
			}
		}
		val result = LinkedHashMap<String, SourceCandidates>(sourceSamples.size)
		for ((name, sampleUrl) in sourceSamples) {
			val exact = resolveInstalled(name)
			val host = exact?.let { domainsOf(it).firstOrNull() } ?: normalizeHost(hostOf(sampleUrl))
			val extMatches = host?.let { matchHost(extByHost, it) }.orEmpty()
			// An unresolved source (e.g. a backup whose extension isn't installed) may still match a
			// built-in by name — offered in the picker, never applied automatically.
			val nameMatch = if (exact == null) nameToBuiltin(name) else null
			val candidates = (listOfNotNull(exact, nameMatch) + extMatches).distinctBy { it.name }
			result[name] = SourceCandidates(name, candidates, isOriginalInstalled = exact != null)
		}
		return result
	}

	private fun nameToBuiltin(name: String): MangaSource? {
		val target = normalizeName(name)
		if (target.isEmpty()) return null
		return MangaParserSource.entries.firstOrNull { src ->
			normalizeName(src.title) == target || normalizeName(src.name) == target
		}
	}

	private fun normalizeName(value: String): String =
		value.lowercase().filter { it.isLetterOrDigit() }

	/**
	 * Auto-resolution for every source group according to [preference]; only emits an entry when
	 * the chosen target differs from the original. KEEP never remaps.
	 */
	fun defaultRemap(
		plan: Map<String, SourceRemapResolver.SourceCandidates>,
		preference: SourceRemapPreference,
	): SourceRemap {
		if (preference == SourceRemapPreference.KEEP) return SourceRemap.IDENTITY
		val perSource = HashMap<String, String>()
		for ((name, group) in plan) {
			val target = pickByPreference(group, preference) ?: continue
			if (target.name != name) {
				perSource[name] = target.name
			}
		}
		return if (perSource.isEmpty()) SourceRemap.IDENTITY else SourceRemap(perSource = perSource)
	}

	fun pickByPreference(group: SourceCandidates, preference: SourceRemapPreference): MangaSource? {
		val candidates = group.candidates
		if (candidates.isEmpty()) return null
		return when (preference) {
			SourceRemapPreference.KEEP -> candidates.firstOrNull { it.name == group.original }
			SourceRemapPreference.BUILT_IN -> candidates.firstOrNull { it is MangaParserSource } ?: candidates.first()
			SourceRemapPreference.EXTENSION -> candidates.firstOrNull { it !is MangaParserSource } ?: candidates.first()
		}
	}

	private fun resolveInstalled(name: String): MangaSource? {
		val resolved = MangaSource(name)
		return resolved.takeUnless { it is UnresolvedMangaSource || it is UnknownMangaSource }
	}

	private fun domainsOf(source: MangaSource): Set<String> = runCatching {
		when (val repo = repositoryFactory.create(source)) {
			is ParserMangaRepository -> repo.domains.toMutableSet().apply { add(repo.domain) }
			is PluginMangaRepository -> repo.domains.toMutableSet().apply { add(repo.domain) }
			is MihonMangaRepository -> setOfNotNull(MihonSourceRegistry.getDefaultReferer(repo.source))
			else -> emptySet()
		}
	}.getOrDefault(emptySet()).mapNotNullTo(LinkedHashSet()) { normalizeHost(hostOf(it)) }

	private fun matchHost(index: Map<String, List<MangaSource>>, host: String): List<MangaSource> {
		index[host]?.let { return it }
		return index.entries
			.filter { (key, _) -> host.endsWith(".$key") || key.endsWith(".$host") }
			.flatMap { it.value }
	}

	private fun hostOf(url: String?): String? {
		val raw = url?.takeIf { it.isNotBlank() } ?: return null
		val parsed = raw.toHttpUrlOrNull() ?: "https://${raw.trimStart('/')}".toHttpUrlOrNull() ?: return null
		return parsed.host
	}

	private fun normalizeHost(host: String?): String? =
		host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
}
