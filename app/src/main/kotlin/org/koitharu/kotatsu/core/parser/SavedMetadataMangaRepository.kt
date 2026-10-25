package org.koitharu.kotatsu.core.parser

import org.koitharu.kotatsu.parsers.model.Manga

/** Applies the shared saved-metadata policy to adapters that own their own stateless/cache layer. */
internal class SavedMetadataMangaRepository(
    private val delegate: MangaRepository,
    private val savedDetails: suspend (Manga) -> Manga?,
    private val freshDetails: suspend (Manga) -> Unit,
) : MangaRepository by delegate {
    override suspend fun getDetails(manga: Manga): Manga {
        savedDetails(manga)?.let { return it }
        val loaded = delegate.getDetails(manga)
        return savedDetails(manga) ?: loaded.also { freshDetails(it) }
    }

    override suspend fun refreshDetails(manga: Manga): Manga =
        delegate.refreshDetails(manga).also { freshDetails(it) }

    override suspend fun getLightweightMetadata(manga: Manga): Manga? {
        savedDetails(manga)?.let { return it.copy(chapters = null) }
        val loaded = delegate.getLightweightMetadata(manga)
        return savedDetails(manga)?.copy(chapters = null) ?: loaded
    }
}
