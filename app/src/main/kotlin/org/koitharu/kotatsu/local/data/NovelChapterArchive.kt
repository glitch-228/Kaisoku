package org.koitharu.kotatsu.local.data

import org.jsoup.Jsoup
import java.io.IOException

/** HTML and image references written through the existing chapter archive output. */
internal object NovelChapterArchive {
    private const val IMAGE_PREFIX = "kaisoku-page:"

    suspend fun prepare(html: String, storeImage: suspend (String, Int) -> Unit): String {
        val doc = Jsoup.parseBodyFragment(html)
        val images = LinkedHashMap<String, Int>()
        for (image in doc.select("img[src]")) {
            val url = image.attr("src")
            if (url.isBlank()) continue
            val number = images[url] ?: (images.size + 1).also {
                storeImage(url, it)
                images[url] = it
            }
            image.attr("src", "$IMAGE_PREFIX$number")
            image.removeAttr("data-src")
            image.removeAttr("srcset")
        }
        doc.outputSettings().prettyPrint(false)
        return doc.body().html()
    }

    fun resolve(html: String, imageUrls: List<String>): String {
        val doc = Jsoup.parseBodyFragment(html)
        for (image in doc.select("img[src]")) {
            val src = image.attr("src")
            if (!src.startsWith(IMAGE_PREFIX)) continue
            val index = src.removePrefix(IMAGE_PREFIX).toIntOrNull()?.minus(1)
            val url = index?.let(imageUrls::getOrNull)
                ?: throw IOException("Downloaded chapter image is missing: $src")
            image.attr("src", url)
        }
        doc.outputSettings().prettyPrint(false)
        return doc.body().html()
    }
}
