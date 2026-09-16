package org.koitharu.kotatsu.details.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.parsers.model.Manga

/** Null means absent metadata; an explicitly empty chapter list is a valid source response. */
internal fun Manga.withMissingDetailsFrom(seed: Manga): Manga = copy(
	description = description ?: seed.description,
	coverUrl = coverUrl ?: seed.coverUrl,
	largeCoverUrl = largeCoverUrl ?: seed.largeCoverUrl,
	chapters = chapters ?: seed.chapters,
)

/** Match title requests, including LibSocial's API, without matching chapter/related/image requests. */
internal fun isMangaDetailsUrl(failedUrl: String, sourceUrl: String, publicUrl: String): Boolean {
	val failed = failedUrl.toHttpUrlOrNull() ?: return false
	val public = publicUrl.toHttpUrlOrNull()
	val source = sourceUrl.toHttpUrlOrNull() ?: public?.resolve(sourceUrl)
	val path = failed.encodedPath.trimEnd('/')
	if (listOfNotNull(source, public).any { failed.host == it.host && path == it.encodedPath.trimEnd('/') }) return true
	val slug = sourceUrl.trim('/').takeUnless { it.contains('/') || it.isBlank() } ?: return false
	return failed.host == "api.cdnlibs.org" && path == "/api/manga/$slug" &&
		public?.host in setOf("mangalib.org", "mangalib.me", "hentailib.me", "hentailib.org", "slashlib.me")
}
