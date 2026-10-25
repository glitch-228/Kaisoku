package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.ChapterTextRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class MihonNovelRepository(
    private val loaded: MihonExtensionManager.LoadedSource,
    cache: MemoryContentCache,
    appSettings: AppSettings,
) : MihonMangaRepository(loaded, cache, appSettings), ChapterTextRepository {
    private val novel = requireNotNull(loaded.sourceInstance as? NovelSource)
    private val requests = MultiMutex<String>()
    private val chapters = LinkedHashMap<String, String>(8, 0.75f, true)
    private var cachedCharacters = 0

    override suspend fun getChapterHtml(chapter: MangaChapter): String = requests.withLock(chapter.url) {
        synchronized(chapters) { chapters[chapter.url] }?.let { return@withLock it }
        val html = withContext(Dispatchers.IO) {
            val site = (loaded.sourceInstance as? HttpSource)?.baseUrl.orEmpty()
            val base = site.toHttpUrlOrNull()?.resolve(chapter.url)?.toString() ?: site
            val original = SChapter.create().also {
                it.url = chapter.url
                it.name = chapter.title ?: chapter.name
                it.chapter_number = chapter.number
                it.date_upload = chapter.uploadDate
                it.scanlator = chapter.scanlator
            }
            fetchMihonChapterHtml(novel, original, base)
        }
        synchronized(chapters) {
            while (chapters.isNotEmpty() && (chapters.size >= 8 || cachedCharacters + html.length > MAX_CACHE_CHARS)) {
                val oldest = chapters.entries.iterator()
                cachedCharacters -= oldest.next().value.length
                oldest.remove()
            }
            if (html.length <= MAX_CACHE_CHARS) {
                chapters.put(chapter.url, html)?.let { cachedCharacters -= it.length }
                cachedCharacters += html.length
            }
        }
        html
    }

    override suspend fun getImageHeaders(): Map<String, String> = withContext(Dispatchers.IO) {
        mihonExtensionCall { (loaded.sourceInstance as? HttpSource)?.headers?.toMap().orEmpty() }
    }

    override fun createPageRequest(pageUrl: String, page: MangaPage): Request {
        val http = loaded.sourceInstance as? HttpSource ?: return super.createPageRequest(pageUrl, page)
        return mihonExtensionCall {
            http.imageRequest(Page(index = 0, url = pageUrl, imageUrl = pageUrl)).newBuilder()
                .tag(MangaSource::class.java, page.source).build()
        }
    }

    companion object {
        private const val MAX_CACHE_CHARS = 4 * 1024 * 1024
    }
}

private const val MAX_NOVEL_CHAPTER_CHARS = 8 * 1024 * 1024
private const val MAX_NOVEL_TEXT_PAGES = 64

internal suspend fun fetchMihonChapterHtml(source: NovelSource, chapter: SChapter, baseUrl: String): String =
    mihonExtensionCall {
    currentCoroutineContext().ensureActive()
    val pages = if (usesDirectMihonNovelText(source.javaClass.name)) {
        // These inspected wrappers explicitly leave enumeration unsupported.
        listOf(Page(index = 0, url = chapter.url))
    } else {
        val pageSource = source as? Source ?: throw IOException("Novel source does not support chapter discovery")
        pageSource.getPageList(chapter)
    }
    currentCoroutineContext().ensureActive()
    if (pages.isEmpty()) throw IOException("Novel source returned no chapter pages")
    if (pages.size > MAX_NOVEL_TEXT_PAGES) throw IOException("Novel chapter has too many text pages")
    val result = StringBuilder()
    var rawCharacters = 0
    for (page in pages) {
        currentCoroutineContext().ensureActive()
        val raw = source.fetchPageText(page)
        currentCoroutineContext().ensureActive()
        if (raw.length > MAX_NOVEL_CHAPTER_CHARS - rawCharacters) {
            throw IOException("Novel chapter text is too large")
        }
        rawCharacters += raw.length
        val pageBase = baseUrl.toHttpUrlOrNull()?.resolve(page.url)?.toString() ?: baseUrl
        val html = normalizeMihonChapterHtml(raw, pageBase)
        val separatorLength = if (result.isEmpty()) 0 else 1
        if (html.length > MAX_NOVEL_CHAPTER_CHARS - result.length - separatorLength) {
            throw IOException("Novel chapter text is too large")
        }
        if (result.isNotEmpty()) result.append('\n')
        result.append(html)
    }
    result.toString()
}

internal fun usesDirectMihonNovelText(className: String): Boolean = when (className) {
    "eu.kanade.tachiyomi.novelextension.all.shosetsu.ShosetsuExtensionAdapter",
    "eu.kanade.tachiyomi.novelextension.en.tsundoku.TsundokuDocs" -> true
    else -> false
}

internal fun normalizeMihonChapterHtml(raw: String, baseUrl: String): String {
    if (raw.isBlank()) throw IOException("Novel source returned no chapter text")
    if (raw.length > MAX_NOVEL_CHAPTER_CHARS) throw IOException("Novel chapter text is too large")
    val doc = Jsoup.parseBodyFragment(raw, baseUrl)
    doc.outputSettings().prettyPrint(false)
    doc.select("img[src]").forEach { image ->
        image.absUrl("src").takeIf(String::isNotBlank)?.let { image.attr("src", it) }
    }
    return doc.body().html().takeIf(String::isNotBlank)
        ?: throw IOException("Novel source returned no chapter text")
}
