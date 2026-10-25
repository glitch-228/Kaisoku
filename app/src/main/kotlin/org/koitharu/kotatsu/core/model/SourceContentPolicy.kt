package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.remote.library.RemoteLibrarySource
import org.koitharu.kotatsu.remote.library.RemoteLibraryContent

enum class SourceContentClass { SAFE, MIXED, ADULT }

data class SourceContentPolicy(
    val separateClasses: Boolean,
    val selectedClasses: Set<SourceContentClass>,
    val legacyRestricted: Boolean,
) {
    fun allows(contentClass: SourceContentClass, legacyNsfw: Boolean): Boolean =
        if (separateClasses) contentClass in selectedClasses else !legacyRestricted || !legacyNsfw

    fun allows(source: MangaSource): Boolean = allows(source.contentClass(), source.isNsfw())

    fun allows(manga: Manga): Boolean = allows(manga.contentClassForFiltering(), manga.isNsfw())
}

/** A local acquisition retains its source rating even after its server has been disconnected. */
fun Manga.contentClassForFiltering(): SourceContentClass {
    if (contentRating == ContentRating.ADULT) return SourceContentClass.ADULT
    val acquired = org.koitharu.kotatsu.remote.library.RemoteLibrarySourceRegistry.acquiredMetadata(id, url)
    return when (acquired?.content) {
        RemoteLibraryContent.SAFE -> SourceContentClass.SAFE
        RemoteLibraryContent.ADULT -> SourceContentClass.ADULT
        RemoteLibraryContent.MIXED, RemoteLibraryContent.UNKNOWN -> SourceContentClass.MIXED
        null -> source.contentClass()
    }
}

fun MangaSource.contentClass(): SourceContentClass {
    val source = unwrap()
    NsfwSourceOverrides.peek(source.name)?.let {
        return if (it) SourceContentClass.ADULT else SourceContentClass.SAFE
    }
    return when (source) {
        is MihonMangaSource -> source.resolved().contentClass
        is RemoteLibrarySource -> when (source.resolved().content) {
            RemoteLibraryContent.SAFE -> SourceContentClass.SAFE
            RemoteLibraryContent.ADULT -> SourceContentClass.ADULT
            RemoteLibraryContent.MIXED, RemoteLibraryContent.UNKNOWN -> SourceContentClass.MIXED
        }
        else -> if (source.intrinsicIsNsfw()) SourceContentClass.ADULT else SourceContentClass.SAFE
    }
}

/** Content filtering may distinguish mixed sources; incognito and notification privacy keep isNsfw(). */
fun Manga.isNsfwForFiltering(separateSourceClasses: Boolean): Boolean =
    if (separateSourceClasses) contentClassForFiltering() == SourceContentClass.ADULT
    else isNsfw()

internal fun parseContentWarning(value: Any?): SourceContentClass? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}?.let { when (it) { 0 -> SourceContentClass.SAFE; 1 -> SourceContentClass.MIXED; 2 -> SourceContentClass.ADULT; else -> null } }

internal fun legacyNsfwFlag(value: Any?): Boolean = when (value) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value.trim() == "1" || value.trim().equals("true", true)
    else -> false
}

internal fun manifestContentClass(warning: Any?, legacy: Any?): SourceContentClass {
    val explicit = parseContentWarning(warning)
    return when {
        explicit == SourceContentClass.MIXED -> explicit
        explicit == SourceContentClass.ADULT || legacyNsfwFlag(legacy) ||
            (explicit == null && (legacyNsfwFlag(warning) || (warning as? Number)?.toDouble()?.let { it > 0 } == true ||
                (warning as? String)?.trim()?.toIntOrNull()?.let { it > 0 } == true)) -> SourceContentClass.ADULT
        else -> SourceContentClass.SAFE
    }
}
