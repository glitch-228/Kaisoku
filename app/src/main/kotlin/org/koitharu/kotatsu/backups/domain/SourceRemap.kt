package org.koitharu.kotatsu.backups.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Global preference for resolving a source that exists in several places (e.g. a site that is
 * available both as a built-in parser and as an installed Mihon/Kotatsu extension).
 */
enum class SourceRemapPreference {
	KEEP, // keep whatever the backup recorded
	BUILT_IN, // prefer the built-in parser
	EXTENSION, // prefer the extension/plugin
}

/**
 * Decisions for rewriting manga `source` identifiers while restoring a backup so titles land on
 * the source the user actually has installed. Precedence when resolving a title:
 * per-manga override > per-source default > original source string.
 *
 * Serialisable so the computed plan can be handed to [RestoreService] through an Intent extra.
 */
@Serializable
data class SourceRemap(
	val perSource: Map<String, String> = emptyMap(),
	val perManga: Map<String, String> = emptyMap(),
) {

	val isEmpty: Boolean
		get() = perSource.isEmpty() && perManga.isEmpty()

	fun resolve(source: String, url: String): String {
		perManga[key(source, url)]?.let { return it }
		perSource[source]?.let { return it }
		return source
	}

	fun toJson(): String = JSON.encodeToString(serializer(), this)

	companion object {

		val IDENTITY = SourceRemap()

		private val JSON = Json { ignoreUnknownKeys = true }

		fun key(source: String, url: String): String = "$source $url"

		fun fromJson(value: String?): SourceRemap = if (value.isNullOrEmpty()) {
			IDENTITY
		} else {
			runCatching { JSON.decodeFromString(serializer(), value) }.getOrDefault(IDENTITY)
		}
	}
}
