package org.koitharu.kotatsu.core.util

object KeepAndroidOpenCampaign {
	private val languages = setOf(
		"fr", "es", "ca", "it", "pt", "de", "da", "fi", "nl", "pl", "cs", "sk", "sq", "el",
		"ru", "uk", "hu", "bg", "be", "az", "tr", "kk", "he", "ar", "fa", "vi", "th", "id", "tl",
		"bn", "hi", "ja", "ko", "eu",
	)

	fun url(language: String?): String {
		val code = language.orEmpty().lowercase()
		val base = code.substringBefore('-')
		val path = when {
			base == "zh" && (code == "zh" || code.endsWith("cn") || code.endsWith("hans")) -> "zh-CN/"
			base == "zh" -> "zh-TW/"
			base in languages -> "$base/"
			else -> ""
		}
		return "https://keepandroidopen.org/$path"
	}
}
