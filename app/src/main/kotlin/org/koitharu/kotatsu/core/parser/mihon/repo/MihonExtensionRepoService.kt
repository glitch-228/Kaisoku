package org.koitharu.kotatsu.core.parser.mihon.repo

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil
import java.io.IOException
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionRepoService @Inject constructor(
	@MangaHttpClient private val httpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}

	private val protoBuf = ProtoBuf

	suspend fun resolveRepo(indexUrl: String): ResolveResult {
		val normalizedIndexUrl = normalizeIndexUrl(indexUrl) ?: return ResolveResult.InvalidUrl
		val baseUrl = normalizedIndexUrl.removeSuffix("/index.min.json")
			.removeSuffix("/index.json")
			.removeSuffix("/index.pb")
		val repo = fetchRepoDetails(baseUrl) ?: return ResolveResult.InvalidRepo
		return ResolveResult.Success(repo)
	}

	suspend fun fetchExtensions(repo: MihonExtensionRepo): List<MihonAvailableExtension> {
		return loadEntries(repo, indexUrlFor(repo), depth = 0)
			.sortedBy { it.name.lowercase() }
	}

	fun getApkUrl(extension: MihonAvailableExtension): String {
		val apkName = extension.apkName
		if (apkName.startsWith("http://") || apkName.startsWith("https://")) {
			return apkName
		}
		return "${extension.repo.baseUrl}/apk/$apkName"
	}

	private suspend fun loadEntries(
		repo: MihonExtensionRepo,
		url: String,
		depth: Int,
	): List<MihonAvailableExtension> {
		if (depth > MAX_INDEX_HOPS) {
			return emptyList()
		}
		val bytes = fetchBytes(url) ?: return emptyList()
		return when (bytes.firstOrNull()) {
			OPEN_BRACKET -> {
				// Legacy flat index.min.json array.
				val entries = json.decodeFromString<List<MihonExtensionIndexEntryDto>>(bytes.decodeToString())
				if (entries.isLegacyOutdatedPlaceholderIndex()) {
					// The repo's legacy URL was replaced with an "Outdated App" marker; follow its
					// repo.json -> index_v2 pointer instead of presenting the placeholder rows.
					followLegacyPointer(repo, url, depth)
				} else {
					entries.mapNotNull { dto -> dto.toAvailableExtension(repo) }
				}
			}

			OPEN_BRACE -> {
				// Either a legacy repo.json pointer or a store-shaped JSON index.
				val text = bytes.decodeToString()
				val pointer = runCatching { json.decodeFromString<MihonExtensionRepoMetaResponse>(text) }.getOrNull()
				val store = runCatching { json.decodeFromString<NetworkExtensionStoreJson>(text) }.getOrNull()

				if (store != null && store.extensionList?.extensions?.isNotEmpty() == true) {
					store.toEntries(repo)
				} else if (store != null && !store.extensionListUrl.isNullOrBlank()) {
					loadEntries(repo, resolveRepoIndexUrl(repo, store.extensionListUrl), depth + 1)
				} else {
					val explicit = runCatching {
						json.decodeFromString<MihonStoreIndexPointer>(text)
					}.getOrNull()?.indexV2
					if (explicit != null) {
						loadEntries(repo, resolveRepoIndexUrl(repo, explicit), depth + 1)
					} else if (pointer != null) {
						// A bare repo.json was requested as the index: locate its index file by convention.
						loadFirstAvailableIndex(repo, legacyModernIndexCandidates(repo.baseUrl), depth + 1)
					} else {
						emptyList()
					}
				}
			}

			null -> emptyList()

			else -> {
				// Protobuf (`index.pb`) — same store shape, binary-encoded.
				val store = runCatching { protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes) }
					.getOrElse { throw IOException("Could not decode extension repository index", it) }
				when {
					store.extensionList != null -> store.extensionList.extensions.mapNotNull {
						it.toAvailableExtension(repo)
					}

					!store.extensionListUrl.isNullOrBlank() -> loadEntries(repo, store.extensionListUrl, depth + 1)
					else -> emptyList()
				}
			}
		}
	}

	private suspend fun followLegacyPointer(repo: MihonExtensionRepo, url: String, depth: Int): List<MihonAvailableExtension> {
		// Some legacy repositories publish a pointer, while others only expose the new index
		// files. A missing repo.json must not hide a valid index.json or index.pb.
		val pointer = fetchBytes("${repo.baseUrl}/repo.json")?.let { bytes ->
			runCatching { json.decodeFromString<MihonStoreIndexPointer>(bytes.decodeToString()).indexV2 }
				.getOrNull()
		}
		val candidates = legacyModernIndexCandidates(repo.baseUrl, pointer)
			.map { resolveRepoIndexUrl(repo, it) }
			.filterNot { it == url }
		return loadFirstAvailableIndex(repo, candidates, depth + 1)
	}

	private suspend fun loadFirstAvailableIndex(
		repo: MihonExtensionRepo,
		candidates: List<String>,
		depth: Int,
	): List<MihonAvailableExtension> {
		for (candidate in candidates) {
			val result = loadEntries(repo, candidate, depth)
			if (result.isNotEmpty()) return result
		}
		return emptyList()
	}

	private fun resolveRepoIndexUrl(repo: MihonExtensionRepo, value: String): String {
		return resolveRepoIndexUrlFromBase(repo.baseUrl, value)
	}

	private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
		httpClient.newCall(
			Request.Builder()
				.url(url)
				.build(),
		).await().use { response ->
			if (response.code == 404) return@withContext null
			if (!response.isSuccessful) throw IOException("Repository request failed with HTTP ${response.code}: $url")
			response.body.bytes().gunzipIfNeeded().takeIf { it.isNotEmpty() }
		}
	}

	private fun ByteArray.gunzipIfNeeded(): ByteArray {
		return if (size >= 2 && this[0] == GZIP_MAGIC_0 && this[1] == GZIP_MAGIC_1) {
			runCatching { GZIPInputStream(inputStream()).use { it.readBytes() } }
				.getOrElse { throw IOException("Could not decompress extension repository index", it) }
		} else {
			this
		}
	}

	private fun NetworkExtensionStoreJson.toEntries(repo: MihonExtensionRepo): List<MihonAvailableExtension> {
		return extensionList?.extensions.orEmpty().mapNotNull { it.toAvailableExtension(repo) }
	}

	private fun MihonExtensionIndexEntryDto.toAvailableExtension(repo: MihonExtensionRepo): MihonAvailableExtension? {
		val libVersion = MihonExtensionPackageUtil.parseLibVersion(version) ?: return null
		if (!MihonExtensionPackageUtil.isSupportedLibVersion(libVersion)) {
			return null
		}
		return MihonAvailableExtension(
			repo = repo,
			name = name.removePrefix("Tachiyomi: ").trim(),
			pkgName = pkg,
			versionName = version,
			versionCode = code,
			libVersion = libVersion,
			lang = lang,
			isNsfw = nsfw == 1,
			sources = sources.orEmpty().map { source ->
				MihonAvailableExtensionSource(
					id = source.id,
					lang = source.lang,
					name = source.name,
					baseUrl = source.baseUrl,
				)
			},
			apkName = apk,
			iconUrl = "${repo.baseUrl}/icon/$pkg.png",
		)
	}

	private fun indexUrlFor(repo: MihonExtensionRepo): String {
		val baseUrl = repo.baseUrl
		return when {
			baseUrl.endsWith(".json") || baseUrl.endsWith(".pb") -> baseUrl
			repo.isStoreFormat -> "$baseUrl/index.json"
			else -> "$baseUrl/index.min.json"
		}
	}

	private suspend fun fetchRepoDetails(baseUrl: String): MihonExtensionRepo? {
		val body = fetchBytes("$baseUrl/repo.json")?.decodeToString() ?: return null
		return runCatching { json.decodeFromString<MihonExtensionRepoMetaResponse>(body).toRepo(baseUrl) }
			.getOrNull()
	}

	private fun normalizeIndexUrl(value: String): String? {
		return value.trim()
			.toHttpUrlOrNull()
			?.toString()
			?.takeIf { it.matches(REPO_URL_REGEX) }
	}

	sealed interface ResolveResult {
		data class Success(val repo: MihonExtensionRepo) : ResolveResult
		data object InvalidUrl : ResolveResult
		data object InvalidRepo : ResolveResult
	}

	private companion object {
		val REPO_URL_REGEX = """^https://.*/index\.(?:min\.json|json|pb)$""".toRegex()
		const val OPEN_BRACKET: Byte = 91 // '[' — legacy JSON array index
		const val OPEN_BRACE: Byte = 123 // '{' — JSON object (repo.json or store); else protobuf
		const val MAX_INDEX_HOPS = 3
		const val TOMBSTONE_KEIYOUSHI_PKG = "eu.kanade.tachiyomi.extension.all.keiyoushi"
		const val TOMBSTONE_MIHON_PKG = "eu.kanade.tachiyomi.extension.all.mihon"
		const val GZIP_MAGIC_0: Byte = 0x1f.toByte()
		const val GZIP_MAGIC_1: Byte = 0x8b.toByte()
	}
}

internal fun List<MihonExtensionIndexEntryDto>.isLegacyOutdatedPlaceholderIndex(): Boolean {
	// The keiyoushi legacy index flip leaves at most two placeholder rows whose packages
	// are the migration stubs rather than real extensions.
	return isNotEmpty() && size <= 2 && all { dto ->
		dto.pkg == "eu.kanade.tachiyomi.extension.all.keiyoushi" ||
			dto.pkg == "eu.kanade.tachiyomi.extension.all.mihon"
	}
}

internal fun legacyModernIndexCandidates(repoBaseUrl: String, pointer: String? = null): List<String> {
	val root = repoBaseUrl.trimEnd('/')
	return buildList {
		pointer?.takeIf(String::isNotBlank)?.let(::add)
		add("$root/index.json")
		add("$root/index.pb")
	}.distinct()
}

internal fun resolveRepoIndexUrlFromBase(repoBaseUrl: String, value: String): String {
	val base = repoBaseUrl.trimEnd('/') + "/"
	return base.toHttpUrlOrNull()?.resolve(value)?.toString() ?: value
}
