package org.koitharu.kotatsu.reader.ui.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonImageView

@RunWith(AndroidJUnit4::class)
class ReaderPresentationTest {
    @Test fun rotationUsesNativeZoomCoordinatesWithoutChangingBitmapDimensions() = onMain {
        val image = SubsamplingScaleImageView(context())
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvasBitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        try {
            image.maxScale = 20f
            image.measure(exact(400), exact(800)); image.layout(0, 0, 400, 800)
            image.orientation = 90
            image.setImage(ImageSource.cachedBitmap(bitmap))
            repeat(3) { image.draw(Canvas(canvasBitmap)) }
            assertTrue(image.isReady)
            assertEquals(200, image.sWidth); assertEquals(100, image.sHeight)
            assertEquals(100, image.displaySourceWidth); assertEquals(200, image.displaySourceHeight)
            image.setScaleAndCenter(6f, PointF(50f, 100f))
            image.draw(Canvas(canvasBitmap))
            val marker = image.normalizedSourceToView(0.5f, 0.5f)!!
            assertEquals(200f, marker.x, 1f); assertEquals(400f, marker.y, 1f)
            assertTrue(image.scale > 4f)
            image.orientation = 270
            image.setScaleAndCenter(6f, PointF(50f, 100f))
            image.draw(Canvas(canvasBitmap))
            val second = image.normalizedSourceToView(0.5f, 0.5f)!!
            assertEquals(marker.x, second.x, 1f); assertEquals(marker.y, second.y, 1f)
            assertFalse(bitmap.isRecycled)
        } finally { image.recycle(); canvasBitmap.recycle(); bitmap.recycle() }
    }
    @Test fun webtoonMeasuresOrientedPageAndKeepsPortraitLongScrollGeometry() = onMain {
        val image = WebtoonImageView(context())
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        try {
            image.orientation = 90
            image.setImage(ImageSource.cachedBitmap(bitmap))
            image.measure(exact(200), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
            assertEquals(400, image.measuredHeight)
            assertEquals(200, image.displaySourceWidth)
            image.measure(exact(200), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals(400, image.measuredHeight)
            image.measure(exact(200), View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.AT_MOST))
            assertEquals(250, image.measuredHeight)
            image.measure(exact(200), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.AT_MOST))
            assertEquals(0, image.measuredHeight)
        } finally { image.recycle(); bitmap.recycle() }
    }
    @Test fun markerAndActionCoordinatesRoundTripThroughAncestorZoomAndTranslation() = onMain {
        val root = FrameLayout(context())
        val parent = FrameLayout(root.context)
        val page = View(root.context)
        root.addView(parent, FrameLayout.LayoutParams(300, 500))
        parent.addView(page, FrameLayout.LayoutParams(100, 200))
        root.measure(exact(800), exact(1000)); root.layout(0, 0, 800, 1000)
        parent.translationX = 70f; parent.translationY = 40f; parent.scaleX = 1.5f; parent.scaleY = 1.5f
        page.translationX = 20f; page.translationY = 10f; page.scaleX = 2f; page.scaleY = 2f
        val coordinates = floatArrayOf(25f, 60f)
        page.matrixToScreen().mapPoints(coordinates)
        val local = page.screenToLocal(coordinates[0], coordinates[1])!!
        assertEquals(25f, local.x, 0.001f); assertEquals(60f, local.y, 0.001f)
    }
    @Test fun animatedRotationKeepsAnimationAndDrawableCallbacks() = onMain {
        val original = AnimatedFixture()
        val rotated = RotatedPageDrawable(original, 90)
        var invalidations = 0
        rotated.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { invalidations++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        assertEquals(100, rotated.intrinsicWidth); assertEquals(200, rotated.intrinsicHeight)
        rotated.start(); assertTrue(original.isRunning)
        original.invalidateSelf(); assertTrue(invalidations > 0)
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        try {
            rotated.setBounds(0, 0, 100, 200); rotated.draw(Canvas(bitmap))
            assertEquals(Color.RED, bitmap.getPixel(50, 100))
        } finally { bitmap.recycle() }
        rotated.stop(); assertFalse(original.isRunning)
    }
    private class AnimatedFixture : Drawable(), Animatable {
        private var running = false
        override fun getIntrinsicWidth() = 200
        override fun getIntrinsicHeight() = 100
        override fun draw(canvas: Canvas) { canvas.drawColor(Color.RED) }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun getOpacity() = PixelFormat.OPAQUE
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun isRunning() = running
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun context() = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_Kotatsu)
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
