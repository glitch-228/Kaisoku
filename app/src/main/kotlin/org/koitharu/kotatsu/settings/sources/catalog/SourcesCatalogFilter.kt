package org.koitharu.kotatsu.settings.sources.catalog

import org.koitharu.kotatsu.parsers.model.ContentType

data class SourcesCatalogFilter(
	val types: Set<ContentType>,
	val locale: String?,
	val isNewOnly: Boolean,
	val mihonMode: SourceCatalogFilterMode,
	val pluginMode: SourceCatalogFilterMode,
	val novelMode: SourceCatalogFilterMode = SourceCatalogFilterMode.NONE,
)

enum class SourceCatalogFilterMode {
	NONE,
	INCLUDE,
	EXCLUDE,
}
