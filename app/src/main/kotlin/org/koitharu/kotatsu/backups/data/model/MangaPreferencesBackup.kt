package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags

@Serializable
data class MangaPreferencesBackup(
	@SerialName("manga") val manga: MangaBackup,
	@SerialName("mode") val mode: Int,
	@SerialName("brightness") val brightness: Float,
	@SerialName("contrast") val contrast: Float,
	@SerialName("invert") val invert: Boolean,
	@SerialName("grayscale") val grayscale: Boolean,
	@SerialName("book_effect") val bookEffect: Boolean,
	@SerialName("title_override") val titleOverride: String?,
	@SerialName("cover_override") val coverOverride: String?,
	@SerialName("cover_data") val coverData: String? = null,
	@SerialName("cover_extension") val coverExtension: String? = null,
	@SerialName("content_rating_override") val contentRatingOverride: String?,
) {
	constructor(
		manga: MangaWithTags,
		prefs: MangaPrefsEntity,
		coverData: String? = null,
		coverExtension: String? = null,
	) : this(
		manga = MangaBackup(manga),
		mode = prefs.mode,
		brightness = prefs.cfBrightness,
		contrast = prefs.cfContrast,
		invert = prefs.cfInvert,
		grayscale = prefs.cfGrayscale,
		bookEffect = prefs.cfBookEffect,
		titleOverride = prefs.titleOverride,
		coverOverride = prefs.coverUrlOverride,
		coverData = coverData,
		coverExtension = coverExtension,
		contentRatingOverride = prefs.contentRatingOverride,
	)

	/** New archives omit custom covers; old archives can still restore their embedded images. */
	fun withoutCustomCover() = copy(coverOverride = null, coverData = null, coverExtension = null)

	fun toEntity(restoredCoverOverride: String? = coverOverride) = MangaPrefsEntity(
		mangaId = manga.id,
		mode = mode,
		cfBrightness = brightness,
		cfContrast = contrast,
		cfInvert = invert,
		cfGrayscale = grayscale,
		cfBookEffect = bookEffect,
		titleOverride = titleOverride,
		coverUrlOverride = restoredCoverOverride,
		contentRatingOverride = contentRatingOverride,
	)
}
