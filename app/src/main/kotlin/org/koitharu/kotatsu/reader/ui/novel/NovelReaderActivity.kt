/*
 * Novel reader activity, trimmed from Kototoro's NovelReaderActivity (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.transition.Fade
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.model.getLocale
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseFullscreenActivity
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivityNovelReaderBinding
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.translate.TranslateException
import org.koitharu.kotatsu.reader.ui.ReaderControlDelegate
import org.koitharu.kotatsu.reader.ui.ChapterBrowserTarget
import org.koitharu.kotatsu.reader.ui.PresentedChapter
import org.koitharu.kotatsu.reader.ui.ReaderChapterBrowser
import org.koitharu.kotatsu.reader.ui.ReaderChapterHaptics
import org.koitharu.kotatsu.reader.ui.tapgrid.TapAction
import org.koitharu.kotatsu.reader.ui.tapgrid.TapGridDispatcher
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import androidx.coordinatorlayout.widget.CoordinatorLayout

@AndroidEntryPoint
class NovelReaderActivity :
    BaseFullscreenActivity<ActivityNovelReaderBinding>(),
    TapGridDispatcher.OnGridTouchListener,
    ReaderControlDelegate.OnInteractionListener,
    NovelChaptersSheet.Callback,
    NovelReaderConfigSheet.Callback {

    @Inject
    lateinit var trackerMatching: org.koitharu.kotatsu.scrobbling.common.data.TrackerMatchingCoordinator

    @Inject
    lateinit var tapGridSettings: TapGridSettings

    @Inject
    lateinit var settings: org.koitharu.kotatsu.core.prefs.AppSettings

    @Inject
    lateinit var mangaRepositoryFactory: org.koitharu.kotatsu.core.parser.MangaRepository.Factory

    private val viewModel by viewModels<NovelReaderViewModel>()

    private lateinit var controlDelegate: ReaderControlDelegate
    private lateinit var chapterBrowser: ReaderChapterBrowser
    private var isUiVisible = true
    private var systemBarsBottomInset: Int = 0
    private var isScrollMode = false
    private var chapterLoadJob: kotlinx.coroutines.Job? = null
    private var preloadJob: kotlinx.coroutines.Job? = null
    private var translationJob: kotlinx.coroutines.Job? = null
    private var speechJob: kotlinx.coroutines.Job? = null
    @Inject
    internal lateinit var speechController: NovelSpeechController
    private var lastLoadedChapterIndex = -1
    private var chapterLoadGeneration = 0L
    private lateinit var scrollLayoutManager: NovelScrollLayoutManager

    private var continuousAdapter: NovelContinuousAdapter? = null

    override val readerMode: org.koitharu.kotatsu.core.prefs.ReaderMode?
        get() = null

    override fun onCreate(savedInstanceState: Bundle?) {
        org.koitharu.kotatsu.core.archive.ArchiveSessionLifecycle.enforceBeforeDisplay()
        super.onCreate(savedInstanceState)
        setContentView(ActivityNovelReaderBinding.inflate(layoutInflater))
        val archiveListener = org.koitharu.kotatsu.core.archive.ArchiveAccess.get(this).sessions.addLockListener { paths ->
            if (viewModel.protectedArchive?.canonicalPath in paths) runOnUiThread {
                viewBinding.root.alpha = 0f
                chapterLoadJob?.cancel()
                preloadJob?.cancel()
                translationJob?.cancel()
                speechController.stop()
                // Keep the real viewport until normal onPause/onStop saves have drained.
                finish()
            }
        }
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { archiveListener.close() }
        })
        supportFragmentManager.setFragmentResultListener(org.koitharu.kotatsu.core.archive.ArchiveUnlockDialog.RESULT_UNLOCKED,
            this) { _, result ->
            if (result.getString(org.koitharu.kotatsu.core.archive.ArchiveUnlockDialog.KEY_FILE) ==
                viewModel.protectedArchive?.canonicalPath) viewModel.retryArchiveUnlock()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        supportActionBar?.title = null
        supportActionBar?.subtitle = getString(R.string.novel_reader_beta)

        controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)
        chapterBrowser = ReaderChapterBrowser(this, viewBinding.root, mangaRepositoryFactory, ::chapterBrowserTarget)
        ReaderChapterHaptics(viewBinding.root, lifecycle, { settings.isReaderChapterHapticsEnabled }) {
            val index = confirmedVisibleChapterIndex() ?: return@ReaderChapterHaptics null
            val mangaId = viewModel.manga.value?.id ?: return@ReaderChapterHaptics null
            val chapter = viewModel.chapters.value.getOrNull(index) ?: return@ReaderChapterHaptics null
            PresentedChapter(mangaId, chapter.id)
        }

        viewBinding.actionsView.listener = this
        viewBinding.actionsView.isSliderEnabled = true
        addMenu()

        setupReaderView()
        setupContinuousScroll()
        isScrollMode = viewModel.readerSettings.value.readingMode == NovelReadingMode.SCROLL
        viewBinding.readerView.isVisible = !isScrollMode
        viewBinding.continuousScrollView.isVisible = isScrollMode

        speechController.state.observe(this) { state ->
            invalidateOptionsMenu()
            state.error?.let { Snackbar.make(viewBinding.root, it, Snackbar.LENGTH_LONG).show() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                viewModel.manga.distinctUntilChangedBy { it?.id }.collectLatest { manga ->
                    if (manga != null && !viewModel.isIncognitoMode) kotlinx.coroutines.withContext(Dispatchers.IO) {
                        trackerMatching.match(manga, incognito = false)
                    }
                }
            }
        }
        trackerMatching.suggestions.observe(this) { suggestion ->
            if (suggestion != null && viewModel.manga.value?.id == suggestion.manga.id && !viewModel.isIncognitoMode &&
                trackerMatching.claimNotice(suggestion)) {
                com.google.android.material.snackbar.Snackbar.make(viewBinding.root,
                    R.string.tracker_match_review, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction(R.string.tracker_match_review_action) {
                        if (trackerMatching.isCurrent(suggestion)) router.showScrobblingSelectorSheet(suggestion.manga,
                            org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.ANILIST, suggestion.targetId)
                    }.show()
            }
        }
        viewModel.manga.observe(this) { manga ->
            if (manga != null) {
                if (speechController.isActive && speechController.state.value.mangaId != manga.id) speechController.stop()
                supportActionBar?.title = manga.title
            }
        }
        viewModel.isUiLoading.observe(this) { showLoading(it) }
        viewModel.chapterRequest.observe(this) { request ->
            if (request != null && request.first >= 0) {
                loadChapter(request.first, force = true)
            }
        }
        viewModel.readerSettings.observe(this) { applySettings(it) }
        viewModel.onError.observeEvent(this) { e ->
            if (!showArchiveUnlock(e)) Snackbar.make(viewBinding.root,
                e.message ?: getString(R.string.error_occurred), Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun getParentActivityIntent(): Intent? {
        val manga = viewModel.manga.value ?: return null
        return AppRouter.detailsIntent(this, manga)
    }

    private fun setupReaderView() {
        with(viewBinding.readerView) {
            imageHeadersProvider = { viewModel.imageHeaders }
            onTapAreaListener = { area -> onGridTouch(area) }
            onChapterChangeRequestListener = { delta -> switchChapterBy(delta) }
            onPageChangeListener = { _, _ -> if (!isScrollMode) updateProgressUi() }
            onImageClickListener = { request -> openInlineImage(request.imagePath) }
        }
    }

    private fun setupContinuousScroll() {
        continuousAdapter = NovelContinuousAdapter(
            settings = viewModel.readerSettings.value,
            imageHeadersProvider = { viewModel.imageHeaders },
            onImageClick = { request -> openInlineImage(request.imagePath) },
            onTap = { _, _, _ -> toggleUiVisibility() },
            onBeforeGeometryChange = {
                continuousAnchor()?.let { restoreContinuousAnchor(it.first, it.second) }
            },
        )
        viewBinding.continuousScrollView.adapter = continuousAdapter
        scrollLayoutManager = NovelScrollLayoutManager(this) { continuousAdapter?.getItems().orEmpty() }
        viewBinding.continuousScrollView.layoutManager = scrollLayoutManager
        viewBinding.continuousScrollView.itemAnimator = null
        viewBinding.continuousScrollView.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {

                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dx != 0 || dy != 0) {
                        continuousAnchor()?.let { anchor ->
                            if (anchor.first != viewModel.currentChapterIndex.value) {
                                stopSpeech()
                                viewModel.switchChapter(anchor.first)
                                preloadBoundary(anchor.first)
                            }
                        }
                    }
                    if (isScrollMode) updateProgressUi()
                }

            },
        )
    }

    private fun applySettings(newSettings: NovelReaderSettings) {
        saveCurrentProgress()
        val anchor = continuousAnchor()
        val modeChanged = isScrollMode != (newSettings.readingMode == NovelReadingMode.SCROLL)
        isScrollMode = newSettings.readingMode == NovelReadingMode.SCROLL
        if (modeChanged) {
            lastLoadedChapterIndex = -1
            continuousAdapter?.clear()
        }

        viewBinding.readerView.updateSettings(newSettings)
        viewBinding.readerView.setDualPageMode(newSettings.enableDualPage && !isScrollMode)
        continuousAdapter?.updateSettings(newSettings)
        if (!modeChanged && isScrollMode && anchor != null) restoreContinuousAnchor(anchor.first, anchor.second)

        if (modeChanged) {
            val index = viewModel.currentChapterIndex.value
            viewBinding.readerView.isVisible = !isScrollMode
            viewBinding.continuousScrollView.isVisible = isScrollMode
            if (index >= 0) {
                loadChapter(index, force = true)
            }
        }
        applyReaderPalette()
    }

    private fun applyReaderPalette() {
        val isDarkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val palette = novelReaderPalette(viewModel.readerSettings.value.themePreset, isDarkTheme)
        viewBinding.readerView.updatePalette(palette)
        continuousAdapter?.updatePalette(palette)
        viewBinding.infoBar.applyColorScheme(isBlackOnWhite = !palette.isDark)
        viewBinding.root.setBackgroundColor(palette.backgroundColor)
    }

    private fun loadChapter(index: Int, force: Boolean = false) {
        if (!force && isScrollMode && continuousAdapter?.getItems()?.any { it.chapterIndex == index } == true) {
            preloadBoundary(index)
            return
        }
        if (!force && lastLoadedChapterIndex == index) return
        if (lastLoadedChapterIndex >= 0) stopSpeech()
        translationJob?.cancel()
        val generation = ++chapterLoadGeneration
        chapterLoadJob?.cancel()
        preloadJob?.cancel()
        chapterLoadJob = lifecycleScope.launch {
            showLoading(true)
            try {
                val text = withContext(Dispatchers.IO) { viewModel.loadChapterText(index) }
                ensureActive()
                if (generation != chapterLoadGeneration) return@launch
                if (text != null) {
                    if (text.isNotBlank()) {
                        lastLoadedChapterIndex = index
                        renderChapter(index, text)
                    } else {
                        showEmptyChapter(index)
                    }
                } else {
                    showEmptyChapter(index)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == chapterLoadGeneration && !showArchiveUnlock(e)) showError(e, index)
            } finally {
                if (generation == chapterLoadGeneration) showLoading(false)
            }
        }
    }

    private fun renderChapter(index: Int, text: String) {
        viewModel.switchChapter(index)
        val initialRatio = viewModel.restoreRatioFor(index)
        if (isScrollMode) {
            continuousAdapter?.setInitialChapter(NovelChapterData(chapterIndex = index, content = text))
            restoreContinuousAnchor(index, initialRatio ?: 0f)
        } else {
            viewBinding.readerView.setContent(
                content = text,
                resetPage = true,
                initialProgressRatio = initialRatio,
            )
        }
        preloadBoundary(index)
        updateProgressUi()
        invalidateOptionsMenu()
    }

    private fun preloadBoundary(centerIndex: Int) {
        preloadJob?.cancel()
        val generation = chapterLoadGeneration
        preloadJob = lifecycleScope.launch {
            for (delta in intArrayOf(1, -1)) {
                val previewIndex = centerIndex + delta
                if (previewIndex !in viewModel.chapters.value.indices) continue
                if (isScrollMode && continuousAdapter?.getItems()?.any { it.chapterIndex == previewIndex } == true) continue
                val text = runCatchingCancellable {
                    withContext(Dispatchers.IO) { viewModel.loadChapterText(previewIndex) }
                }.getOrNull() ?: continue
                ensureActive()
                if (generation != chapterLoadGeneration) return@launch
                if (text.isNotBlank()) {
                    if (isScrollMode) {
                        val data = NovelChapterData(previewIndex, text)
                        if (delta > 0) continuousAdapter?.appendChapter(data) else continuousAdapter?.prependChapter(data)
                        // LinearLayoutManager preserves the attached item's pixel offset on insertion.
                        // Re-scrolling to a character here used to interrupt flings and reset progress.
                    } else {
                        viewBinding.readerView.setChapterBoundaryPreview(delta, text)
                    }
                }
            }
        }
    }

    private fun showEmptyChapter(index: Int) {
        showError(IllegalStateException(getString(R.string.error_no_data_received)), index)
    }

    private fun showError(e: Exception, index: Int) {
        viewBinding.readerView.cancelPendingChapterTransition()
        Snackbar.make(viewBinding.root, e.message ?: getString(R.string.error_occurred), Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.retry) { loadChapter(index, force = true) }.show()
    }

    private fun showTranslationError(e: Exception) {
        val message = if (e is TranslateException.Http && e.provider == "GEMINI" && e.code in setOf(500, 503)) {
            getString(R.string.translate_temporary_provider_error, e.code)
        } else {
            e.message ?: getString(R.string.error_occurred)
        }
        Snackbar.make(
            viewBinding.root,
            message,
            Snackbar.LENGTH_INDEFINITE,
        ).setAction(R.string.copy) {
            copyToClipboard(getString(R.string.error), e.getCopyableErrorDetails())
            Toast.makeText(this, R.string.error_copied, Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun showLoading(isLoading: Boolean) {
        viewBinding.layoutLoading.isVisible = isLoading
    }

    private fun updateProgressUi() {
        val anchor = continuousAnchor()
        val index = anchor?.first ?: lastLoadedChapterIndex
        val chapter = viewModel.chapters.value.getOrNull(index) ?: return
        val view = continuousChapterView()
        val count = if (isScrollMode) view?.pageCount(scrollViewportHeight()) ?: 1
            else viewBinding.readerView.getDisplayPageCount().coerceAtLeast(1)
        val page = if (isScrollMode) view?.pageAtProgress(anchor?.second ?: 0f, scrollViewportHeight()) ?: 0
            else viewBinding.readerView.getDisplayPageIndex()
        val ratio = anchor?.second ?: viewBinding.readerView.getProgressRatio()
        viewBinding.actionsView.setSliderValue(page, count)
        viewBinding.actionsView.isSliderEnabled = count > 1
        viewBinding.infoBar.update(org.koitharu.kotatsu.reader.ui.pager.ReaderUiState(
            mangaName = viewModel.manga.value?.title,
            chapter = chapter, chapterIndex = index, chaptersTotal = viewModel.chapters.value.size,
            currentPage = page, totalPages = count,
            percent = (index + ratio) / viewModel.chapters.value.size.coerceAtLeast(1),
            incognito = viewModel.isIncognitoMode, scrollProgress = if (isScrollMode) ratio else -1f,
        ))
        viewBinding.infoBar.isVisible = isUiVisible && viewModel.readerSettings.value.showReadingStatus
        saveCurrentProgress()
    }

    private fun scrollViewportHeight(): Int = with(viewBinding.continuousScrollView) {
        (height - paddingTop - paddingBottom).coerceAtLeast(1)
    }

    private fun continuousChapterView(): NovelChapterView? {
        val manager = viewBinding.continuousScrollView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            ?: return null
        return manager.findViewByPosition(manager.findFirstVisibleItemPosition()) as? NovelChapterView
    }

    private fun continuousAnchor(): Pair<Int, Float>? {
        if (!isScrollMode) return null
        scrollLayoutManager.pendingAnchor?.let { return it }
        val recycler = viewBinding.continuousScrollView
        val manager = recycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return null
        val position = manager.findFirstVisibleItemPosition()
        val chapter = continuousAdapter?.getItems()?.getOrNull(position) ?: return null
        val view = manager.findViewByPosition(position) as? NovelChapterView ?: return null
        val atEnd = chapter.chapterIndex == viewModel.chapters.value.lastIndex && !recycler.canScrollVertically(1)
        return chapter.chapterIndex to if (atEnd) 1f else view.progressAt(recycler.paddingTop - view.top)
    }

    private fun restoreContinuousAnchor(index: Int, ratio: Float) {
        scrollLayoutManager.restoreChapter(index, ratio)
    }

    private fun saveCurrentProgress() {
        if (isScrollMode) {
            val anchor = continuousAnchor() ?: return
            val recycler = viewBinding.continuousScrollView
            val manager = recycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            if (manager != null) {
                for (position in manager.findFirstVisibleItemPosition()..manager.findLastVisibleItemPosition()) {
                    val chapterView = manager.findViewByPosition(position) ?: continue
                    if (chapterView.bottom > recycler.paddingTop && chapterView.bottom <= recycler.height - recycler.paddingBottom) {
                        continuousAdapter?.getItems()?.getOrNull(position)?.let { viewModel.onChapterCompleted(it.chapterIndex) }
                    }
                }
            }
            val page = continuousChapterView()?.pageAtProgress(anchor.second, scrollViewportHeight())
            viewModel.saveProgress(anchor.first, anchor.second, page)
        } else if (lastLoadedChapterIndex >= 0 && viewBinding.readerView.isLaidOut) {
            viewModel.saveProgress(lastLoadedChapterIndex, viewBinding.readerView.getProgressRatio(),
                viewBinding.readerView.getDisplayPageIndex())
            if (viewBinding.readerView.getDisplayPageIndex() == viewBinding.readerView.getDisplayPageCount() - 1) {
                viewModel.onChapterCompleted(lastLoadedChapterIndex)
            }
        }
    }

    private fun openInlineImage(imagePath: String) {
        router.openImage(imagePath, viewModel.manga.value?.source)
    }

    private fun showChaptersSheet() {
        val sheet = NovelChaptersSheet()
        sheet.show(supportFragmentManager, NovelChaptersSheet::class.java.name)
    }

    private fun showConfigSheet() {
        NovelReaderConfigSheet.newInstance().show(supportFragmentManager, NovelReaderConfigSheet::class.java.name)
    }

    override fun onChapterSelected(index: Int) {
        saveCurrentProgress()
        viewModel.navigateTo(index, 0f)
    }

    private fun onReverseReadingChanged(reversed: Boolean) {
        if (reversed == viewModel.isReadingReversed.value) return
        stopSpeech()
        val anchor = continuousAnchor() ?: if (lastLoadedChapterIndex >= 0) {
            lastLoadedChapterIndex to viewBinding.readerView.getProgressRatio()
        } else null
        saveCurrentProgress()
        // Old indices and preloads belong to the old sequence. Retire them before publishing the new one.
        ++chapterLoadGeneration
        chapterLoadJob?.cancel()
        preloadJob?.cancel()
        translationJob?.cancel()
        lastLoadedChapterIndex = -1
        scrollLayoutManager.clearPendingAnchor()
        continuousAdapter?.clear()
        viewBinding.readerView.cancelPendingChapterTransition()
        viewBinding.readerView.clearChapterBoundaryPreviews()
        viewModel.setReadingReversed(reversed, anchor?.first, anchor?.second)
    }

    private fun visibleAnchor(): Pair<Int, Float>? = continuousAnchor() ?: if (lastLoadedChapterIndex >= 0) {
        lastLoadedChapterIndex to viewBinding.readerView.getProgressRatio()
    } else null

    private fun confirmedVisibleChapterIndex(): Int? {
        val index = if (isScrollMode) {
            val recycler = viewBinding.continuousScrollView
            if (scrollLayoutManager.pendingAnchor != null || recycler.isComputingLayout || recycler.isLayoutRequested) {
                return null
            }
            val position = scrollLayoutManager.findFirstVisibleItemPosition()
            val child = scrollLayoutManager.findViewByPosition(position) as? NovelChapterView ?: return null
            if (child.bottom <= recycler.paddingTop || child.top >= recycler.height - recycler.paddingBottom) return null
            continuousAdapter?.getItems()?.getOrNull(position)?.chapterIndex ?: return null
        } else {
            val reader = viewBinding.readerView
            if (reader.isLayoutRequested || reader.width <= 0 || reader.height <= 0 || reader.getChapterLength() <= 0) {
                return null
            }
            lastLoadedChapterIndex
        }
        return index.takeIf { it >= 0 && it == viewModel.currentChapterIndex.value }
    }

    private fun chapterBrowserTarget(): ChapterBrowserTarget? {
        val index = confirmedVisibleChapterIndex() ?: return null
        val manga = viewModel.manga.value ?: return null
        val chapter = viewModel.chapters.value.getOrNull(index) ?: return null
        return ChapterBrowserTarget(manga, chapter)
    }

    private fun toggleChapterTranslation() {
        stopSpeech()
        if (translationJob?.isActive == true) { translationJob?.cancel(); return }
        val anchor = visibleAnchor() ?: return
        if (viewModel.isChapterTranslated(anchor.first)) {
            saveCurrentProgress()
            viewModel.showOriginalChapter(anchor.first)
            viewBinding.readerView.clearChapterBoundaryPreviews()
            viewModel.navigateTo(anchor.first, anchor.second)
            return
        }
        val chapterId = viewModel.chapters.value.getOrNull(anchor.first)?.id ?: return
        translationJob = lifecycleScope.launch {
            val message = Snackbar.make(viewBinding.root, R.string.novel_translating, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.cancel) { translationJob?.cancel() }
            message.show()
            try {
                viewModel.translateChapter(anchor.first) { done, total ->
                    runOnUiThread { message.setText(getString(R.string.novel_translation_progress, done, total)) }
                }
                ensureActive()
                val current = visibleAnchor()
                val translatedIsLoaded = isScrollMode && continuousAdapter?.getItems()?.any {
                    viewModel.chapters.value.getOrNull(it.chapterIndex)?.id == chapterId
                } == true
                if (current != null && (translatedIsLoaded ||
                    viewModel.chapters.value.getOrNull(current.first)?.id == chapterId)) {
                    saveCurrentProgress()
                    viewBinding.readerView.clearChapterBoundaryPreviews()
                    viewModel.navigateTo(current.first, current.second)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showTranslationError(e)
            } finally {
                message.dismiss()
                invalidateOptionsMenu()
            }
        }
        invalidateOptionsMenu()
    }

    private fun stopSpeech() {
        speechJob?.cancel()
        speechJob = null
        speechController.stop()
    }

    private fun toggleSpeech() {
        if (speechController.isActive || speechJob?.isActive == true) { stopSpeech(); return }
        val anchor = visibleAnchor() ?: return
        speechJob = lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { viewModel.loadChapterText(anchor.first) } ?: return@launch
                ensureActive()
                if (visibleAnchor()?.first != anchor.first) return@launch
                val target = viewModel.manga.value ?: return@launch
                val translated = viewModel.isChapterTranslated(anchor.first)
                val locale = if (translated) Locale.forLanguageTag(settings.translateTargetLanguage)
                else viewModel.readingSource.value?.getLocale()
                speechController.start(this@NovelReaderActivity, NovelSpeechRequest(
                    manga = target,
                    chapters = viewModel.chapters.value.toList(),
                    firstChapter = anchor.first,
                    firstText = text,
                    ratio = anchor.second,
                    locale = locale,
                    translated = translated,
                    cachedTranslations = if (translated) viewModel.speechTranslationsSnapshot() else emptyMap(),
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showTranslationError(e)
            } finally {
                invalidateOptionsMenu()
            }
        }
        invalidateOptionsMenu()
    }

    override fun onSettingsChanged(newSettings: NovelReaderSettings) {
        // The sheet persists the prefs; apply them live (same path as the flow observer) and
        // update the VM flow so visibility/palette logic sees the new values without re-entering.
        saveCurrentProgress()
        viewModel.readerSettings.value = newSettings
    }

    override fun onBookmarkClick() {
        // Bookmarks for novels are not supported in v1
    }

    override fun showChaptersSheet(defaultTab: Int?) {
        showChaptersSheet()
    }

    override fun onGridTouch(area: TapGridArea): Boolean {
        return handleGridAction(tapGridSettings.getTapAction(area, isLongTap = false))
    }

    override fun onGridLongTouch(area: TapGridArea) {
        handleGridAction(tapGridSettings.getTapAction(area, isLongTap = true))
    }

    override fun onProcessTouch(rawX: Int, rawY: Int): Boolean = true

    private fun handleGridAction(action: TapAction?): Boolean {
        when (action ?: return false) {
            TapAction.PAGE_NEXT -> switchPageBy(1)
            TapAction.PAGE_PREV -> switchPageBy(-1)
            TapAction.CHAPTER_NEXT -> switchChapterBy(1)
            TapAction.CHAPTER_PREV -> switchChapterBy(-1)
            TapAction.TOGGLE_UI -> toggleUiVisibility()
            TapAction.SHOW_MENU -> openMenu()
        }
        return true
    }

    override fun toggleUiVisibility() {
        setUiIsVisible(!isUiVisible)
    }

    private fun setUiIsVisible(visible: Boolean) {
        isUiVisible = visible
        val transition = Fade().apply { duration = 150 }
        TransitionManager.beginDelayedTransition(viewBinding.root, transition)
        viewBinding.appbarTop.isVisible = visible
        viewBinding.toolbarDocked.isVisible = visible
        viewBinding.infoBar.isVisible = visible && viewModel.readerSettings.value.showReadingStatus
        systemUiController.setSystemUiVisible(visible || !viewModel.readerSettings.value.enableFullscreen)
        // Hidden system bars change the insets; re-apply so content re-paginates for the new area.
        viewBinding.root.requestApplyInsets()
    }

    override fun openMenu() {
        if (!isUiVisible) {
            setUiIsVisible(true)
            return
        }
        showConfigSheet()
    }

    private fun addMenu() {
        addMenuProvider(object : androidx.core.view.MenuProvider {
            override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
                menuInflater.inflate(R.menu.opt_novel_reader, menu)
            }

            override fun onPrepareMenu(menu: android.view.Menu) {
                menu.findItem(R.id.action_open_chapter_browser)?.isEnabled = chapterBrowser.isAvailable
                menu.findItem(R.id.action_novel_read_aloud)?.apply {
                    isEnabled = visibleAnchor() != null
                    setTitle(if (speechController.isActive || speechJob?.isActive == true)
                        R.string.novel_stop_reading_aloud else R.string.novel_read_aloud)
                }
                menu.findItem(R.id.action_novel_speech_pause)?.apply {
                    isVisible = speechController.isActive
                    isEnabled = speechController.state.value.status != NovelSpeechStatus.PREPARING
                    setTitle(if (speechController.state.value.status == NovelSpeechStatus.PAUSED)
                        R.string.novel_speech_resume else R.string.novel_speech_pause)
                }
                menu.findItem(R.id.action_novel_source_settings)?.isEnabled = viewModel.readingSource.value != null
                menu.findItem(R.id.action_novel_translate)?.apply {
                    isEnabled = visibleAnchor() != null
                    setTitle(when {
                        translationJob?.isActive == true -> R.string.cancel
                        viewModel.isChapterTranslated(visibleAnchor()?.first ?: -1) -> R.string.novel_show_original
                        else -> R.string.novel_translate_chapter
                    })
                }
            }

            override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_open_chapter_browser -> { chapterBrowser.open(); true }
                    R.id.action_novel_read_aloud -> { toggleSpeech(); true }
                    R.id.action_novel_speech_pause -> { speechController.toggle(); true }
                    R.id.action_novel_translate -> { toggleChapterTranslation(); true }
                    R.id.action_novel_translation_settings -> { router.openReaderSettings(); true }
                    R.id.action_novel_source_settings -> {
                        viewModel.readingSource.value?.let { router.openSourceSettings(it) }
                        true
                    }
                    R.id.action_chapters -> {
                        showChaptersSheet()
                        true
                    }

                    R.id.action_settings -> {
                        showConfigSheet()
                        true
                    }

                    else -> false
                }
            }
        })
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        // Keep the camera/status area reserved even when fullscreen hides the status bar.
        val safe = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val contentInsets = Insets.max(bars, safe)
        systemBarsBottomInset = bars.bottom
        // Keep the reading content between the status bar and the navigation bar, like the
        // manga reader: text never renders under system UI regardless of toolbar visibility.
        viewBinding.readerView.applyContentInsets(
            left = contentInsets.left,
            right = contentInsets.right,
            top = contentInsets.top,
            bottom = contentInsets.bottom,
        )
        // Padding alone does not prevent scrolled children drawing under the camera.
        viewBinding.continuousScrollView.clipToPadding = true
        viewBinding.continuousScrollView.updatePadding(
            left = contentInsets.left,
            right = contentInsets.right,
            top = contentInsets.top,
            bottom = contentInsets.bottom,
        )
        viewBinding.appbarTop.updatePadding(
            left = bars.left,
            right = bars.right,
            top = bars.top,
        )
        viewBinding.toolbarDocked.updatePadding(
            left = bars.left,
            right = bars.right,
            bottom = bars.bottom,
        )
        viewBinding.infoBar.updatePadding(bottom = bars.bottom)
        // The floating bottom toolbar sits above the navigation bar, like the manga reader's.
        (viewBinding.toolbarDocked.layoutParams as? CoordinatorLayout.LayoutParams)?.let { lp ->
            lp.bottomMargin = bars.bottom + resources.getDimensionPixelSize(R.dimen.reader_toolbar_float_gap)
            viewBinding.toolbarDocked.layoutParams = lp
        }
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
            .build()
    }

    override fun switchPageBy(delta: Int) {
        if (isScrollMode) {
            if (delta > 0) {
                viewBinding.continuousScrollView.smoothScrollBy(0, viewBinding.root.height / 2)
            } else {
                viewBinding.continuousScrollView.smoothScrollBy(0, -viewBinding.root.height / 2)
            }
        } else {
            if (delta > 0) {
                viewBinding.readerView.nextPage()
            } else {
                viewBinding.readerView.previousPage()
            }
        }
        updateProgressUi()
    }

    override fun switchPageTo(index: Int) {
        if (isScrollMode) {
            val anchor = continuousAnchor() ?: return
            val view = continuousChapterView() ?: return
            restoreContinuousAnchor(anchor.first, view.progressAt(index * scrollViewportHeight()))
        } else {
            viewBinding.readerView.goToPage(index)
        }
    }

    override fun switchChapterBy(delta: Int) {
        val next = viewModel.currentChapterIndex.value + delta
        if (next in viewModel.chapters.value.indices) {
            saveCurrentProgress()
            viewModel.navigateTo(next, if (delta > 0) 0f else 1f)
        } else {
            viewBinding.readerView.cancelPendingChapterTransition()
            Snackbar.make(viewBinding.root, R.string.no_more_chapters, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
        return if (isScrollMode) {
            if (smooth) {
                viewBinding.continuousScrollView.smoothScrollBy(0, delta)
            } else {
                viewBinding.continuousScrollView.scrollBy(0, delta)
            }
            true
        } else {
            false
        }
    }

    override fun toggleScreenOrientation() = Unit

    override fun onSavePageClick() = Unit

    override fun onScrollTimerClick(isLongClick: Boolean) = Unit

    override fun isReaderResumed(): Boolean = !viewModel.isUiLoading.value

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (controlDelegate.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (controlDelegate.onKeyUp(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgress()
        viewModel.onPause()
    }

    override fun onResume() {
        org.koitharu.kotatsu.core.archive.ArchiveSessionLifecycle.enforceBeforeDisplay()
        super.onResume()
        viewModel.onResume()
        viewModel.configuredReadingReversed()?.let(::onReverseReadingChanged)
        updateProgressUi()
    }

    override fun onStop() {
        super.onStop()
        speechJob?.cancel()
        speechJob = null
        speechController.detachActivity(this)
        saveCurrentProgress()
    }

    override fun onDestroy() {
        speechController.detachActivity(this)
        if (isFinishing && speechController.state.value.mangaId == viewModel.manga.value?.id) speechController.stop()
        if (viewModel.protectedArchive != null) {
            viewBinding.readerView.clearSessionContent()
            continuousAdapter?.clear()
            viewBinding.continuousScrollView.adapter = null
            viewBinding.continuousScrollView.recycledViewPool.clear()
        }
        super.onDestroy()
    }

    private fun showArchiveUnlock(error: Throwable): Boolean {
        if (error !is org.koitharu.kotatsu.core.archive.ArchiveAccessException.Locked) return false
        val file = viewModel.protectedArchive ?: return false
        org.koitharu.kotatsu.core.archive.ArchiveUnlockDialog.show(supportFragmentManager, file)
        return true
    }

    companion object {

        const val EXTRA_INCOGNITO = NovelReaderViewModel.EXTRA_INCOGNITO
        const val EXTRA_STATE = NovelReaderViewModel.EXTRA_STATE
    }
}
