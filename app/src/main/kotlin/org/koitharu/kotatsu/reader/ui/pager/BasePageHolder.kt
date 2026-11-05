package org.koitharu.kotatsu.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.view.MotionEvent
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.databinding.LayoutPageInfoBinding
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.UpscaleEffect
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.doublepage.DoublePageHolder
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState
import org.koitharu.kotatsu.reader.ui.pager.vm.PageViewModel
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonHolder

private fun Context.findActivity(): android.app.Activity {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    error("Holder context is not attached to an Activity")
}

abstract class BasePageHolder<B : ViewBinding>(
    protected val binding: B,
    loader: PageLoader,
    readerSettingsProducer: ReaderSettings.Producer,
    networkState: NetworkState,
    exceptionResolver: ExceptionResolver,
    lifecycleOwner: LifecycleOwner,
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner), DefaultOnImageEventListener, ComponentCallbacks2 {

    protected val viewModel = PageViewModel(
        loader = loader,
        settingsProducer = readerSettingsProducer,
        networkState = networkState,
        exceptionResolver = exceptionResolver,
        isWebtoon = this is WebtoonHolder,
        isAnimationSupported = this !is DoublePageHolder,
    )
    protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
    protected abstract val ssiv: SubsamplingScaleImageView

    protected val animatedView: CoilImageView? by lazy {
        itemView.findViewById(R.id.animatedView)
    }

    protected val settings: ReaderSettings
        get() = viewModel.settingsProducer.value
    private var appliedSettings: ReaderSettings? = null

    val context: Context
        get() = itemView.context

    var boundData: ReaderPage? = null
        private set

    private val translateEntryPoint by lazy {
        EntryPointAccessors.fromActivity(
            context.findActivity(),
            org.koitharu.kotatsu.reader.translate.ReaderPageEntryPoint::class.java,
        )
    }
    private var translationJob: Job? = null
    private var translatedPage: org.koitharu.kotatsu.reader.translate.PageTranslationState.Done? = null
    private var animationListener: AnimatedImageListener? = null
    private var animationRequestUri: Uri? = null
    private var viewportToRestore: PageViewport? = null

    private data class PageViewport(val x: Float, val y: Float, val zoom: Float)

    protected val isAnimatedPageDisplayed: Boolean
        get() = ssiv.isGone && animatedView?.isVisible == true
    private val translationMarkers by lazy { itemView.findViewById<TranslationMarkerOverlay>(R.id.translation_markers) }

    init {
        lifecycleScope.launch(Dispatchers.Main) {
            ssiv.bindToLifecycle(this@BasePageHolder)
            ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
            ssiv.addOnImageEventListener(viewModel)
            ssiv.addOnImageEventListener(this@BasePageHolder)
        }
        val clickListener = View.OnClickListener { v ->
            when (v.id) {
                R.id.button_retry -> if (viewModel.state.value is PageState.Hidden) {
                    viewModel.showHiddenPage()
                } else {
                    viewModel.retry(
                        page = boundData?.toMangaPage() ?: return@OnClickListener,
                        isFromUser = true,
                    )
                }

                R.id.button_error_details -> viewModel.showErrorDetails(boundData?.url)
            }
        }
        bindingInfo.buttonRetry.setOnClickListener(clickListener)
        bindingInfo.buttonErrorDetails.setOnClickListener(clickListener)
        animatedView?.apply {
            imageRequestLifecycle = lifecycle
            crossfadeDurationFactor = 0f
            isAnimationPlaybackAllowed = false
        }
    }

    @CallSuper
    protected open fun onConfigChanged(settings: ReaderSettings) {
        val previous = appliedSettings
        appliedSettings = settings
        if (previous?.copy(isUpscaleEnabled = settings.isUpscaleEnabled, upscaleConfig = settings.upscaleConfig) == settings) {
            applyUpscale()
            return
        }
        if (previous != null && viewModel.settingsProducer.zoomMemory.isEnabled) viewportToRestore = captureViewport()
        if (previous != null && previous.widePageRotation != settings.widePageRotation) {
            onPagePresentationChanging()
            viewportToRestore = captureViewport()
            if (isAnimatedPageDisplayed) applyAnimatedRotation()
        }
        settings.applyBackground(itemView)
        animatedView?.colorFilter = settings.colorFilter?.toColorFilter()
        val bitmapConfigChanged = settings.applyBitmapConfig(ssiv)
        if (previous != null && previous.isAnimatedPagesEnabled != settings.isAnimatedPagesEnabled) {
            reloadImage(preserveState = true)
        } else if (bitmapConfigChanged) {
            reloadImage(preserveState = true)
        } else if (viewModel.state.value is PageState.Shown && !isAnimatedPageDisplayed) {
            onReady()
        }
        ssiv.applyDownSampling(isResumed())
        syncAnimation()
        applyUpscale()
    }

    fun reloadImage(preserveState: Boolean = false) {
        val loaded = when (val state = viewModel.state.value) {
            is PageState.Loaded -> state
            is PageState.Shown -> PageState.Loaded(state.source, state.isConverted, state.animationUri)
            else -> return
        }
        val translation = translatedPage
        when {
            translation != null -> showTranslatedPage(translation)
            shouldShowAnimation(loaded) -> showAnimated(loaded)
            else -> showStaticPage(loaded.source, preserveState)
        }
    }

    private fun shouldShowAnimation(state: PageState.Loaded): Boolean =
        settings.isAnimatedPagesEnabled && state.animationUri != null && animatedView != null

    private fun showStaticPage(source: ImageSource, preserveState: Boolean) {
        if (isAnimatedPageDisplayed || preserveState) onPagePresentationChanging()
        if (preserveState && !isAnimatedPageDisplayed) viewportToRestore = captureViewport()
        stopAnimation()
        ssiv.isVisible = true
        settings.applyBitmapConfig(ssiv)
        ssiv.setImage(source)
    }

    private fun captureViewport(): PageViewport? {
        if (this is WebtoonHolder || !ssiv.isReady || ssiv.displaySourceWidth <= 0 || ssiv.displaySourceHeight <= 0) return null
        val center = ssiv.getCenter() ?: return null
        val fit = minOf(ssiv.width.toFloat() / ssiv.displaySourceWidth, ssiv.height.toFloat() / ssiv.displaySourceHeight)
        if (fit <= 0f || !ssiv.scale.isFinite()) return null
        val (x, y) = PageRotation.toOriginal(center.x / ssiv.displaySourceWidth, center.y / ssiv.displaySourceHeight, ssiv.orientation)
        return PageViewport(x, y, ssiv.scale / fit)
    }

    final override fun onReady() {
        if (!ssiv.isReady || isAnimatedPageDisplayed) return
        val rotation = PageRotation.orientation(ssiv.sWidth, ssiv.sHeight, settings.widePageRotation)
        if (ssiv.orientation != rotation) ssiv.orientation = rotation
        onPageReady()
        val viewport = viewportToRestore
        if (viewport != null) {
            val (x, y) = PageRotation.toDisplay(viewport.x, viewport.y, rotation)
            val fit = minOf(ssiv.width.toFloat() / ssiv.displaySourceWidth, ssiv.height.toFloat() / ssiv.displaySourceHeight)
            ssiv.setScaleAndCenter((viewport.zoom * fit).coerceIn(ssiv.minScale, ssiv.maxScale.coerceAtLeast(ssiv.minScale)),
                PointF(x * ssiv.displaySourceWidth, y * ssiv.displaySourceHeight))
        } else restoreNewPageZoom()
        viewportToRestore = null
        ssiv.post { applyUpscale() }
    }

    protected abstract fun onPageReady()

    protected open fun restoreNewPageZoom() = Unit

    protected open fun onPageTouch(event: MotionEvent) = Unit

    private fun listenToPageTouches() {
        ssiv.setOnTouchListener { _, event ->
            onPageTouch(event)
            if (translatedPage?.overflow?.isNotEmpty() == true) translationMarkers?.invalidate()
            false
        }
    }

    private fun applyAnimatedRotation() {
        val view = animatedView ?: return
        val current = view.drawable ?: return
        val original = (current as? RotatedPageDrawable)?.source ?: current
        val rotation = PageRotation.orientation(original.intrinsicWidth, original.intrinsicHeight, settings.widePageRotation)
        val desired = if (rotation == 0) original else {
            if (current is RotatedPageDrawable && current.rotation == rotation) current else RotatedPageDrawable(original, rotation)
        }
        if (view.drawable !== desired) view.setImageDrawable(desired)
        onAnimatedImageShown(desired.intrinsicWidth, desired.intrinsicHeight)
        syncAnimation()
    }

    private fun showTranslatedPage(state: org.koitharu.kotatsu.reader.translate.PageTranslationState.Done) {
        translationMarkers?.showMarkers(ssiv, state.overflow) { number ->
            (context.findActivity() as? org.koitharu.kotatsu.reader.ui.ReaderActivity)
                ?.showTranslatedText(boundData?.id ?: return@showMarkers, number)
        }
        listenToPageTouches()
        showStaticPage(ImageSource.cachedBitmap(state.rendered), preserveState = true)
    }

    fun bind(data: ReaderPage) {
        if (boundData?.id != data.id) {
            clearUpscale()
        }
        boundData = data
        viewportToRestore = null
        // Free the previous page before requesting the next — SubsamplingScaleImageView keeps its
        // decoded tiles and bitmap until recycle(), so rapid paging would otherwise hold two
        // full sets of page pixels and displayed frames for +1 frame, reproducing the visual
        // "page glitch" Usagi fixed in 0.0.32-beta2+ via the same call at this same point.
        ssiv.recycle()
        ssiv.isVisible = true
        stopAnimation()
        translatedPage = null
        translationMarkers?.showMarkers(ssiv, emptyList()) {}
        listenToPageTouches()
        viewModel.onBind(data.toMangaPage())
        onBind(data)
        observeTranslationState(data)
    }

    private fun observeTranslationState(data: ReaderPage) {
        translationJob?.cancel()
        val page = data.toMangaPage()
        val coordinator = translateEntryPoint.translationCoordinator()
        val appSettings = translateEntryPoint.appSettings()
        translationJob = lifecycleScope.launch(Dispatchers.Main) {
            coordinator.stateFor(page.id).collectLatest { state ->
                when (state) {
                    is org.koitharu.kotatsu.reader.translate.PageTranslationState.Done -> {
                        translatedPage = state
                        showTranslatedPage(state)
                    }
                    org.koitharu.kotatsu.reader.translate.PageTranslationState.Idle,
                    is org.koitharu.kotatsu.reader.translate.PageTranslationState.Failed -> {
                        if (translatedPage != null) {
                            translatedPage = null
                            translationMarkers?.showMarkers(ssiv, emptyList()) {}
                            listenToPageTouches()
                            reloadImage(preserveState = true)
                        }
                    }
                    org.koitharu.kotatsu.reader.translate.PageTranslationState.Loading -> Unit
                }
            }
        }
        if (appSettings.translateTriggerMode == org.koitharu.kotatsu.reader.translate.TranslateTriggerMode.AUTO_ON_PAGE &&
            appSettings.isPageTranslationEnabled &&
            appSettings.isPageTranslationConfigured
        ) {
            coordinator.requestTranslate(page)
        }
    }

    @CallSuper
    protected open fun onBind(data: ReaderPage) = Unit

    override fun onCreate() {
        super.onCreate()
        context.registerComponentCallbacks(this)
        viewModel.state.observe(this, ::onStateChanged)
        viewModel.settingsProducer.observe(this, ::onConfigChanged)
    }

    override fun onStart() {
        super.onStart()
        // Coil restarts animations of all attached views when the screen becomes visible,
        // so stop them again on pages that are not current
        syncAnimation()
    }

    override fun onResume() {
        super.onResume()
        syncAnimation()
        ssiv.applyDownSampling(isForeground = true)
        if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
            boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
        }
    }

    override fun onPause() {
        super.onPause()
        syncAnimation()
        ssiv.applyDownSampling(isForeground = false)
    }

    override fun onDestroy() {
        stopAnimation()
        clearUpscale()
        context.unregisterComponentCallbacks(this)
        super.onDestroy()
    }

    open fun onAttachedToWindow() {
        syncAnimation()
        applyUpscale()
    }

    open fun onDetachedFromWindow() {
        animatedView?.isAnimationPlaybackAllowed = false
        clearUpscale()
    }

    @CallSuper
    open fun onRecycled() {
        translationJob?.cancel()
        translationJob = null
        translatedPage = null
        clearUpscale()
        viewModel.onRecycle()
        ssiv.recycle()
        stopAnimation()
    }

    override fun onTrimMemory(level: Int) {
        // TODO
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        boundData?.let { translateEntryPoint.translationCoordinator().rerender(it.toMangaPage()) }
    }

    @Deprecated("Deprecated in Java")
    final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

    protected open fun onStateChanged(state: PageState) {
        bindingInfo.layoutError.isVisible = state is PageState.Error || (state is PageState.Hidden && !viewModel.removesHiddenPages)
        bindingInfo.layoutProgress.isGone = state.isFinalState()
        val progress = (state as? PageState.Loading)?.progress ?: -1
        if (progress in 0..100) {
            bindingInfo.progressBar.isIndeterminate = false
            bindingInfo.progressBar.setProgressCompat(progress, true)
            bindingInfo.textViewStatus.text = context.getString(R.string.percent_string_pattern, progress.toString())
        } else {
            bindingInfo.progressBar.isIndeterminate = true
            bindingInfo.textViewStatus.setText(R.string.loading_)
        }
        when (state) {
            is PageState.Converting -> {
                bindingInfo.textViewStatus.setText(R.string.processing_)
            }

            is PageState.Empty -> Unit

            is PageState.Hidden -> {
                // The page slot stays, so page numbers, chapter positions and saved progress do not move
                // By default the list drops this page and nothing is shown; with the placeholder option
                // its place stays, with a tap to show it
                bindingInfo.textViewError.setText(R.string.hidden_page_placeholder)
                bindingInfo.buttonRetry.setText(R.string.hidden_page_show)
                bindingInfo.buttonErrorDetails.isVisible = false
                bindingInfo.layoutError.isVisible = !viewModel.removesHiddenPages
                bindingInfo.progressBar.hide()
                ssiv.recycle()
            }

            is PageState.Error -> {
                val e = state.error
                bindingInfo.textViewError.text = e.getDisplayMessage(context.resources)
                bindingInfo.buttonRetry.setText(
                    ExceptionResolver.getResolveStringId(e).ifZero { R.string.try_again },
                )
                bindingInfo.buttonErrorDetails.isVisible = e.isSerializable()
                bindingInfo.layoutError.isVisible = true
                bindingInfo.progressBar.hide()
            }

            is PageState.Loaded -> {
                val translation = translatedPage
                if (translation != null) {
                    showTranslatedPage(translation)
                } else if (shouldShowAnimation(state)) {
                    showAnimated(state)
                } else {
                    bindingInfo.textViewStatus.setText(R.string.preparing_)
                    // Enforce the configured bitmap config before mounting: onConfigChanged only runs
                    // for holders that are already bound, so a freshly bound or recycled holder would
                    // otherwise decode with whatever config the previous image left on the view.
                    showStaticPage(state.source, preserveState = false)
                }
            }

            is PageState.Loading -> {
                // Skip preview-as-SSIV-source in webtoon mode. The WebtoonImageView
                // sizes itself from the SSIV's sWidth/sHeight, so showing a thumbnail
                // first locks the slot to the thumbnail's dimensions; the second
                // setImage(full) updates minScale via adjustScale() but leaves the
                // existing scale value applied to the (now wider) full image, which
                // renders it at the wrong size. Standard reader is unaffected (it
                // fits the page to the viewport regardless of source dimensions).
                // Only nhentai.net sets MangaPage.preview today, which is exactly
                // where this bug shows up.
                if (this is WebtoonHolder) return
                if (state.preview != null && ssiv.getState() == null) {
                    settings.applyBitmapConfig(ssiv)
                    ssiv.setImage(state.preview)
                }
            }

            is PageState.Shown -> ssiv.post { applyUpscale() }
        }
    }

    private fun applyUpscale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !ssiv.isVisible) {
            return
        }
        if (isAnimatedPageDisplayed) {
            return
        }
        val page = boundData ?: return
        val fitScale = if (ssiv.isReady && ssiv.sWidth > 0) {
            ssiv.width / ssiv.displaySourceWidth.toFloat()
        } else {
            0f
        }
        val powerSaveMode = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val effect = if (UpscaleEffect.shouldApply(true, settings.isUpscaleEnabled, powerSaveMode, fitScale, settings.upscaleConfig)) {
            UpscaleEffect.create(fitScale, settings.upscaleConfig)
        } else {
            null
        }
        UpscaleEffect.registerView(page.id, ssiv, settings.isUpscaleEnabled, settings.upscaleConfig)
        ssiv.setRenderEffect(effect)
        UpscaleEffect.setActive(page.id, effect != null)
    }

    private fun clearUpscale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        ssiv.setRenderEffect(null)
        boundData?.let { UpscaleEffect.unregisterView(it.id, ssiv) }
    }

    private fun showAnimated(loadedState: PageState.Loaded) {
        val view = animatedView ?: return
        val uri = loadedState.animationUri ?: return
        if (animationRequestUri == uri && animationListener != null) {
            syncAnimation()
            return
        }
        stopAnimation()
        animationRequestUri = uri
        val listener = AnimatedImageListener(uri)
        animationListener = listener
        view.colorFilter = settings.colorFilter?.toColorFilter()
        view.isVisible = true
        // Retain the old presentation until decoding succeeds. Shown is reported only on success.
        view.setImageAsync(uri.toString(), listener)
    }

    private fun stopAnimation() {
        val view = animatedView ?: return
        view.isAnimationPlaybackAllowed = false
        animationListener = null
        animationRequestUri = null
        view.isVisible = false
        view.disposeImage()
    }

    private fun syncAnimation() {
        animatedView?.isAnimationPlaybackAllowed = isResumed() && isAnimatedPageDisplayed &&
            settings.isAnimatedPagesEnabled && translatedPage == null
    }

    protected open fun onPagePresentationChanging() = Unit

    protected open fun onAnimatedImageShown(width: Int, height: Int) = Unit

    private inner class AnimatedImageListener(private val uri: Uri) : ImageRequest.Listener {

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            if (animationListener !== this || !settings.isAnimatedPagesEnabled || translatedPage != null) return
            if (animatedView?.drawable !is Animatable || result.image.width <= 0 || result.image.height <= 0) {
                viewModel.onAnimationLoadError(uri)
                return
            }
            onPagePresentationChanging()
            clearUpscale()
            ssiv.isVisible = false
            // Animated pages scroll in the RecyclerView; release the previous tiled bitmap.
            ssiv.recycle()
            applyAnimatedRotation()
            viewModel.onAnimatedImageLoaded(uri)
            syncAnimation()
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            if (animationListener !== this) return
            result.throwable.printStackTraceDebug()
            viewModel.onAnimationLoadError(uri)
        }
    }

    protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
        downSampling = when {
            isForeground || !settings.isReaderOptimizationEnabled -> 1
            BuildConfig.DEBUG -> 32
            context.isLowRamDevice() -> 8
            else -> 4
        }
    }

    protected fun defaultMaxScale(view: SubsamplingScaleImageView): Float {
        return DEFAULT_MAX_SCALE_MULTIPLIER * maxOf(
            view.width / view.displaySourceWidth.toFloat(),
            view.height / view.displaySourceHeight.toFloat(),
        )
    }

    companion object {
        protected const val DEFAULT_MAX_SCALE_MULTIPLIER = 4f
    }
}
