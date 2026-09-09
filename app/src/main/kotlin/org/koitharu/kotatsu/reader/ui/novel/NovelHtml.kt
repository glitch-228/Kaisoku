/*
 * Ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

/**
 * Converts the base64 data-URL page produced by the LNReader repository
 * into chapter plain text, keeping image references as inline markers.
 */
object NovelHtml {

	private val IMG_TAG_REGEX = Regex("(?i)<img[^>]+(?:data-src|src)=['\"]([^'\"]+)['\"][^>]*>")

	fun decodeChapterHtml(url: String): String {
		if (url.startsWith("data:", ignoreCase = true)) {
			val commaIndex = url.indexOf(',')
			if (commaIndex != -1) {
				val meta = url.substring(5, commaIndex)
				val data = url.substring(commaIndex + 1)
				return if (meta.contains("base64", ignoreCase = true)) {
					val decoded = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
					String(decoded, Charsets.UTF_8)
				} else {
					data
				}
			}
		}
		return ""
	}

	fun toPlainText(html: String): String = html
		.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
		.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
		.replace(IMG_TAG_REGEX) { m ->
			val src = m.groupValues.getOrNull(1).orEmpty()
			if (src.isNotBlank()) "\n📷 [图片: $src]\n" else ""
		}
		.replace(Regex("(?i)<br\\s*/?>"), "\n")
		.replace(Regex("(?i)</(p|div|h[1-6]|li|blockquote)>"), "\n")
		.replace(Regex("(?i)<(p|div|h[1-6]|li|blockquote)(\\s[^>]*)?>"), "\n")
		.replace(Regex("<[^>]+>"), "")
		.replace("&nbsp;", " ")
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&amp;", "&")
		.replace("&quot;", "\"")
		.trim()
		.lines()
		.filter { it.isNotBlank() }
		.joinToString("\n\n")
}
