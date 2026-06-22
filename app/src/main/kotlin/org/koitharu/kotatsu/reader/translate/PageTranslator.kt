package org.koitharu.kotatsu.reader.translate

import android.graphics.Bitmap

/**
 * A translation backend for a single page bitmap. Implementations either return
 * [PageTranslationResult.Blocks] (text + boxes, drawn by [TranslationRenderer]) or a
 * [PageTranslationResult.Image] (a page already rendered by the backend, shown as-is).
 */
interface PageTranslator {

	/**
	 * @param onTotal reports the number of work units (tiles/pages) for the progress bar.
	 * @param onTileDone called once per unit with whether it succeeded.
	 */
	suspend fun translate(
		bitmap: Bitmap,
		sourceLang: String,
		targetLang: String,
		onTotal: (Int) -> Unit = {},
		onTileDone: (success: Boolean) -> Unit = {},
	): PageTranslationResult
}

sealed interface PageTranslationResult {

	/** The page-relative text blocks to render over the source bitmap. */
	data class Blocks(
		val blocks: List<TranslatedBlock>,
	) : PageTranslationResult

	/** A finished page produced by the backend; [blocks] is optional (for the OCR sheet / copy). */
	data class Image(
		val rendered: Bitmap,
		val blocks: List<TranslatedBlock> = emptyList(),
	) : PageTranslationResult
}
