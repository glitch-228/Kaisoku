package org.koitharu.kotatsu.search.domain

@kotlinx.serialization.Serializable
enum class SearchKind {

	SIMPLE, TITLE, AUTHOR, TAG
}
