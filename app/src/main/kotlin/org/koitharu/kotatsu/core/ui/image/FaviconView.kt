package org.koitharu.kotatsu.core.ui.image

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.withStyledAttributes
import coil3.Image
import coil3.asDrawable
import coil3.asImage
import coil3.request.Disposable
import coil3.target.Target
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler.Companion.suppressCaptchaErrors
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.util.ext.faviconCacheOnly
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class FaviconView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : CoilImageView(context, attrs, defStyleAttr) {

	@StyleRes
	private var iconStyle: Int = R.style.FaviconDrawable
	private val weakTarget = WeakFaviconTarget(this)

	init {
		context.withStyledAttributes(attrs, R.styleable.FaviconView, defStyleAttr) {
			iconStyle = getResourceId(R.styleable.FaviconView_iconStyle, iconStyle)
		}
		if (isInEditMode) {
			setImageDrawable(
				FaviconDrawable(
					context = context,
					styleResId = iconStyle,
					name = context.getString(R.string.app_name).random().toString(),
				),
			)
		}
	}

	override val imageRequestContext: Context
		get() = context.applicationContext

	fun setImageAsync(mangaSource: MangaSource, cacheOnly: Boolean = false): Disposable {
		val sourceTitle = mangaSource.getTitle(context)
		val fallbackImage: Image = FaviconDrawable(context, iconStyle, sourceTitle).asImage()
		val placeholderImage: Image = if (context.isAnimationsEnabled) {
			AnimatedFaviconDrawable(context, iconStyle, sourceTitle).asImage()
		} else {
			fallbackImage
		}
		return enqueueRequest(
			newRequestBuilder()
				.data(mangaSource.faviconUri())
				.size(resolveRequestSize())
				.target(weakTarget)
				.error(fallbackImage)
				.fallback(fallbackImage)
				.placeholder(placeholderImage)
				.faviconCacheOnly(cacheOnly)
				.mangaSourceExtra(mangaSource)
				.suppressCaptchaErrors()
				.build(),
		)
	}

	private fun resolveRequestSize(): Int {
		val params = layoutParams
		val size = maxOf(
			params?.width ?: 0,
			params?.height ?: 0,
			width,
			height,
			minimumWidth,
			minimumHeight,
		)
		if (size > 0) {
			return size
		}
		return (context.resources.displayMetrics.density * DEFAULT_REQUEST_SIZE_DP).roundToInt()
	}

	private companion object {

		private const val DEFAULT_REQUEST_SIZE_DP = 40f
	}

	private class WeakFaviconTarget(
		view: FaviconView,
	) : Target {

		private val viewRef = WeakReference(view)

		override fun onStart(placeholder: Image?) {
			viewRef.get()?.let { view ->
				view.setImageDrawable(placeholder?.asDrawable(view.resources))
			}
		}

		override fun onError(error: Image?) {
			viewRef.get()?.let { view ->
				view.setImageDrawable(error?.asDrawable(view.resources))
			}
		}

		override fun onSuccess(result: Image) {
			viewRef.get()?.let { view ->
				view.setImageDrawable(result.asDrawable(view.resources))
			}
		}
	}
}
