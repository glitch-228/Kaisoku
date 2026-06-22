package org.koitharu.kotatsu.reader.translate

sealed interface OcrSheetState {
	data object Idle : OcrSheetState
	data object Loading : OcrSheetState
	data class Done(val text: String, val blocks: List<TranslatedBlock>) : OcrSheetState
	data class Failed(val error: Throwable) : OcrSheetState
}
