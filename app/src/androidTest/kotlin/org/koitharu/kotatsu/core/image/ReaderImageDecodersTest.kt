package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.reader.ui.pager.displaySourceHeight
import org.koitharu.kotatsu.reader.ui.pager.displaySourceWidth
import org.koitharu.kotatsu.reader.ui.pager.normalizedSourceToView
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ReaderImageDecodersTest {

    @Test
    fun nativeSupportedFilesKeepTheirDecoderAndParallelTiles() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val native = object : StubRegionDecoder() {
            override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
            }
        }
        val decoder = ReaderRegionDecoder(Bitmap.Config.RGB_565, native)
        decoder.init(context, Uri.parse("file:///no-read-needed"))
        val error = AtomicReference<Throwable>()
        val workers = List(2) {
            thread {
                try { decoder.decodeRegion(Rect(0, 0, 4, 4), 1).recycle() }
                catch (failure: Throwable) { error.set(failure) }
            }
        }
        try {
            assertTrue("Native pooling must remain parallel", entered.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.forEach { it.join(5000) }
            decoder.recycle()
        }
        error.get()?.let { throw AssertionError(it) }
        assertEquals(1, native.recycles)
    }

    @Test
    fun rejectedProgressiveAndCmykJpegsKeepRawGeometryAndNonzeroCropCoordinates() {
        for (name in listOf("progressive.jpg", "cmyk.jpg")) withImage(name) { file ->
            val before = file.readBytes()
            val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, RejectingRegionDecoder())
            try {
                assertEquals(Point(96, 64), decoder.init(context, Uri.fromFile(file)))
                assertTrue(decoder.isReady)
                val tile = decoder.decodeRegion(Rect(50, 2, 94, 30), 2)
                try {
                    assertEquals(22, tile.width)
                    assertEquals(14, tile.height)
                    val pixel = tile.getPixel(10, 5)
                    assertTrue("Crop should show green quadrant: $name", Color.green(pixel) > Color.red(pixel) + 80)
                } finally { tile.recycle() }
            } finally { decoder.recycle() }
            assertFalse(decoder.isReady)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test
    fun avifNamedJpegDecodesInBothNativeFactoryModesAndBitmapFallback() = withImage("avif-disguised.jpg") { file ->
        val before = file.readBytes()
        for (pooled in listOf(false, true)) {
            val decoder = ReaderRegionDecoderFactory(Bitmap.Config.RGB_565, pooled).make()
            try {
                assertEquals(Point(96, 64), decoder.init(context, Uri.fromFile(file)))
                val tile = decoder.decodeRegion(Rect(0, 32, 48, 64), 2)
                try {
                    assertEquals(24, tile.width)
                    assertEquals(16, tile.height)
                    assertTrue(Color.blue(tile.getPixel(10, 10)) > 150)
                } finally { tile.recycle() }
            } finally { decoder.recycle() }
        }
        val sampled = ReaderBitmapDecoder(Bitmap.Config.RGB_565, rejectingBitmap()).decode(context, Uri.fromFile(file), 2)
        try {
            assertEquals(48, sampled.width)
            assertEquals(32, sampled.height)
            assertEquals(Bitmap.Config.RGB_565, sampled.config)
        } finally { sampled.recycle() }
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun zipFallbackReopensEntryAndNeverChangesArchiveBytes() = withImage("avif-disguised.jpg") { file ->
        val archive = File.createTempFile("reader-decoder-", ".cbz", context.cacheDir)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("images/a page.jpg"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val before = archive.readBytes()
            val uri = Uri.Builder().scheme("file+zip").path(archive.path).fragment("images/a page.jpg").build()
            val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, RejectingRegionDecoder())
            try {
                assertEquals(Point(96, 64), decoder.init(context, uri))
                decoder.decodeRegion(Rect(1, 2, 49, 34), 2).recycle()
            } finally { decoder.recycle() }
            assertArrayEquals(before, archive.readBytes())
        } finally { archive.delete() }
    }

    @Test
    fun nullNativeTileTriggersOneFallbackAndReleasesOriginalDecoder() = withImage("progressive.jpg") { file ->
        for (pooledRejection in listOf(false, true)) {
            val native = object : StubRegionDecoder() {
                var tiles = 0
                override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
                    tiles++
                    if (pooledRejection) throw ImageDecodeException(null, "image/jpeg", "Fixture pooled null tile")
                    throw IllegalStateException(ReaderRegionDecoder.NULL_TILE_REJECTION)
                }
            }
            val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, native)
            try {
                decoder.init(context, Uri.fromFile(file))
                repeat(3) { decoder.decodeRegion(Rect(0, 0, 48, 32), 2).recycle() }
                assertEquals(1, native.tiles)
                assertEquals(1, native.recycles)
            } finally { decoder.recycle() }
            assertEquals(1, native.tiles)
            assertEquals(1, native.recycles)
        }
    }

    @Test
    fun recoveryRejectsGeometryChangesAfterNativeInitialization() = withImage("progressive.jpg") { file ->
        val native = object : StubRegionDecoder() {
            override fun init(context: Context, uri: Uri) = Point(64, 96)
            override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap =
                throw IllegalStateException(ReaderRegionDecoder.NULL_TILE_REJECTION)
        }
        val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, native)
        try {
            decoder.init(context, Uri.fromFile(file))
            val error = assertThrows(IllegalStateException::class.java) {
                decoder.decodeRegion(Rect(0, 0, 32, 32), 1)
            }
            assertTrue(error.suppressed.any { it.message.orEmpty().contains("changed source dimensions") })
            assertFalse(decoder.isReady)
        } finally { decoder.recycle() }
    }

    @Test
    fun rawExifIsPreservedAndRotatingPlatformAlternateIsRejected() = withImage("rotated-exif.jpg") { file ->
        val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, RejectingRegionDecoder())
        try {
            assertEquals(Point(96, 64), decoder.init(context, Uri.fromFile(file)))
            val tile = decoder.decodeRegion(Rect(0, 0, 48, 32), 1)
            try { assertTrue(Color.red(tile.getPixel(10, 10)) > 150) } finally { tile.recycle() }
        } finally { decoder.recycle() }
        if (Build.VERSION.SDK_INT >= 28) {
            val error = assertThrows(ImageDecodeException::class.java) {
                ReaderBitmapDecoder(Bitmap.Config.ARGB_8888, rejectingBitmap()).decode(context, Uri.fromFile(file), 1)
            }
            assertTrue(error.suppressed.any { it.message.orEmpty().contains("EXIF orientation") })
        }
    }

    @Test
    fun unsupportedCorruptAndOversizedImagesRetainUsefulOriginalRejection() {
        for (name in listOf("unsupported.png", "corrupt.jpg", "oversized.jpg")) withImage(name) { file ->
            val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, RejectingRegionDecoder())
            try {
                val error = assertThrows(ImageDecodeException::class.java) { decoder.init(context, Uri.fromFile(file)) }
                assertTrue("Missing alternate diagnostic: $name", error.suppressed.isNotEmpty())
                assertFalse(decoder.isReady)
            } finally { decoder.recycle() }
        }
    }

    @Test
    fun encodedPayloadLimitIsCheckedWithoutGrowingTheBuffer() = withImage("avif-disguised.jpg") { file ->
        file.appendBytes(ByteArray(ReaderDecodeLimits.MAX_ENCODED_BYTES + 1))
        val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, RejectingRegionDecoder())
        try {
            val error = assertThrows(ImageDecodeException::class.java) { decoder.init(context, Uri.fromFile(file)) }
            assertTrue(error.suppressed.any { it.message.orEmpty().contains("encoded-byte budget") })
        } finally { decoder.recycle() }
    }

    @Test
    fun unrelatedIoErrorsAndCancellationDoNotInvokeCompatibility() {
        val original = FileNotFoundException("fixture")
        val native = object : StubRegionDecoder() {
            override fun init(context: Context, uri: Uri): Point = throw original
        }
        val decoder = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, native)
        try {
            assertSame(original, assertThrows(FileNotFoundException::class.java) {
                decoder.init(context, Uri.parse("file:///missing"))
            })
            assertEquals(0, original.suppressed.size)
        } finally { decoder.recycle() }
        val interrupted = ReaderRegionDecoder(Bitmap.Config.ARGB_8888, RejectingRegionDecoder())
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                interrupted.init(context, Uri.parse("file:///must-not-open"))
            }
        } finally {
            Thread.interrupted()
            interrupted.recycle()
        }
    }

    @Test
    fun factoryInstallationRunsEvenWhenDefaultConfigAlreadyMatches() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = SubsamplingScaleImageView(context)
            try {
                assertTrue(ReaderImageDecoders.install(view, Bitmap.Config.RGB_565))
                assertTrue(view.regionDecoderFactory is ReaderRegionDecoderFactory)
                assertTrue(view.bitmapDecoderFactory is ReaderBitmapDecoderFactory)
                assertFalse(ReaderImageDecoders.install(view, Bitmap.Config.RGB_565))
                assertTrue(ReaderImageDecoders.install(view, Bitmap.Config.ARGB_8888))
            } finally { view.recycle() }
        }
    }

    @Test
    fun actualTiledViewKeepsCropRotationZoomAndMarkerCoordinates() = withImage("progressive.jpg") { file ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val failure = AtomicReference<Throwable>()
        val loaded = AtomicBoolean(false)
        val pixels = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        lateinit var view: SubsamplingScaleImageView
        instrumentation.runOnMainSync {
            view = SubsamplingScaleImageView(context)
            view.maxScale = 20f
            view.regionDecoderFactory = object : DecoderFactory<ImageRegionDecoder> {
                override val bitmapConfig = Bitmap.Config.ARGB_8888
                override fun make() = ReaderRegionDecoder(bitmapConfig, RejectingRegionDecoder())
            }
            view.bitmapDecoderFactory = ReaderBitmapDecoderFactory(Bitmap.Config.ARGB_8888)
            view.addOnImageEventListener(object : DefaultOnImageEventListener {
                override fun onImageLoaded() { loaded.set(true) }
                override fun onImageLoadError(e: Throwable) { failure.set(e) }
                override fun onTileLoadError(e: Throwable) { failure.set(e) }
            })
            val exact = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact)
            view.layout(0, 0, 200, 200)
            view.setImage(ImageSource.file(file).region(Rect(48, 0, 96, 32)))
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!loaded.get() && failure.get() == null && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    view.draw(Canvas(pixels))
                }
                if (!loaded.get()) Thread.sleep(20)
            }
            failure.get()?.let { throw AssertionError(it) }
            assertTrue("Tiled fixture was not loaded", loaded.get())
            instrumentation.runOnMainSync {
                // The async loaded callback may run after the last manual paint.
                view.draw(Canvas(pixels))
                assertEquals(48, view.sWidth)
                assertEquals(32, view.sHeight)
                assertTrue(Color.green(pixels.getPixel(100, 100)) > 150)
                view.orientation = 90
                view.setScaleAndCenter(10f, PointF(16f, 24f))
            }
            // Rotation invalidates tiled layers. Wait for a painted, ready layer before checking its transform.
            var rotatedReady = false
            val rotatedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!rotatedReady && failure.get() == null && System.nanoTime() < rotatedDeadline) {
                instrumentation.runOnMainSync {
                    view.draw(Canvas(pixels))
                    rotatedReady = view.isReady && view.scale > 5f
                }
                if (!rotatedReady) Thread.sleep(20)
            }
            failure.get()?.let { throw AssertionError(it) }
            assertTrue("Rotated tiled fixture was not ready", rotatedReady)
            instrumentation.runOnMainSync {
                assertEquals(32, view.displaySourceWidth)
                assertEquals(48, view.displaySourceHeight)
                val marker = requireNotNull(view.normalizedSourceToView(0.5f, 0.5f))
                assertEquals(100f, marker.x, 1f)
                assertEquals(100f, marker.y, 1f)
                assertTrue(view.scale > 5f)
                assertEquals(10f, view.scale, 0.01f)
            }
        } finally {
            instrumentation.runOnMainSync { view.recycle() }
            pixels.recycle()
        }
    }

    private fun rejectingBitmap() = object : ImageDecoder {
        override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap =
            throw ImageDecodeException(uri.toString(), "image/jpeg", "Fixture native rejection")
    }

    private open class StubRegionDecoder : ImageRegionDecoder {
        var recycles = 0
        override fun init(context: Context, uri: Uri) = Point(96, 64)
        override val isReady get() = recycles == 0
        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = error("Unexpected tile request")
        override fun recycle() { recycles++ }
    }

    private class RejectingRegionDecoder : StubRegionDecoder() {
        override fun init(context: Context, uri: Uri): Point =
            throw ImageDecodeException(uri.toString(), "image/jpeg", "Fixture native rejection")
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withImage(name: String, block: (File) -> Unit) {
        val file = File.createTempFile("reader-decoder-", ".jpg", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("reader_decoder/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            block(file)
        } finally { file.delete() }
    }
}
