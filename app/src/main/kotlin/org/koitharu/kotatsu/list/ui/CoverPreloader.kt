package org.koitharu.kotatsu.list.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler.Companion.suppressCaptchaErrors
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isPowerSaveMode
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.remote.library.RemoteLibrarySource

/** At most two small covers per idle event; no paging, metadata discovery or CAPTCHA UI. */
internal class CoverPreloader(
    private val owner: LifecycleOwner,
    private val recycler: RecyclerView,
    private val loader: ImageLoader,
    private val settings: AppSettings,
    private val network: NetworkState,
    private val items: () -> List<ListModel>,
) : RecyclerView.OnScrollListener(), DefaultLifecycleObserver {
    private var job: Job? = null
    private val attempted = linkedSetOf<String>()

    init {
        owner.lifecycle.addObserver(this)
        recycler.addOnScrollListener(this)
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) schedule() else job?.cancel()
    }

    fun schedule() {
        job?.cancel()
        if (!eligible()) return
        job = owner.lifecycleScope.launch {
            delay(200)
            if (!eligible()) return@launch
            val end = (recycler.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: return@launch
            if (end < 0) return@launch
            val candidates = items().drop(end + 1).take(6).filterIsInstance<MangaListModel>().filter {
                !it.manga.isLocal && !it.source.name.startsWith(RemoteLibrarySource.PREFIX) &&
                    !settings.isIncognitoModeEnabled(it.manga.isNsfw()) &&
                    it.coverUrl?.startsWith("https://") == true
            }.distinctBy { it.coverUrl }.filter { "${it.source.name}:${it.coverUrl}" !in attempted }.take(2)
            for (item in candidates) {
                if (!eligible()) break
                attempted += "${item.source.name}:${item.coverUrl}"
                if (attempted.size > 64) attempted.remove(attempted.first())
                try {
                    withTimeoutOrNull(4_000) {
                        loader.execute(ImageRequest.Builder(recycler.context)
                            .data(item.coverUrl).mangaSourceExtra(item.source).size(240, 360)
                            .suppressCaptchaErrors().build())
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Speculative work never changes the list's visible error state.
                }
            }
        }
    }

    private fun eligible() = settings.isCoverPreloadEnabled && !settings.isIncognitoModeEnabled &&
        owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
        !recycler.context.isLowRamDevice() && network.isOnline() && !network.isMetered() && !network.isDataSaverEnabled() && !recycler.context.isPowerSaveMode()

    override fun onStop(owner: LifecycleOwner) { job?.cancel() }
    override fun onStart(owner: LifecycleOwner) { schedule() }
    override fun onDestroy(owner: LifecycleOwner) {
        job?.cancel()
        recycler.removeOnScrollListener(this)
        owner.lifecycle.removeObserver(this)
    }
}
