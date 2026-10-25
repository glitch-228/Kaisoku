package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.content.withStyledAttributes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.Image
import coil3.ImageLoader
import coil3.asDrawable
import coil3.asImage
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowRgb565
import coil3.request.lifecycle
import coil3.request.target
import coil3.request.transitionFactory
import coil3.size.Scale
import coil3.size.Size
import coil3.size.SizeResolver
import coil3.target.ViewTarget
import coil3.transition.TransitionTarget
import coil3.transition.Transition
import coil3.util.CoilUtils
import com.google.android.material.imageview.ShapeableImageView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.util.ext.decodeRegion
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getAnimationDuration
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.isNetworkError
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.lang.ref.WeakReference
import java.util.LinkedList
import javax.inject.Inject

@AndroidEntryPoint
open class CoilImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
) : ShapeableImageView(context, attrs, defStyleAttr), ImageRequest.Listener {

    @Inject
    lateinit var coil: ImageLoader

    @Inject
    lateinit var networkState: NetworkState

    var allowRgb565: Boolean = false
    var useExistingDrawable: Boolean = false
    var decodeRegion: Boolean = false
    var exactImageSize: Size? = null
    var crossfadeDurationFactor: Float = 1f
    var imageRequestLifecycle: Lifecycle? = null
    var isAnimationPlaybackAllowed: Boolean = true
        set(value) {
            field = value
            (drawable as? Animatable)?.let {
                if (value && isAttachedToWindow) it.start() else it.stop()
            }
        }

    var placeholderDrawable: Drawable? = null
    var errorDrawable: Drawable? = null
    var fallbackDrawable: Drawable? = null

    private var currentRequest: Disposable? = null
    private var currentRequestKey: ViewImageRequestKey? = null
    private var networkWaitingJob: Job? = null
    private val weakRequestListener = WeakImageRequestListener(this)

    private var listeners: MutableList<ImageRequest.Listener>? = null

    val isFailed: Boolean
        get() = CoilUtils.result(this) is ErrorResult

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        currentRequest?.job?.cancel()
        networkWaitingJob?.cancel()
        networkWaitingJob = null
    }

    init {
        context.withStyledAttributes(attrs, R.styleable.CoilImageView, defStyleAttr) {
            allowRgb565 = getBoolean(R.styleable.CoilImageView_allowRgb565, allowRgb565)
            useExistingDrawable = getBoolean(R.styleable.CoilImageView_useExistingDrawable, useExistingDrawable)
            decodeRegion = getBoolean(R.styleable.CoilImageView_decodeRegion, decodeRegion)
            placeholderDrawable = getDrawable(R.styleable.CoilImageView_placeholderDrawable)
            errorDrawable = getDrawable(R.styleable.CoilImageView_errorDrawable)
            fallbackDrawable = getDrawable(R.styleable.CoilImageView_fallbackDrawable)
            crossfadeDurationFactor = if (getBoolean(R.styleable.CoilImageView_crossfadeEnabled, true)) {
                crossfadeDurationFactor
            } else {
                0f
            }
        }
    }

    override fun onCancel(request: ImageRequest) {
        super.onCancel(request)
        listeners?.toList()?.forEach { it.onCancel(request) }
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        super.onError(request, result)
        listeners?.toList()?.forEach { it.onError(request, result) }
        if (result.throwable.isNetworkError()) {
            waitForNetwork()
        }
    }

    override fun onStart(request: ImageRequest) {
        super.onStart(request)
        listeners?.toList()?.forEach { it.onStart(request) }
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        super.onSuccess(request, result)
        listeners?.toList()?.forEach { it.onSuccess(request, result) }
    }

    fun addImageRequestListener(listener: ImageRequest.Listener) {
        val list = listeners ?: LinkedList<ImageRequest.Listener>().also { listeners = it }
        list.add(listener)
    }

    fun removeImageRequestListener(listener: ImageRequest.Listener) {
        listeners?.remove(listener)
    }

    fun setImageAsync(@DrawableRes resourceId: Int) = enqueueRequest(
        newRequestBuilder()
            .data(resourceId)
            .build(),
    )

    fun setImageAsync(url: String?) = enqueueRequest(
        newRequestBuilder()
            .data(url)
            .build(),
    )

    fun setImageAsync(url: String, listener: ImageRequest.Listener) = enqueueRequest(
        newRequestBuilder()
            .data(url)
            .listener(WeakImageRequestListener(this, listener))
            .build(),
    )

    open fun setImageAsync(page: ReaderPage) = enqueueRequest(
        newRequestBuilder()
            .data(page.toMangaPage())
            .mangaSourceExtra(page.source)
            .build(),
    )

    fun disposeImage() {
        networkWaitingJob?.cancel()
        networkWaitingJob = null
        currentRequest?.dispose()
        CoilUtils.dispose(this)
        currentRequest = null
        currentRequestKey = null
        setImageDrawable(null)
    }

    fun reload(skipCache: Boolean = false) {
        CoilUtils.result(this)?.let { result ->
            val request = if (skipCache) result.request.newBuilder()
                .memoryCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
                .diskCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
                .build() else result.request
            enqueueRequest(request, force = true)
        }
    }

    protected fun enqueueRequest(request: ImageRequest, force: Boolean = false): Disposable {
        val previous = currentRequest
        val key = ViewImageRequestKey.from(request, exactImageSize)
        if (!force && currentRequestKey == key && previous?.job?.isCancelled == false && !isFailed) {
            return previous
        }
        networkWaitingJob?.cancel()
        networkWaitingJob = null
        previous?.dispose()
        currentRequestKey = key
        return coil.enqueue(request).also { currentRequest = it }
    }

    protected open val imageRequestContext: Context
        get() = context.applicationContext

    protected open fun newRequestBuilder() = ImageRequest.Builder(imageRequestContext).apply {
        val ownerLifecycle = imageRequestLifecycle
            ?: findViewTreeLifecycleOwner()?.lifecycle
            ?: (context.findActivity() as? LifecycleOwner)?.lifecycle
        ownerLifecycle?.let(::lifecycle)
        val crossfadeDuration = if (context.isAnimationsEnabled) {
            (context.getAnimationDuration(R.integer.config_defaultAnimTime) * crossfadeDurationFactor).toInt()
        } else {
            0
        }
        transitionFactory(if (crossfadeDuration > 0) NetworkOnlyCrossfade(crossfadeDuration) else Transition.Factory.NONE)
        if (useExistingDrawable) {
            val previousDrawable = this@CoilImageView.drawable?.asImage()
            if (previousDrawable != null) {
                fallback(previousDrawable)
                placeholder(previousDrawable)
                error(previousDrawable)
            } else {
                setupPlaceholders()
            }
        } else {
            setupPlaceholders()
        }
        if (decodeRegion) {
            decodeRegion(0)
        }
        size(
            exactImageSize?.let {
                SizeResolver(it)
            } ?: WeakViewSizeResolver(this@CoilImageView),
        )
        scale(scaleType.toCoilScale())
        listener(weakRequestListener)
        allowRgb565(allowRgb565)
        target(WeakImageViewTarget(this@CoilImageView))
    }

    private fun ImageRequest.Builder.setupPlaceholders() {
        placeholder(placeholderDrawable?.asImage())
        error(errorDrawable?.asImage())
        fallback(fallbackDrawable?.asImage())
    }

    private fun ScaleType.toCoilScale(): Scale = if (this == ScaleType.CENTER_CROP) {
        Scale.FILL
    } else {
        Scale.FIT
    }

    private fun waitForNetwork() {
        if (networkWaitingJob?.isActive == true || networkState.isOnline()) {
            return
        }
        networkWaitingJob?.cancel()
        networkWaitingJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            networkState.awaitForConnection()
            if (isFailed) {
                reload()
            }
        }
    }

    private class WeakImageRequestListener(
        view: CoilImageView,
        listener: ImageRequest.Listener? = null,
    ) : ImageRequest.Listener {

        private val viewRef = WeakReference(view)
        private val listenerRef = WeakReference(listener)

        override fun onCancel(request: ImageRequest) {
            viewRef.get()?.onCancel(request)
            listenerRef.get()?.onCancel(request)
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            viewRef.get()?.onError(request, result)
            listenerRef.get()?.onError(request, result)
        }

        override fun onStart(request: ImageRequest) {
            viewRef.get()?.onStart(request)
            listenerRef.get()?.onStart(request)
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            viewRef.get()?.onSuccess(request, result)
            listenerRef.get()?.onSuccess(request, result)
        }
    }

    /**
     * Coil owns a request target until every child of a cancelled request finishes. Keeping the
     * target's view weak prevents a slow fetch cancellation from retaining a detached view and its
     * destroyed activity. This still implements [ViewTarget] and [TransitionTarget], so Coil keeps
     * its normal attach/restart, result lookup, and crossfade behaviour while the view is alive.
     */
    private class WeakImageViewTarget(
        view: CoilImageView,
    ) : ViewTarget<CoilImageView>, TransitionTarget, DefaultLifecycleObserver {

        private val viewRef = WeakReference(view)
        private var isStarted = view.isAttachedToWindow

        override val view: CoilImageView
            get() = viewRef.get() ?: throw CancellationException("Image view was released")

        override val drawable: Drawable?
            get() = viewRef.get()?.drawable

        override fun onStart(placeholder: Image?) = updateImage(placeholder)

        override fun onError(error: Image?) = updateImage(error)

        override fun onSuccess(result: Image) = updateImage(result)

        override fun onStart(owner: LifecycleOwner) {
            isStarted = true
            updateAnimation()
        }

        override fun onStop(owner: LifecycleOwner) {
            isStarted = false
            updateAnimation()
        }

        private fun updateImage(image: Image?) {
            val target = viewRef.get() ?: return
            (target.drawable as? Animatable)?.stop()
            target.setImageDrawable(image?.asDrawable(target.resources))
            updateAnimation()
        }

        private fun updateAnimation() {
            val target = viewRef.get() ?: return
            val animation = target.drawable as? Animatable ?: return
            if (isStarted && target.isAnimationPlaybackAllowed && target.isAttachedToWindow) {
                animation.start()
            } else {
                animation.stop()
            }
        }
    }
}
