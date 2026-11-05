package org.koitharu.kotatsu.reader.ui.pager.vm

import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.throttle
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.domain.AnimatedImageDetector
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings

class PageViewModel(
    private val loader: PageLoader,
    val settingsProducer: ReaderSettings.Producer,
    private val networkState: NetworkState,
    private val exceptionResolver: ExceptionResolver,
    private val isWebtoon: Boolean,
    private val isAnimationSupported: Boolean,
) : DefaultOnImageEventListener {

    private val scope = loader.loaderScope + Dispatchers.Main.immediate
    private var job: Job? = null
    private var cachedBounds: Rect? = null
    private var boundPage: MangaPage? = null
    private var loadedUri: Uri? = null
    private var showHiddenAnyway = false
    private var hiddenChangesJob: Job? = null

    val state = MutableStateFlow<PageState>(PageState.Empty)

    fun isLoading() = job?.isActive == true

    /** Whether a hidden page is dropped from the list (the default) rather than kept as a tap-to-show placeholder. */
    val removesHiddenPages: Boolean get() = loader.removesHiddenPages

    fun onBind(page: MangaPage) {
        boundPage = page
        showHiddenAnyway = false
        observeHiddenPages()
        val prevJob = job
        job = scope.launch(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            doLoad(page, force = false)
        }
    }

    fun retry(page: MangaPage, isFromUser: Boolean) {
        val prevJob = job
        job = scope.launch {
            prevJob?.cancelAndJoin()
            val e = (state.value as? PageState.Error)?.error
            if (e != null && ExceptionResolver.canResolve(e)) {
                if (isFromUser) {
                    exceptionResolver.resolve(e, tryAutoResolve = false)
                }
            }
            withContext(Dispatchers.Default) {
                doLoad(page, force = true)
            }
        }
    }

    fun showErrorDetails(url: String?) {
        val e = (state.value as? PageState.Error)?.error ?: return
        exceptionResolver.showErrorDetails(e, url)
    }

    fun onRecycle() {
        state.value = PageState.Empty
        cachedBounds = null
        boundPage = null
        loadedUri = null
        showHiddenAnyway = false
        hiddenChangesJob?.cancel()
        hiddenChangesJob = null
        job?.cancel()
    }

    /** Shows a page that matches a hidden one, for as long as it stays on this screen. */
    fun showHiddenPage() {
        val page = boundPage ?: return
        showHiddenAnyway = true
        retry(page, isFromUser = false)
    }

    /** Beta: hiding or showing pages again takes effect on the page already open, not only on the next one. */
    private fun observeHiddenPages() {
        if (hiddenChangesJob?.isActive == true) return
        hiddenChangesJob = scope.launch(Dispatchers.Default) {
            loader.hiddenPageIds.collect { ids ->
                val page = boundPage ?: return@collect
                val isHidden = !showHiddenAnyway && page.id in ids
                val current = state.value
                if (isHidden && (current is PageState.Loaded || current is PageState.Shown)) {
                    state.value = PageState.Hidden
                } else if (!isHidden && current is PageState.Hidden) {
                    retry(page, isFromUser = false)
                }
            }
        }
    }

    override fun onImageLoaded() {
        state.update { currentState ->
            if (currentState is PageState.Loaded) {
                PageState.Shown(currentState.source, currentState.isConverted, currentState.animationUri)
            } else {
                currentState
            }
        }
    }

    override fun onImageLoadError(e: Throwable) {
        e.printStackTraceDebug()

        state.update { currentState ->
            if (currentState is PageState.Loaded) {
                val uri = (currentState.source as? ImageSource.Uri)?.uri
                if (!currentState.isConverted && uri != null && (e is IOException || e is ImageDecodeException)) {
                    tryConvert(uri, e, currentState.animationUri)
                    PageState.Converting()
                } else {
                    PageState.Error(e)
                }
            } else {
                currentState
            }
        }
    }

    fun onAnimatedImageLoaded(uri: Uri) {
        state.update { current ->
            if (current is PageState.Loaded && current.animationUri == uri) {
                PageState.Shown(current.source, current.isConverted, uri)
            } else current
        }
    }

    fun onAnimationLoadError(uri: Uri) {
        state.update { current ->
            when (current) {
                is PageState.Loaded -> if (current.animationUri == uri) current.copy(animationUri = null) else current
                is PageState.Shown -> if (current.animationUri == uri) {
                    PageState.Loaded(current.source, current.isConverted)
                } else current
                else -> current
            }
        }
    }

    private fun tryConvert(uri: Uri, e: Exception, animationUri: Uri?) {
        val prevJob = job
        job = scope.launch(Dispatchers.Default) {
            prevJob?.join()
            state.value = PageState.Converting()
            try {
                val newUri = loader.convertBimap(uri)
                cachedBounds = if (animationUri == null && settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
                    loader.getTrimmedBounds(newUri)
                } else {
                    null
                }
                state.value = PageState.Loaded(newUri.toImageSource(cachedBounds), true, animationUri)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e2: Throwable) {
                e2.printStackTrace()
                e.addSuppressed(e2)
                state.value = PageState.Error(e)
            }
        }
    }

    @WorkerThread
    private suspend fun doLoad(data: MangaPage, force: Boolean) = coroutineScope {
        state.value = PageState.Loading(null, -1)
        val previewJob = launch {
            val preview = loader.loadPreview(data) ?: return@launch
            state.update {
                if (it is PageState.Loading) it.copy(preview = preview) else it
            }
        }
        try {
            val task = loader.loadPageAsync(data, force)
            val progressObserver = observeProgress(this, task.progressAsFlow())
            val uri = task.await()
            progressObserver.cancelAndJoin()
            previewJob.cancel()
            loadedUri = uri
            if (!showHiddenAnyway && loader.checkHiddenPage(data, uri)) {
                state.value = PageState.Hidden
                return@coroutineScope
            }
            // Keep content metadata when playback is off, so toggling reuses this downloaded file.
            val isAnimated = isAnimationSupported &&
                runInterruptible(Dispatchers.IO) { AnimatedImageDetector.isAnimated(uri) }
            cachedBounds = if (!isAnimated && settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
                loader.getTrimmedBounds(uri)
            } else {
                null
            }
            state.value = PageState.Loaded(uri.toImageSource(cachedBounds), false, uri.takeIf { isAnimated })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.printStackTraceDebug()
            state.value = PageState.Error(e)
            if (e is IOException && !networkState.value) {
                networkState.awaitForConnection()
                retry(data, isFromUser = false)
            }
        }
    }

    private fun observeProgress(scope: CoroutineScope, progress: Flow<Float>) = progress
        .throttle(250)
        .onEach {
            val progressValue = (100 * it).toInt()
            state.update { currentState ->
                if (currentState is PageState.Loading) {
                    currentState.copy(progress = progressValue)
                } else {
                    currentState
                }
            }
        }.launchIn(scope)

    private fun Uri.toImageSource(bounds: Rect?): ImageSource {
        val source = ImageSource.uri(this)
        return if (bounds != null) {
            source.region(bounds)
        } else {
            source
        }
    }
}
