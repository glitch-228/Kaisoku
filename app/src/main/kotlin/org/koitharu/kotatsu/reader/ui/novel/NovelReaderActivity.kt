/*
 * Novel reader activity, trimmed from Kototoro's NovelReaderActivity (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseFullscreenActivity
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivityNovelReaderBinding
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.ui.ReaderControlDelegate
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
	lateinit var tapGridSettings: TapGridSettings

	@Inject
	lateinit var settings: org.koitharu.kotatsu.core.prefs.AppSettings

	private val viewModel by viewModels<NovelReaderViewModel>()

	private lateinit var controlDelegate: ReaderControlDelegate
	private var isUiVisible = true
	private var systemBarsBottomInset: Int = 0
	private var isScrollMode = false
	private var chapterLoadJob: kotlinx.coroutines.Job? = null
	private var preloadJob: kotlinx.coroutines.Job? = null
	private var translationJob: kotlinx.coroutines.Job? = null
	private var lastLoadedChapterIndex = -1
	private var chapterLoadGeneration = 0L
	private lateinit var scrollLayoutManager: NovelScrollLayoutManager

	private var continuousAdapter: NovelContinuousAdapter? = null

	override val readerMode: org.koitharu.kotatsu.core.prefs.ReaderMode?
		get() = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityNovelReaderBinding.inflate(layoutInflater))
		WindowCompat.setDecorFitsSystemWindows(window, false)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.title = null
		supportActionBar?.subtitle = getString(R.string.novel_reader_beta)

		controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)

		viewBinding.actionsView.listener = this
		viewBinding.actionsView.isSliderEnabled = true
		addMenu()

		setupReaderView()
		setupContinuousScroll()
		isScrollMode = viewModel.readerSettings.value.readingMode == NovelReadingMode.SCROLL
		viewBinding.readerView.isVisible = !isScrollMode
		viewBinding.continuousScrollView.isVisible = isScrollMode

		viewModel.manga.observe(this) { manga ->
			if (manga != null) {
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
			Snackbar.make(viewBinding.root, e.message ?: getString(R.string.error_occurred), Snackbar.LENGTH_SHORT)
				.show()
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
						showEmptyChapter()
					}
				} else {
					showEmptyChapter()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (generation == chapterLoadGeneration) showError(e)
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

	private fun showEmptyChapter() {
        showError(IllegalStateException(getString(R.string.error_no_data_received)))
    }

	private fun showError(e: Exception) {
		viewBinding.readerView.cancelPendingChapterTransition()
		Snackbar.make(
			viewBinding.root,
			e.message ?: getString(R.string.error_occurred),
			Snackbar.LENGTH_SHORT,
		).setAction(R.string.try_again) { loadChapter(viewModel.currentChapterIndex.value, force = true) }.show()
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
			viewModel.saveProgress(anchor.first, anchor.second)
		} else if (lastLoadedChapterIndex >= 0 && viewBinding.readerView.isLaidOut) {
			viewModel.saveProgress(lastLoadedChapterIndex, viewBinding.readerView.getProgressRatio())
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

	private fun toggleChapterTranslation() {
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
				Snackbar.make(viewBinding.root, e.message ?: getString(R.string.error_occurred), Snackbar.LENGTH_LONG)
					.setAction(R.string.settings) { router.openReaderSettings() }.show()
			} finally {
				message.dismiss()
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
	}

	override fun onResume() {
		super.onResume()
		viewModel.configuredReadingReversed()?.let(::onReverseReadingChanged)
	}

	override fun onStop() {
		super.onStop()
		saveCurrentProgress()
	}

	companion object {

		const val EXTRA_INCOGNITO = NovelReaderViewModel.EXTRA_INCOGNITO
		const val EXTRA_STATE = NovelReaderViewModel.EXTRA_STATE
	}
}
