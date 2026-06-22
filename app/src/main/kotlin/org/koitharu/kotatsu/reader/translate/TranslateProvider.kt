package org.koitharu.kotatsu.reader.translate

enum class TranslateProvider {
	OPENAI_COMPATIBLE,
	GEMINI,
	GOOGLE_LENS,
}

enum class TranslateTriggerMode {
	MANUAL,
	AUTO_ON_PAGE,
}
