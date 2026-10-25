package org.koitharu.kotatsu.reader.translate

import org.koitharu.kotatsu.core.prefs.AppSettings

/** One request's settings. Credentials stay in memory and never appear in [toString]. */
class TextTranslationConfig internal constructor(
    val provider: TranslateProvider,
    val model: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    internal val endpoint: String,
    internal val apiKey: String,
    internal val customHeaders: String,
    internal val concurrency: Int,
) {

    internal val isGemini: Boolean
        get() = provider == TranslateProvider.GEMINI || endpoint.contains("generateContent") ||
            endpoint.contains("googleapis.com/v1beta/models/")

    override fun toString(): String =
        "TextTranslationConfig(provider=$provider, model=$model, source=$sourceLanguage, target=$targetLanguage)"

    companion object {
        fun capture(settings: AppSettings): TextTranslationConfig {
            val provider = settings.translateProvider
            val google = provider == TranslateProvider.GOOGLE_LENS
            return TextTranslationConfig(
                provider = provider,
                model = if (google) "" else settings.translateModel.trim().ifBlank {
                    if (provider == TranslateProvider.GEMINI) "gemini-2.5-flash" else "gpt-4o-mini"
                },
                sourceLanguage = settings.translateSourceLanguage.trim().ifBlank { "auto" },
                targetLanguage = settings.translateTargetLanguage.trim().ifBlank { "en" },
                endpoint = if (google) "" else settings.translateEndpoint.trim(),
                apiKey = if (google) "" else settings.translateApiKey.trim(),
                customHeaders = if (google) "" else settings.translateCustomHeaders.trim(),
                concurrency = settings.translateConcurrency,
            )
        }
    }
}
