package org.koitharu.kotatsu.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isAnimatedImage
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.LayoutPageInfoBinding
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
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
	)
	protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
	protected abstract val ssiv: SubsamplingScaleImageView

	protected val animatedView: CoilImageView? by lazy {
		itemView.findViewById(R.id.animatedView)
	}

	protected val settings: ReaderSettings
		get() = viewModel.settingsProducer.value

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
	private var translationOverlayActive = false

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssiv.addOnImageEventListener(viewModel)
			ssiv.addOnImageEventListener(this@BasePageHolder)
		}
		val clickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> viewModel.retry(
					page = boundData?.toMangaPage() ?: return@OnClickListener,
					isFromUser = true,
				)

				R.id.button_error_details -> viewModel.showErrorDetails(boundData?.url)
			}
		}
		bindingInfo.buttonRetry.setOnClickListener(clickListener)
		bindingInfo.buttonErrorDetails.setOnClickListener(clickListener)
	}

	@CallSuper
	protected open fun onConfigChanged(settings: ReaderSettings) {
		settings.applyBackground(itemView)
		if (settings.applyBitmapConfig(ssiv)) {
			reloadImage()
		} else if (viewModel.state.value is PageState.Shown) {
			onReady()
		}
		ssiv.applyDownSampling(isResumed())
	}

	fun reloadImage(preserveState: Boolean = false) {
		val source = (viewModel.state.value as? PageState.Shown)?.source ?: return
		// On untranslate, keep the current pan/zoom so going back doesn't jump either.
		val viewState = if (preserveState) ssiv.getState() else null
		if (viewState != null) {
			ssiv.setImage(source, null, viewState)
		} else {
			ssiv.setImage(source)
		}
	}

	fun bind(data: ReaderPage) {
		boundData = data
		ssiv.isVisible = true
		animatedView?.isVisible = false
		animatedView?.disposeImage()
		translationOverlayActive = false
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
						translationOverlayActive = true
						// Keep the current pan/zoom: the translated bitmap has the same dimensions,
						// so reusing the view state stops the page from jumping on overlay swap.
						val viewState = ssiv.getState()
						val rendered = ImageSource.cachedBitmap(state.rendered)
						if (viewState != null) {
							ssiv.setImage(rendered, null, viewState)
						} else {
							ssiv.setImage(rendered)
						}
					}
					org.koitharu.kotatsu.reader.translate.PageTranslationState.Idle,
					is org.koitharu.kotatsu.reader.translate.PageTranslationState.Failed -> {
						if (translationOverlayActive) {
							translationOverlayActive = false
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

	override fun onResume() {
		super.onResume()
		ssiv.applyDownSampling(isForeground = true)
		if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
			boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
		}
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
	}

	override fun onDestroy() {
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		translationJob?.cancel()
		translationJob = null
		translationOverlayActive = false
		viewModel.onRecycle()
		ssiv.recycle()
		animatedView?.disposeImage()
	}

	override fun onTrimMemory(level: Int) {
		// TODO
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

	protected open fun onStateChanged(state: PageState) {
		bindingInfo.layoutError.isVisible = state is PageState.Error
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
		val isAnimated = boundData?.url?.isAnimatedImage() == true
		when (state) {
			is PageState.Converting -> {
				bindingInfo.textViewStatus.setText(R.string.processing_)
			}

			is PageState.Empty -> Unit

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
				if (isAnimated) {
					showAnimated(boundData!!, state)
					bindingInfo.layoutProgress.isGone = true
				} else {
					bindingInfo.textViewStatus.setText(R.string.preparing_)
					ssiv.setImage(state.source)
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
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> Unit
		}
	}

	private fun showAnimated(page: ReaderPage, loadedState: PageState.Loaded) {
		ssiv.isVisible = false
		animatedView?.let {
			it.isVisible = true
			it.setImageAsync(page)
		}
		viewModel.state.update { currentState ->
			if (currentState is PageState.Loaded) {
				PageState.Shown(loadedState.source, loadedState.isConverted)
			} else {
				currentState
			}
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
			view.width / view.sWidth.toFloat(),
			view.height / view.sHeight.toFloat(),
		)
	}

	companion object {
		protected const val DEFAULT_MAX_SCALE_MULTIPLIER = 4f
	}
}
