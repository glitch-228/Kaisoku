package org.koitharu.kotatsu.reader.ui.upscale

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.databinding.DialogUpscalePreviewBinding
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.UpscaleEffect
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class UpscalePreviewDialog : AppCompatDialogFragment(), android.content.SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject lateinit var settings: org.koitharu.kotatsu.core.prefs.AppSettings
	private var previewFitScale = 0f
	private val powerReceiver = object : android.content.BroadcastReceiver() {
		override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) = applyPreviewEffect()
	}

	override fun onStart() {
		super.onStart()
		settings.subscribe(this)
		androidx.core.content.ContextCompat.registerReceiver(requireContext(), powerReceiver,
			android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
			androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
		applyPreviewEffect()
	}

	override fun onStop() {
		settings.unsubscribe(this)
		requireContext().unregisterReceiver(powerReceiver)
		super.onStop()
	}

	override fun onSharedPreferenceChanged(prefs: android.content.SharedPreferences?, key: String?) {
		applyPreviewEffect()
	}

	private fun applyPreviewEffect() {
		val b = binding ?: return
		val powerSave = requireContext().getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
		val effect = if (UpscaleEffect.shouldApply(true, settings.isReaderUpscaleEnabled, powerSave, previewFitScale, settings.readerUpscaleConfig)) UpscaleEffect.create(previewFitScale, settings.readerUpscaleConfig) else null
		b.imageEnhanced.setRenderEffect(effect)
		b.textStatus.text = when {
			!settings.isReaderUpscaleEnabled -> getString(R.string.disabled)
			powerSave -> getString(R.string.upscale_power_save)
			settings.readerUpscaleStrength == 0 -> getString(R.string.upscale_strength_value, 0)
			else -> getString(R.string.upscale_threshold_value, settings.readerUpscaleThreshold.toString())
		}
		b.textStatus.isVisible = effect == null
		b.divider.isVisible = effect != null
		b.dividerThumb.isVisible = effect != null
	}

	private val viewModel by activityViewModels<ReaderViewModel>()

	@Inject
	lateinit var pageLoader: PageLoader

	private var binding: DialogUpscalePreviewBinding? = null

	private var clipFraction = 0.5f
	private var bitmap: Bitmap? = null
	private var originalWidth = 0
	private var originalHeight = 0
	private val imageMatrix = Matrix()
	private var isDraggingDivider = false
	private var lastTouchX = 0f
	private var lastTouchY = 0f
	private lateinit var scaleDetector: ScaleGestureDetector

	@SuppressLint("ClickableViewAccessibility")
	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val b = DialogUpscalePreviewBinding.inflate(layoutInflater)
		binding = b
		val cornerRadius = resources.getDimension(R.dimen.margin_normal)
		b.imagesContainer.outlineProvider = object : ViewOutlineProvider() {
			override fun getOutline(view: View, outline: Outline) {
				outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
			}
		}
		b.imagesContainer.clipToOutline = true
		scaleDetector = ScaleGestureDetector(
			requireContext(),
			object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
				override fun onScale(detector: ScaleGestureDetector): Boolean {
					imageMatrix.postScale(
						detector.scaleFactor, detector.scaleFactor,
						detector.focusX, detector.focusY,
					)
					applyMatrix()
					return true
				}
			},
		)
		b.imagesContainer.setOnTouchListener { v, event -> onImageTouch(v, event) }
		b.textSettings.setOnClickListener {
			UpscaleSettingsDialog().show(parentFragmentManager, "upscaleSettings")
		}
		loadPreview()
		return MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.upscale_preview)
			.setView(b.root)
			.setPositiveButton(R.string.close, null)
			.create()
	}

	override fun onDestroyView() {
		binding?.imageOriginal?.setImageDrawable(null)
		binding?.imageEnhanced?.setImageDrawable(null)
		bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
		bitmap = null
		binding = null
		super.onDestroyView()
	}

	private fun onImageTouch(v: View, event: MotionEvent): Boolean {
		val b = binding ?: return false
		scaleDetector.onTouchEvent(event)
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				isDraggingDivider = b.divider.isVisible &&
					abs(event.x - b.divider.translationX) < v.width * 0.08f
				lastTouchX = event.x
				lastTouchY = event.y
			}

			MotionEvent.ACTION_POINTER_DOWN -> isDraggingDivider = false

			MotionEvent.ACTION_MOVE -> if (isDraggingDivider) {
				applyClip((event.x / v.width).coerceIn(0f, 1f))
			} else if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
				imageMatrix.postTranslate(event.x - lastTouchX, event.y - lastTouchY)
				applyMatrix()
				lastTouchX = event.x
				lastTouchY = event.y
			} else {
				lastTouchX = event.x
				lastTouchY = event.y
			}

			MotionEvent.ACTION_UP -> {
				if (event.eventTime - event.downTime < 200) {
					v.performClick()
				}
				isDraggingDivider = false
			}
		}
		return true
	}

	private fun loadPreview() = lifecycleScope.launch {
		val b = binding ?: return@launch
		runCatchingCancellable {
			val page = checkNotNull(viewModel.getCurrentPage()) { "Cannot find current page" }
			val uri = pageLoader.loadPage(page, force = false)
			page.id to runInterruptible(Dispatchers.Default) { decode(uri) }
		}.onSuccess { (pageId, preview) ->
			bitmap = preview.bitmap
			originalWidth = preview.originalWidth
			originalHeight = preview.originalHeight
			b.imagesContainer.doOnLayout { showBitmap(preview.bitmap, pageId) }
		}.onFailure { e ->
			b.progressBar.isVisible = false
			b.textStatus.text = if (e is UnsafePreviewException) {
				getString(R.string.upscale_preview_too_large)
			} else {
				e.getDisplayMessage(resources)
			}
			b.textStatus.isVisible = true
		}
	}

	private fun showBitmap(bitmap: Bitmap, pageId: Long) {
		val b = binding ?: return
		b.progressBar.isVisible = false
		val fitScale = resources.displayMetrics.widthPixels / bitmap.width.toFloat()
		val originalFitScale = resources.displayMetrics.widthPixels / originalWidth.toFloat()
		// start from what the reader is showing right now; fall back to fit-width
		val viewport = UpscaleEffect.viewportFor(pageId)
		val scale = viewport?.scale ?: fitScale
		val sampleX = bitmap.width / originalWidth.toFloat()
		val sampleY = bitmap.height / originalHeight.toFloat()
		val viewportScale = viewport?.scale?.times(originalWidth / bitmap.width.toFloat()) ?: scale
		val cx = viewport?.centerX?.times(sampleX) ?: (bitmap.width / 2f)
		val cy = viewport?.centerY?.times(sampleY) ?: (b.imagesContainer.height / (2f * viewportScale))
		imageMatrix.setScale(viewportScale, viewportScale)
		imageMatrix.postTranslate(
			b.imagesContainer.width / 2f - cx * viewportScale,
			b.imagesContainer.height / 2f - cy * viewportScale,
		)
		b.imageOriginal.setImageBitmap(bitmap)
		b.imageEnhanced.setImageBitmap(bitmap)
		applyMatrix()
		previewFitScale = viewport?.fitScale ?: originalFitScale
		applyPreviewEffect()
		applyClip(clipFraction)
	}

	private fun applyMatrix() {
		val b = binding ?: return
		val bmp = bitmap ?: return
		clampMatrix(bmp, b.imagesContainer.width.toFloat(), b.imagesContainer.height.toFloat())
		b.imageOriginal.imageMatrix = imageMatrix
		b.imageEnhanced.imageMatrix = imageMatrix
	}

	private fun clampMatrix(bmp: Bitmap, viewW: Float, viewH: Float) {
		val values = FloatArray(9)
		imageMatrix.getValues(values)
		var scale = values[Matrix.MSCALE_X]
		val fitScale = min(viewW / bmp.width, viewH / bmp.height)
		val clamped = scale.coerceIn(fitScale * 0.5f, max(fitScale, 1f) * 10f)
		if (clamped != scale) {
			val f = clamped / scale
			imageMatrix.postScale(f, f, viewW / 2f, viewH / 2f)
			imageMatrix.getValues(values)
			scale = clamped
		}
		val imgW = bmp.width * scale
		val imgH = bmp.height * scale
		values[Matrix.MTRANS_X] = clampTranslation(values[Matrix.MTRANS_X], imgW, viewW)
		values[Matrix.MTRANS_Y] = clampTranslation(values[Matrix.MTRANS_Y], imgH, viewH)
		imageMatrix.setValues(values)
	}

	private fun clampTranslation(t: Float, imgSize: Float, viewSize: Float) = if (imgSize <= viewSize) {
		(viewSize - imgSize) / 2f
	} else {
		t.coerceIn(viewSize - imgSize, 0f)
	}

	private fun applyClip(value: Float) {
		val b = binding ?: return
		val width = b.imagesContainer.width
		if (width == 0) {
			return
		}
		clipFraction = value
		val cut = (width * value).toInt()
		b.imageEnhanced.clipBounds = Rect(cut, 0, width, b.imagesContainer.height)
		b.divider.translationX = cut.toFloat()
		b.dividerThumb.translationX = cut - b.dividerThumb.width / 2f
	}

	private fun decode(uri: Uri): PreviewImage {
		val maxPixels = resources.displayMetrics.run {
			widthPixels.toLong() * heightPixels.toLong()
		}.coerceAtLeast(1L)
		val source = if (uri.isZipUri()) {
		ZipFile(uri.schemeSpecificPart).use { zip ->
			val entry = checkNotNull(zip.getEntry(uri.fragment)) { "Missing page in archive" }
			if (entry.size !in 0..MAX_COMPRESSED_BYTES) throw UnsafePreviewException()
			val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_COMPRESSED_BYTES.toInt() + 1) }
			if (bytes.size > MAX_COMPRESSED_BYTES) throw UnsafePreviewException()
			ImageDecoder.createSource(ByteBuffer.wrap(bytes))
		}
	} else {
		ImageDecoder.createSource(File(requireNotNull(uri.path) { "Invalid page uri" }))
	}
		var sourceWidth = 0
		var sourceHeight = 0
		val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
			sourceWidth = info.size.width
			sourceHeight = info.size.height
			val ratio = sourceWidth.toLong() * sourceHeight.toLong() / maxPixels.toDouble()
			val sample = ceil(sqrt(ratio.coerceAtLeast(1.0))).toInt().coerceAtLeast(1)
			decoder.setTargetSampleSize(sample)
			decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
		}
		return PreviewImage(decoded, sourceWidth, sourceHeight)
	}

	private data class PreviewImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)
	private class UnsafePreviewException : IllegalStateException()

	companion object {

		private const val TAG = "UpscalePreviewDialog"
		private const val MAX_COMPRESSED_BYTES = 32L * 1024L * 1024L

		fun show(fm: FragmentManager) {
			if (UpscaleEffect.isSupported) {
				UpscalePreviewDialog().show(fm, TAG)
			}
		}
	}
}
