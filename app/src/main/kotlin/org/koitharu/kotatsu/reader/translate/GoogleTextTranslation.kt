package org.koitharu.kotatsu.reader.translate

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray

/** Shared by Lens OCR output and direct novel translation. */
internal object GoogleTextTranslation {
	fun url(text: String, source: String, target: String) =
		"https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
			.addQueryParameter("client", "gtx").addQueryParameter("dt", "t")
			.addQueryParameter("sl", source.ifBlank { "auto" })
			.addQueryParameter("tl", target.ifBlank { "en" })
			.addQueryParameter("q", text).build()

	fun response(body: String): String {
		val root = runCatching { JSONArray(body) }.getOrNull()
			?: throw TranslateException.Parse("Google Translate: not JSON")
		val segments = root.optJSONArray(0) ?: throw TranslateException.Parse("Google Translate: missing segments")
		val result = buildString {
			for (i in 0 until segments.length()) {
				val segment = segments.optJSONArray(i)
				val translated = segment?.opt(0) as? String
				if (translated == null) throw TranslateException.Parse("Google Translate: missing translated segment")
				append(translated)
			}
		}
		return result.takeIf { it.isNotBlank() && it != "null" }
			?: throw TranslateException.Parse("Google Translate: empty text")
	}
}
