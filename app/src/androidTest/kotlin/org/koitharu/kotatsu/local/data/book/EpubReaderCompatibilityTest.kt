package org.koitharu.kotatsu.local.data.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Before
import org.koitharu.kotatsu.settings.SettingsActivity
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.ArchiveBitmapDecoder
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.reader.ui.novel.NovelHtml
import org.koitharu.kotatsu.reader.ui.novel.NovelReaderSettings
import org.koitharu.kotatsu.reader.ui.novel.NovelReaderView
import org.koitharu.kotatsu.reader.ui.novel.parseNovelImages
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/** Uses the same text capability/reflow/image paths as the reader; no source or translator requests. */
@HiltAndroidTest
class EpubReaderCompatibilityTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Before fun inject() {
        hilt.inject()
        org.koitharu.kotatsu.core.archive.ArchiveAccess.get(ApplicationProvider.getApplicationContext())
    }

    @Test fun epubNavigationIllustrationsAndReflowRetainStableChapterAndResumeAnchors() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("epub-reader-compatibility-", ".epub", context.cacheDir)
        try {
            write(file)
            val original = file.readBytes()
            val manga = LocalMangaParser(file).getManga(true).manga
            val chapters = requireNotNull(manga.chapters)
            assertEquals(listOf("Opening 日本語", "Second chapter"), chapters.map { it.title })
            val html = LocalMangaParser(chapters.first().url.toUri()).getChapterHtml(chapters.first())
            val text = NovelHtml.toPlainText(NovelHtml.decodeChapterHtml(html))
            val parsed = parseNovelImages(text)
            assertEquals(1, parsed.blockImagePaths.size)
            assertFalse(html.contains("https://untrusted.invalid"))
            assertFalse(html.contains("<script"))
            assertTrue(text.contains("Paragraph 150"))
            assertTrue(parsed.blockImagePaths.single().startsWith("file+zip:"))
            ArchiveBitmapDecoder.decode(context, parsed.blockImagePaths.single().toUri()).let { image ->
                try {
                    assertEquals(16, image.width)
                    assertEquals(12, image.height)
                    assertEquals(Color.MAGENTA, image.getPixel(4, 4))
                } finally { image.recycle() }
            }
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val themed = ContextThemeWrapper(activity, R.style.Theme_Kotatsu)
                val settings = NovelReaderSettings(fontSizeSp = 14f, showReadingStatus = false)
                val view = NovelReaderView(themed)
                view.updateSettings(settings)
                measure(view, 600, 900)
                view.setContent(text, suppressNotification = true, initialProgressRatio = 0.55f)
                assertTrue(view.getTotalPages() > 5)
                val offset = view.getCurrentCharOffset()
                assertTrue(offset > text.length / 3)
                assertTrue(offset < text.length * 2 / 3)
                val savedRatio = view.getProgressRatio()
                view.updateSettings(settings.copy(fontSizeSp = 24f))
                measure(view, 900, 600)
                assertTrue(abs(view.getCurrentCharOffset() - offset) < text.length / 10)
                val recreated = NovelReaderView(themed)
                recreated.updateSettings(settings.copy(fontSizeSp = 24f))
                measure(recreated, 900, 600)
                recreated.setContent(text, suppressNotification = true, initialProgressRatio = savedRatio)
                assertTrue(abs(recreated.getCurrentCharOffset() - offset) < text.length / 10)
                view.clearSessionContent()
                recreated.clearSessionContent()
            } }
            assertEquals(chapters.map { it.id }, LocalMangaParser(file).getManga(true).manga.chapters!!.map { it.id })
            assertTrue(original.contentEquals(file.readBytes()))
        } finally { file.delete() }
    }

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun write(file: File) {
        val text = (1..200).joinToString("") {
            "<p>Paragraph $it. The reader keeps this place through a changed font and viewport. 日本語 العربية.</p>"
        }
        val data = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "OPS/book.opf" to """<package version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Compatibility fixture</dc:title></metadata><manifest><item id="one" href="first.xhtml" media-type="application/xhtml+xml"/><item id="two" href="second.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="image" href="illustration.png" media-type="image/png"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""".toByteArray(),
            "OPS/nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="first.xhtml#opening">Opening 日本語</a></li><li><a href="second.xhtml">Second chapter</a></li></ol></nav></body></html>""".toByteArray(),
            "OPS/first.xhtml" to ("<html><body><h1 id=\"opening\">Opening</h1><img src=\"illustration.png\"/>" +
                text + "<script>alert('blocked')</script><img src=\"https://untrusted.invalid/image.png\"/></body></html>").toByteArray(),
            "OPS/second.xhtml" to "<html><body><p>Second chapter.</p></body></html>".toByteArray(),
        )
        val image = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.MAGENTA)
            data["OPS/illustration.png"] = ByteArrayOutputStream().use { output ->
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { image.recycle() }
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                data.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipParameters().apply { fileNameInZip = name })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        } finally { data.values.forEach { it.fill(0) } }
    }
}
