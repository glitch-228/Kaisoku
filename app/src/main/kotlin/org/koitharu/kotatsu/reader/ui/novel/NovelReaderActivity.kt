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
	private var isScrollMode = false
	private var chapterLoadJob: kotlinx.coroutines.Job? = null
	private var preloadJob: kotlinx.coroutines.Job? = null
	private var lastLoadedChapterIndex = -1

	private var continuousAdapter: NovelContinuousAdapter? = null

	override val readerMode: org.koitharu.kotatsu.core.prefs.ReaderMode?
		get() = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityNovelReaderBinding.inflate(layoutInflater))
		WindowCompat.setDecorFitsSystemWindows(window, false)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.title = null

		controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)

		viewBinding.actionsView.listener = this
		viewBinding.actionsView.isSliderEnabled = false
		addMenu()

		setupReaderView()
		setupContinuousScroll()

		viewModel.manga.observe(this) { manga ->
			if (manga != null) {
				supportActionBar?.title = manga.title
			}
		}
		viewModel.isUiLoading.observe(this) { showLoading(it) }
		viewModel.currentChapterIndex.observe(this) { index ->
			if (index >= 0) {
				loadChapter(index)
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
			onTapAreaListener = { area -> onGridTouch(area) }
			onChapterChangeRequestListener = { delta -> switchChapterBy(delta) }
			onPageChangeListener = { _, _ -> updateProgressUi() }
		}
	}

	private fun setupContinuousScroll() {
		continuousAdapter = NovelContinuousAdapter(
			settings = viewModel.readerSettings.value,
			onImageClick = { request -> openInlineImage(request.imagePath) },
			onTap = { _, _, _ -> toggleUiVisibility() },
		)
		viewBinding.continuousScrollView.adapter = continuousAdapter
		viewBinding.continuousScrollView.addOnScrollListener(
			object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {

				private var lastVisibleChapter = -1

				override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
					super.onScrolled(recyclerView, dx, dy)
					val manager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
						?: return
					val lastVisible = manager.findLastVisibleItemPosition()
					if (lastVisible >= 0 && lastVisible != lastVisibleChapter) {
						lastVisibleChapter = lastVisible
						val chapterIndex = continuousAdapter?.getItems()?.getOrNull(lastVisible)?.chapterIndex ?: return
						if (chapterIndex != viewModel.currentChapterIndex.value) {
							viewModel.switchChapter(chapterIndex)
						}
						saveCurrentProgress()
					}
				}
			},
		)
	}

	private fun applySettings(newSettings: NovelReaderSettings) {
		val modeChanged = isScrollMode != (newSettings.readingMode == NovelReadingMode.SCROLL)
		isScrollMode = newSettings.readingMode == NovelReadingMode.SCROLL

		viewBinding.readerView.updateSettings(newSettings)
		viewBinding.readerView.setDualPageMode(newSettings.enableDualPage && !isScrollMode)
		continuousAdapter?.updateSettings(newSettings)

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
		if (!force && lastLoadedChapterIndex == index) return
		lastLoadedChapterIndex = index
		chapterLoadJob?.cancel()
		chapterLoadJob = lifecycleScope.launch {
			showLoading(true)
			try {
				val html = withContext(Dispatchers.IO) { viewModel.loadChapterHtml(index) }
				if (html != null) {
					val text = NovelHtml.decodeChapterHtml(html).let(NovelHtml::toPlainText)
					if (text.isNotBlank()) {
						renderChapter(index, text)
					} else {
						showEmptyChapter()
					}
				} else {
					showEmptyChapter()
				}
			} catch (e: Exception) {
				showError(e)
			} finally {
				showLoading(false)
			}
		}
	}

	private fun renderChapter(index: Int, text: String) {
		val initialRatio = viewModel.restoreRatioFor(index)
		if (isScrollMode) {
			continuousAdapter?.setInitialChapter(NovelChapterData(chapterIndex = index, content = text))
		} else {
			viewBinding.readerView.setContent(
				content = text,
				resetPage = true,
				initialProgressRatio = initialRatio,
			)
		}
		updateProgressUi()
		preloadBoundary(index)
	}

	private fun preloadBoundary(centerIndex: Int) {
		preloadJob?.cancel()
		preloadJob = lifecycleScope.launch {
			for (delta in intArrayOf(1, -1)) {
				val previewIndex = centerIndex + delta
				if (previewIndex !in viewModel.chapters.value.indices) return@launch
				val html = runCatching {
					withContext(Dispatchers.IO) { viewModel.loadChapterHtml(previewIndex) }
				}.getOrNull() ?: continue
				val text = NovelHtml.decodeChapterHtml(html).let(NovelHtml::toPlainText)
				if (text.isNotBlank()) {
					viewBinding.readerView.setChapterBoundaryPreview(delta, text)
				}
			}
		}
	}

	private fun showEmptyChapter() {
		if (isScrollMode) {
			continuousAdapter?.setInitialChapter(
				NovelChapterData(
					chapterIndex = viewModel.currentChapterIndex.value,
					content = getString(R.string.no_chapters_in_manga),
				),
			)
		} else {
			viewBinding.readerView.setContent(getString(R.string.no_chapters_in_manga))
		}
	}

	private fun showError(e: Exception) {
		Snackbar.make(
			viewBinding.root,
			e.message ?: getString(R.string.error_occurred),
			Snackbar.LENGTH_SHORT,
		).show()
	}

	private fun showLoading(isLoading: Boolean) {
		viewBinding.layoutLoading.isVisible = isLoading
	}

	private fun updateProgressUi() {
		val chapter = viewModel.chapters.value.getOrNull(viewModel.currentChapterIndex.value)
		viewBinding.actionsView.setSliderValue(
			viewBinding.readerView.getDisplayPageIndex(),
			viewBinding.readerView.getDisplayPageCount().coerceAtLeast(1),
		)
		viewBinding.infoBar.isVisible = chapter != null && viewModel.readerSettings.value.showReadingStatus
		saveCurrentProgress()
	}

	private fun saveCurrentProgress() {
		val index = viewModel.currentChapterIndex.value
		if (index < 0) return
		val ratio = if (isScrollMode) 0f else viewBinding.readerView.getProgressRatio()
		viewModel.saveProgress(index, ratio)
	}

	private fun openInlineImage(imagePath: String) {
		Snackbar.make(viewBinding.root, imagePath, Snackbar.LENGTH_SHORT).show()
	}

	private fun showChaptersSheet() {
		val sheet = NovelChaptersSheet.newInstance(
			viewModel.chapters.value,
			viewModel.currentChapterIndex.value,
		)
		sheet.show(supportFragmentManager, NovelChaptersSheet::class.java.name)
	}

	private fun showConfigSheet() {
		NovelReaderConfigSheet.newInstance().show(supportFragmentManager, NovelReaderConfigSheet::class.java.name)
	}

	override fun onChapterSelected(index: Int) {
		viewModel.switchChapter(index)
	}

	override fun onSettingsChanged(newSettings: NovelReaderSettings) {
		viewBinding.readerView.updateSettings(newSettings)
		viewBinding.readerView.setDualPageMode(newSettings.enableDualPage && !isScrollMode)
		continuousAdapter?.updateSettings(newSettings)
		applyReaderPalette()
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

			override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
				return when (menuItem.itemId) {
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
		viewBinding.readerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.continuousScrollView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
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
		if (!isScrollMode) {
			viewBinding.readerView.goToPage(index)
		}
	}

	override fun switchChapterBy(delta: Int) {
		val next = viewModel.currentChapterIndex.value + delta
		if (next in viewModel.chapters.value.indices) {
			viewModel.initialRatio.value = if (delta > 0) 0f else 1f
			viewModel.switchChapter(next)
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

	override fun onStop() {
		super.onStop()
		saveCurrentProgress()
	}

	companion object {

		const val EXTRA_INCOGNITO = NovelReaderViewModel.EXTRA_INCOGNITO
		const val EXTRA_STATE = NovelReaderViewModel.EXTRA_STATE
	}
}
