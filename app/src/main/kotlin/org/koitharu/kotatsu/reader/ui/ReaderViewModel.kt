package org.koitharu.kotatsu.reader.ui

import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.exceptions.EmptyMangaException
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.firstNotNull
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.details.ui.pager.EmptyMangaReason
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.HistoryUpdateUseCase
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.sizeOrZero
import org.koitharu.kotatsu.reader.domain.ChapterSwitchCursor
import org.koitharu.kotatsu.reader.domain.ChaptersLoader
import org.koitharu.kotatsu.reader.domain.DetectReaderModeUseCase
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordRpc
import org.koitharu.kotatsu.stats.domain.StatsCollector
import java.time.Instant
import javax.inject.Inject

private const val BOUNDS_PAGE_OFFSET = 2
private const val PREFETCH_LIMIT = 10

internal fun calculateReaderPercent(
    chapterIndex: Int,
    chaptersCount: Int,
    pageIndex: Int,
    pagesCount: Int,
): Float {
    if (chapterIndex !in 0 until chaptersCount || pageIndex < 0 || pagesCount <= 0) {
        return PROGRESS_NONE
    }
    val boundedPageIndex = pageIndex.coerceAtMost(pagesCount - 1)
    if (chapterIndex == chaptersCount - 1 && boundedPageIndex == pagesCount - 1) {
        // Avoid float rounding an exact end position down to 99% for some chapter counts.
        return 1f
    }
    val pagePercent = (boundedPageIndex + 1) / pagesCount.toFloat()
    return ((chapterIndex + pagePercent) / chaptersCount).coerceIn(0f, 1f)
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dataRepository: MangaDataRepository,
    private val historyRepository: HistoryRepository,
    private val bookmarksRepository: BookmarksRepository,
    settings: AppSettings,
    private val pageLoader: PageLoader,
    private val chaptersLoader: ChaptersLoader,
    private val appShortcutManager: AppShortcutManager,
    private val detailsLoadUseCase: DetailsLoadUseCase,
    private val historyUpdateUseCase: HistoryUpdateUseCase,
    private val detectReaderModeUseCase: DetectReaderModeUseCase,
    private val statsCollector: StatsCollector,
    private val discordRpc: DiscordRpc,
    val translationCoordinator: org.koitharu.kotatsu.reader.translate.TranslationCoordinator,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
    interactor: DetailsInteractor,
    deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
    downloadScheduler: DownloadWorker.Scheduler,
    readerSettingsProducerFactory: ReaderSettings.Producer.Factory,
) : ChaptersPagesViewModel(
    settings = settings,
    interactor = interactor,
    bookmarksRepository = bookmarksRepository,
    historyRepository = historyRepository,
    downloadScheduler = downloadScheduler,
    deleteLocalMangaUseCase = deleteLocalMangaUseCase,
    localStorageChanges = localStorageChanges,
) {
    private val intent = MangaIntent(savedStateHandle)

    private var loadingJob: Job? = null
    private var preloadJob: Job? = null
    private var pageSaveJob: Job? = null
    private var bookmarkJob: Job? = null
    private var stateChangeJob: Job? = null
    private var modeSwitchJob: Job? = null
    @Volatile private var divertedToNovelReader = false
    private var lastScrollProgress: Float = -1f
    private val navCursor = ChapterSwitchCursor()
    // Page-list replacement and delayed page-state commits must be ordered together. Otherwise an
    // old scroll callback can pass a page-count check after a same-sized chapter switch and put the
    // reader back into the previous chapter.
    private val contentStateLock = Any()
    private var stateRevision = 0L
    private var contentGeneration = 0L
    private var nextReaderReplacementId = 0L
    private var pendingReaderReplacement: PendingReaderReplacement? = null
    private val lifecycleStateGuard = ReaderLifecycleStateGuard()

    init {
        mangaDetails.value = intent.manga?.let { MangaDetails(it) }
    }

    val readerMode = MutableStateFlow<ReaderMode?>(null)
    val onPageSaved = MutableEventFlow<Collection<Uri>>()
    val onLoadingError = MutableEventFlow<Throwable>()
    val onShowToast = MutableEventFlow<Int>()
    val onAskNsfwIncognito = MutableEventFlow<Unit>()
    val onOpenNovelReader = MutableEventFlow<Manga>()
    val onShowOcrSheet = MutableEventFlow<Unit>()
    val onTranslateConfigMissing = MutableEventFlow<Unit>()
    val ocrSheetState = MutableStateFlow<org.koitharu.kotatsu.reader.translate.OcrSheetState>(
        org.koitharu.kotatsu.reader.translate.OcrSheetState.Idle,
    )
    private var ocrJob: Job? = null
    val uiState = MutableStateFlow<ReaderUiState?>(null)

    val isIncognitoMode = MutableStateFlow(savedStateHandle.get<Boolean>(ReaderIntent.EXTRA_INCOGNITO))

    val content = MutableStateFlow(ReaderContent(emptyList(), null))

    val pageAnimation = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_ANIMATION,
        valueProducer = { readerAnimation },
    )

    val isInfoBarEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_BAR,
        valueProducer = { isReaderBarEnabled },
    )

    val isInfoBarTransparent = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_BAR_TRANSPARENT,
        valueProducer = { isReaderBarTransparent },
    )

    val isKeepScreenOnEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_SCREEN_ON,
        valueProducer = { isReaderKeepScreenOn },
    )

    val isWebtoonZooEnabled = observeIsWebtoonZoomEnabled()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val isWebtoonGapsEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_GAPS,
        valueProducer = { isWebtoonGapsEnabled },
    )

    val isWebtoonPullGestureEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_PULL_GESTURE,
        valueProducer = { isWebtoonPullGestureEnabled },
    )

    val defaultWebtoonZoomOut = observeIsWebtoonZoomEnabled().flatMapLatest {
        if (it) {
            observeWebtoonZoomOut()
        } else {
            flowOf(0f)
        }
    }.flowOn(Dispatchers.Default)

    val isZoomControlsEnabled = getObserveIsZoomControlEnabled().flatMapLatest { zoom ->
        if (zoom) {
            combine(readerMode, isWebtoonZooEnabled) { mode, ze -> ze || mode != ReaderMode.WEBTOON }
        } else {
            flowOf(false)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val readerSettingsProducer = readerSettingsProducerFactory.create(
        manga.mapNotNull { it?.id },
    )

    val isMangaNsfw = manga.map { it?.contentRating == ContentRating.ADULT }

    val isBookmarkAdded = readingState.flatMapLatest { state ->
        val manga = mangaDetails.value?.toManga()
        if (state == null || manga == null) {
            flowOf(false)
        } else {
            bookmarksRepository.observeBookmark(manga, state.chapterId, state.page)
                .map {
                    it != null && it.chapterId == state.chapterId && it.page == state.page
                }
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    init {
        initIncognitoMode()
        loadImpl()
        launchJob(Dispatchers.Default) {
            val mangaId = manga.filterNotNull().first().id
            if (!isIncognitoMode.firstNotNull()) {
                appShortcutManager.notifyMangaOpened(mangaId)
            }
        }
    }

    fun reload() {
        loadingJob?.cancel()
        loadImpl()
    }

    @MainThread
    fun onBackgrounding() {
        stateChangeJob?.cancel()
        synchronized(contentStateLock) {
            lifecycleStateGuard.onBackgrounding()
            // A page callback already queued on a worker must not commit after backgrounding.
            stateRevision++
        }
    }

    @MainThread
    fun onResume() {
        synchronized(contentStateLock) {
            val wasBackgrounded = lifecycleStateGuard.onResumed()
            val resumeState = readingState.value
            if (wasBackgrounded && resumeState != null && content.value.pages.any {
                    it.chapterId == resumeState.chapterId && it.index == resumeState.page
                }) {
                // A retained RecyclerView/ViewPager may restore its own older layout position before
                // emitting callbacks. Publish a new generation and re-anchor it to the frozen pause
                // snapshot before UI callbacks become authoritative again.
                stateRevision++
                val replacement = PendingReaderReplacement(
                    id = ++nextReaderReplacementId,
                    state = resumeState,
                    restoreExistingReader = true,
                )
                pendingReaderReplacement = replacement
                publishContentLocked(content.value)
            }
        }
    }

    fun onPause() {
        getMangaOrNull()?.let {
            statsCollector.onPause(it.id)
        }
    }

    fun onStop() {
        discordRpc.clearRpc()
    }

    fun onIdle() {
        discordRpc.setIdle()
    }

    @MainThread
    fun switchMode(newMode: ReaderMode, visibleSnapshot: ReaderStateSnapshot?) {
        if (readerMode.value == newMode) {
            saveVisibleState(visibleSnapshot)
            return
        }
        // Capture the outgoing reader's visible page exactly once. Re-reading readingState after
        // the preferences write allowed a late scroll callback to replace the handoff position.
        prepareReaderReplacement(visibleSnapshot)
        readerMode.value = newMode

        val previousModeSwitch = modeSwitchJob
        modeSwitchJob = launchJob {
            previousModeSwitch?.cancelAndJoin()
            val manga = checkNotNull(getMangaOrNull())
            dataRepository.saveReaderMode(
                manga = manga,
                mode = newMode,
            )
        }
    }

    @MainThread
    fun prepareReaderReplacement(visibleSnapshot: ReaderStateSnapshot?) {
        val handoffState = synchronized(contentStateLock) {
            visibleSnapshot.stateForGeneration(content.value.generation) ?: getCurrentState()
        } ?: return
        saveCurrentState(handoffState)
        synchronized(contentStateLock) {
            pendingReaderReplacement = PendingReaderReplacement(
                id = ++nextReaderReplacementId,
                state = handoffState,
                restoreExistingReader = false,
            )
            publishContentLocked(content.value)
        }
    }

    fun getPendingReaderReplacementState(replacementId: Long): ReaderState? = synchronized(contentStateLock) {
        pendingReaderReplacement?.takeIf { it.id == replacementId }?.state
    }

    fun onReaderStateRestored(replacementId: Long, state: ReaderState, generation: Long) {
        synchronized(contentStateLock) {
            if (content.value.generation == generation &&
                pendingReaderReplacement?.let { it.id == replacementId && it.state == state } == true
            ) {
                pendingReaderReplacement = null
            }
        }
    }

    /**
     * Confirms the initial/restored adapter only after AsyncListDiffer has applied its page list.
     * This preserves normal state notification, prefetching, and adjacent-chapter preload without
     * accepting the synthetic callbacks emitted while the adapter still represented an old list.
     */
    @MainThread
    fun onReaderContentApplied(generation: Long) {
        val position = synchronized(contentStateLock) {
            if (pendingReaderReplacement != null ||
                !lifecycleStateGuard.canCommitUiState() ||
                content.value.generation != generation
            ) {
                return
            }
            val state = readingState.value ?: return
            content.value.pages.indexOfFirst {
                it.chapterId == state.chapterId && it.index == state.page
            }
        }
        if (position >= 0) {
            onCurrentPageChanged(
                lowerPos = position,
                upperPos = position,
                scrollProgress = lastScrollProgress,
                scrollOffset = readingState.value?.scroll ?: 0,
                contentGeneration = generation,
            )
        }
    }

    fun saveVisibleState(snapshot: ReaderStateSnapshot?) {
        val save = synchronized(contentStateLock) {
            if (pendingReaderReplacement != null) {
                VisibleStateSave(shouldSave = false, state = null)
            } else {
                val state = snapshot.stateForGeneration(content.value.generation)
                lifecycleStateGuard.selectVisibleState(state, readingState.value)
            }
        }
        if (save.shouldSave) {
            saveCurrentState(save.state)
        }
    }

    @MainThread
    fun saveBackgroundState(snapshot: ReaderStateSnapshot?) {
        val save = synchronized(contentStateLock) {
            if (pendingReaderReplacement != null) {
                VisibleStateSave(shouldSave = false, state = null)
            } else {
                val state = snapshot.stateForGeneration(content.value.generation)
                lifecycleStateGuard.captureBackgroundState(state, readingState.value)
            }
        }
        if (save.shouldSave) {
            saveCurrentState(save.state)
        }
    }

    fun saveCurrentState(state: ReaderState? = null) {
        stateChangeJob?.cancel()
        if (state != null) {
            synchronized(contentStateLock) {
                stateRevision++
                readingState.value = state
            }
            savedStateHandle[ReaderIntent.EXTRA_STATE] = state
        }
        if (isIncognitoMode.value != false) {
            return
        }
        val readerState = state ?: readingState.value ?: return
        historyUpdateUseCase.invokeAsync(
            manga = getMangaOrNull() ?: return,
            readerState = readerState,
            percent = computePercent(readerState.chapterId, readerState.page),
        )
    }

    fun getCurrentState() = readingState.value

    fun getCurrentChapterPages(): List<MangaPage>? {
        val chapterId = readingState.value?.chapterId ?: return null
        return chaptersLoader.getPages(chapterId)
    }

    fun saveCurrentPage(
        pageSaveHelper: PageSaveHelper
    ) {
        val prevJob = pageSaveJob
        pageSaveJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val state = checkNotNull(getCurrentState())
            val currentManga = manga.requireValue()
            val task = PageSaveHelper.Task(
                manga = currentManga,
                chapterId = state.chapterId,
                pageNumber = state.page + 1,
                page = checkNotNull(getCurrentPage()) { "Cannot find current page" },
            )
            val dest = pageSaveHelper.save(setOf(task))
            onPageSaved.call(dest)
        }
    }

    fun getCurrentPage(): MangaPage? {
        val state = readingState.value ?: return null
        return content.value.pages.find {
            it.chapterId == state.chapterId && it.index == state.page
        }?.toMangaPage()
    }

    fun requestOcrCurrentPage() {
        val page = getCurrentPage() ?: return
        if (!isTranslateConfigured()) {
            onTranslateConfigMissing.call(Unit)
            return
        }
        onShowOcrSheet.call(Unit)
        ocrJob?.cancel()
        ocrSheetState.value = org.koitharu.kotatsu.reader.translate.OcrSheetState.Loading
        ocrJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val blocks = translationCoordinator.requestOcr(page)
                val text = blocks.joinToString("\n") { it.originalText }.trim()
                ocrSheetState.value = org.koitharu.kotatsu.reader.translate.OcrSheetState.Done(text, blocks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ocrSheetState.value = org.koitharu.kotatsu.reader.translate.OcrSheetState.Failed(e)
            }
        }
    }

    fun toggleTranslateCurrentPage() {
        val page = getCurrentPage() ?: return
        if (!isTranslateConfigured()) {
            onTranslateConfigMissing.call(Unit)
            return
        }
        val currentState = translationCoordinator.stateFor(page.id).value
        if (currentState is org.koitharu.kotatsu.reader.translate.PageTranslationState.Done) {
            translationCoordinator.hideTranslation(page.id)
        } else {
            translationCoordinator.requestTranslate(page)
        }
    }

    /** Always request translation for the current page (no toggle). Used by AUTO switch. */
    fun ensureTranslateCurrentPage() {
        val page = getCurrentPage() ?: return
        if (!isTranslateConfigured()) {
            onTranslateConfigMissing.call(Unit)
            return
        }
        translationCoordinator.requestTranslate(page)
    }

    /** Re-run translation for the current page, re-attempting the tiles that failed last time. */
    fun retryTranslateCurrentPage() {
        val page = getCurrentPage() ?: return
        if (!isTranslateConfigured()) {
            onTranslateConfigMissing.call(Unit)
            return
        }
        translationCoordinator.requestTranslate(page, force = true)
    }

    private fun isTranslateConfigured(): Boolean = settings.isPageTranslationConfigured

    /** Flip between manual and auto trigger mode. Returns the new mode. */
    fun toggleAutoTrigger(): org.koitharu.kotatsu.reader.translate.TranslateTriggerMode {
        val next = if (settings.translateTriggerMode == org.koitharu.kotatsu.reader.translate.TranslateTriggerMode.AUTO_ON_PAGE) {
            org.koitharu.kotatsu.reader.translate.TranslateTriggerMode.MANUAL
        } else {
            org.koitharu.kotatsu.reader.translate.TranslateTriggerMode.AUTO_ON_PAGE
        }
        settings.translateTriggerMode = next
        if (next == org.koitharu.kotatsu.reader.translate.TranslateTriggerMode.AUTO_ON_PAGE && isTranslateConfigured()) {
            getCurrentPage()?.let { translationCoordinator.requestTranslate(it) }
        }
        return next
    }

    fun switchChapter(id: Long, page: Int) {
        navCursor.settle(id)
        launchChapterSwitch(id, page, scroll = 0)
    }

    @MainThread
    fun switchChapterBy(delta: Int) {
        if (delta == 0) {
            // Reload the current chapter in place, keeping the page/scroll position.
            val state = readingState.value ?: return
            navCursor.settle(state.chapterId)
            launchChapterSwitch(state.chapterId, state.page, state.scroll)
            return
        }
        // Resolve the target from the navigation cursor, not the live reading state: the latter is
        // updated asynchronously and can be stale or momentarily point at an adjacent preloaded
        // chapter mid load, which made rapid presses misfire (no advance / jump to chapter start /
        // wrong direction). The cursor chains presses deterministically.
        val allChapterIds = mangaDetails.value?.allChapters?.map { it.id } ?: return
        val targetId = navCursor.resolveRelative(
            allChapterIds = allChapterIds,
            liveChapterId = readingState.value?.chapterId,
            delta = delta,
        ) ?: return // unknown base or first/last chapter reached
        launchChapterSwitch(targetId, page = 0, scroll = 0)
    }

    @MainThread
    private fun launchChapterSwitch(chapterId: Long, page: Int, scroll: Int) {
        stateChangeJob?.cancel()
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            replaceContent(ReaderContent(emptyList(), null))
            chaptersLoader.loadSingleChapter(chapterId)
            val newState = ReaderState(chapterId, page, scroll)
            replaceContent(ReaderContent(chaptersLoader.snapshot(), newState))
            saveCurrentState(newState)
        }
    }

    override suspend fun onDownloadComplete(downloadedManga: LocalManga?) {
        super.onDownloadComplete(downloadedManga)
        // When the chapter currently open in the reader finishes downloading, swap it to the
        // local copy in place so the open chapter starts serving downloaded pages without a reopen.
        val state = readingState.value ?: return
        val details = mangaDetails.value ?: return
        if (downloadedManga != null && details.id == downloadedManga.manga.id) {
            chaptersLoader.init(details)
            if (chaptersLoader.peekChapter(state.chapterId)?.source == LocalMangaSource) {
                val pages = chaptersLoader.getPages(state.chapterId)
                if (pages.isEmpty() || pages.first().source != LocalMangaSource) {
                    runCatchingCancellable {
                        chaptersLoader.loadSingleChapter(state.chapterId)
                    }.onSuccess {
                        replaceContent(ReaderContent(chaptersLoader.snapshot(), state))
                    }
                }
            }
        }
    }

    @MainThread
    fun updateScrollProgress(progress: Float, contentGeneration: Long) {
        val accepted = synchronized(contentStateLock) {
            if (pendingReaderReplacement != null ||
                !lifecycleStateGuard.canCommitUiState() ||
                content.value.generation != contentGeneration
            ) {
                false
            } else {
                lastScrollProgress = progress
                true
            }
        }
        if (!accepted) {
            return
        }
        uiState.value?.let { current ->
            uiState.value = current.copy(scrollProgress = progress)
        }
    }

    @MainThread
    fun updateScrollOffset(chapterId: Long, page: Int, offset: Int, contentGeneration: Long) {
        synchronized(contentStateLock) {
            if (pendingReaderReplacement != null ||
                !lifecycleStateGuard.canCommitUiState() ||
                content.value.generation != contentGeneration
            ) {
                return
            }
            readingState.update { cs ->
                if (cs?.chapterId == chapterId && cs.page == page) {
                    cs.copy(scroll = offset)
                } else {
                    cs
                }
            }
        }
    }

    @MainThread
    fun onCurrentPageChanged(
        lowerPos: Int,
        upperPos: Int,
        scrollProgress: Float = -1f,
        scrollOffset: Int = 0,
        contentGeneration: Long,
    ) {
        val (pages, capturedRevision) = synchronized(contentStateLock) {
            if (
                pendingReaderReplacement != null ||
                    !lifecycleStateGuard.canCommitUiState() ||
                    content.value.generation != contentGeneration
            ) {
                return
            }
            lastScrollProgress = scrollProgress
            content.value.pages to stateRevision
        }
        val capturedScrollOffset = scrollOffset
        val prevJob = stateChangeJob
        stateChangeJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            loadingJob?.join()
            val centerPos = (lowerPos + upperPos) / 2
            val isCurrentSnapshot = synchronized(contentStateLock) {
                if (!lifecycleStateGuard.canCommitUiState() || !canCommitReaderState(
                        capturedPages = pages,
                        currentPages = content.value.pages,
                        capturedRevision = capturedRevision,
                        currentRevision = stateRevision,
                        capturedGeneration = contentGeneration,
                        currentGeneration = content.value.generation,
                    )) {
                    false
                } else {
                    pages.getOrNull(centerPos)?.let { page ->
                        readingState.update { cs ->
                            cs?.copy(chapterId = page.chapterId, page = page.index, scroll = capturedScrollOffset)
                        }
                    }
                    true
                }
            }
            if (!isCurrentSnapshot) {
                return@launchJob
            }
            notifyStateChanged()
            if (pages.isEmpty() || loadingJob?.isActive == true) {
                return@launchJob
            }
            ensureActive()
            // Scrolling has settled with no load in flight, so the reading state reliably reflects
            // the chapter on screen: re-anchor button navigation to it (covers natural scrolling
            // across chapter boundaries) before the auto-load below shifts page positions.
            navCursor.settle(readingState.value?.chapterId)
            val autoLoadAllowed =
                readerMode.value != ReaderMode.WEBTOON || !isWebtoonPullGestureEnabled.value
            if (autoLoadAllowed) {
                if (upperPos >= pages.lastIndex - BOUNDS_PAGE_OFFSET) {
                    loadPrevNextChapter(pages.last().chapterId, isNext = true)
                }
                if (lowerPos <= BOUNDS_PAGE_OFFSET) {
                    loadPrevNextChapter(pages.first().chapterId, isNext = false)
                }
            }
            if (pageLoader.isPrefetchApplicable()) {
                pageLoader.prefetch(pages.trySublist(upperPos + 1, upperPos + PREFETCH_LIMIT))
            }
        }
    }

    fun toggleBookmark() {
        if (bookmarkJob?.isActive == true) {
            return
        }
        bookmarkJob = launchJob(Dispatchers.Default) {
            loadingJob?.join()
            val state = checkNotNull(getCurrentState())
            if (isBookmarkAdded.value) {
                val manga = requireManga()
                bookmarksRepository.removeBookmark(manga.id, state.chapterId, state.page)
                onShowToast.call(R.string.bookmark_removed)
            } else {
                val page = checkNotNull(getCurrentPage()) { "Page not found" }
                val bookmark = Bookmark(
                    manga = requireManga(),
                    pageId = page.id,
                    chapterId = state.chapterId,
                    page = state.page,
                    scroll = state.scroll,
                    imageUrl = page.preview.ifNullOrEmpty { page.url },
                    createdAt = Instant.now(),
                    percent = computePercent(state.chapterId, state.page),
                )
                bookmarksRepository.addBookmark(bookmark)
                onShowToast.call(R.string.bookmark_added)
            }
        }
    }

    fun setIncognitoMode(value: Boolean, dontAskAgain: Boolean) {
        isIncognitoMode.value = value
        if (dontAskAgain) {
            settings.incognitoModeForNsfw = if (value) TriStateOption.ENABLED else TriStateOption.DISABLED
        }
    }

    private fun loadImpl() {
        loadingJob = launchLoadingJob(Dispatchers.Default + EventExceptionHandler(onLoadingError)) {
            var exception: Exception? = null
            var loadedDetails: MangaDetails? = null
            try {
                detailsLoadUseCase(intent, force = false)
                    .collect { details ->
                        loadedDetails = details
                        if (mangaDetails.value == null) {
                            mangaDetails.value = details
                        }
                        val manga = details.toManga()
                        // Novels are rendered by the dedicated text reader; the standard pager
                        // cannot display them (their only "page" is a data: URL). Details can come
                        // from any entry point (Read button, chapter list, bookmarks, shortcuts),
                        // so divert here rather than at each call site.
                        if (manga.source.unwrap() is LnReaderMangaSource && !divertedToNovelReader) {
                            divertedToNovelReader = true
                            onOpenNovelReader.call(manga)
                            loadingJob?.cancel()
                            return@collect
                        }
                        chaptersLoader.init(details)
                        // obtain state
                        if (readingState.value == null) {
                            val newState = getStateFromIntent(manga)
                            if (newState == null) {
                                return@collect // manga not loaded yet if cannot get state
                            }
                            readingState.value = newState
                            navCursor.settle(newState.chapterId)
                            val mode = runCatchingCancellable {
                                detectReaderModeUseCase(manga, newState)
                            }.getOrDefault(settings.defaultReaderMode)
                            val branch = chaptersLoader.peekChapter(newState.chapterId)?.branch
                            selectedBranch.value = branch
                            readerMode.value = mode
                            try {
                                chaptersLoader.loadSingleChapter(newState.chapterId)
                            } catch (e: Exception) {
                                readingState.value = null // try next time
                                exception = e.mergeWith(exception)
                                return@collect
                            }
                        }
                        mangaDetails.value = details.filterChapters(selectedBranch.value)

                        // save state
                        if (!isIncognitoMode.firstNotNull()) {
                            readingState.value?.let {
                                val percent = computePercent(it.chapterId, it.page)
                                historyUpdateUseCase(manga, it, percent)
                            }
                        }
                        notifyStateChanged()
                        replaceContent(ReaderContent(chaptersLoader.snapshot(), readingState.value))
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exception = e.mergeWith(exception)
            }
            if (readingState.value == null) {
                val loadedManga = loadedDetails // for smart cast
                if (loadedManga != null) {
                    mangaDetails.value = loadedManga.filterChapters(selectedBranch.value)
                }
                val loadingError = when {
                    exception != null -> exception
                    loadedManga == null || !loadedManga.isLoaded -> null
                    loadedManga.isRestricted -> EmptyMangaException(
                        EmptyMangaReason.RESTRICTED,
                        loadedManga.toManga(),
                        null,
                    )

                    loadedManga.allChapters.isEmpty() -> EmptyMangaException(
                        EmptyMangaReason.NO_CHAPTERS,
                        loadedManga.toManga(),
                        null,
                    )

                    else -> null
                } ?: IllegalStateException("Unable to load manga. This should never happen. Please report")
                onLoadingError.call(loadingError)
            } else exception?.let { e ->
                // manga has been loaded but error occurred
                errorEvent.call(e)
            }
        }
    }

    @AnyThread
    private fun loadPrevNextChapter(currentId: Long, isNext: Boolean) {
        val prevJob = preloadJob
        preloadJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.join()
            chaptersLoader.loadPrevNextChapter(mangaDetails.requireValue(), currentId, isNext)
            replaceContent(ReaderContent(chaptersLoader.snapshot(), null))
        }
    }

    private fun replaceContent(newContent: ReaderContent) {
        synchronized(contentStateLock) {
            publishContentLocked(newContent)
        }
    }

    /** Must be called under [contentStateLock]. */
    private fun publishContentLocked(newContent: ReaderContent) {
        val replacement = pendingReaderReplacement
        val containsReplacement = replacement != null && newContent.pages.any {
                it.chapterId == replacement.state.chapterId && it.index == replacement.state.page
            }
        content.value = if (replacement != null && containsReplacement) {
            newContent.copy(
                state = replacement.state,
                replacementId = replacement.id,
                generation = ++contentGeneration,
                forceStateRestore = replacement.restoreExistingReader,
            )
        } else {
            // Keep a replacement through a transient empty chapter-switch emission, but do not
            // permanently freeze the reader if the eventual non-empty content no longer contains
            // its target (for example after changing branches).
            if (replacement != null && newContent.pages.isNotEmpty()) {
                pendingReaderReplacement = null
            }
            newContent.copy(
                generation = ++contentGeneration,
                forceStateRestore = false,
            )
        }
    }

    private fun <T> List<T>.trySublist(fromIndex: Int, toIndex: Int): List<T> {
        val fromIndexBounded = fromIndex.coerceAtMost(lastIndex)
        val toIndexBounded = toIndex.coerceIn(fromIndexBounded, lastIndex)
        return if (fromIndexBounded == toIndexBounded) {
            emptyList()
        } else {
            subList(fromIndexBounded, toIndexBounded)
        }
    }

    @WorkerThread
    private fun notifyStateChanged() {
        val state = getCurrentState() ?: return
        val chapter = chaptersLoader.peekChapter(state.chapterId) ?: return
        val m = mangaDetails.value ?: return
        val chapterIndex = m.chapters[chapter.branch]?.indexOfFirst { it.id == chapter.id } ?: -1
        val newState = ReaderUiState(
            mangaName = m.toManga().title,
            chapter = chapter,
            chapterIndex = chapterIndex,
            chaptersTotal = m.chapters[chapter.branch].sizeOrZero(),
            totalPages = chaptersLoader.getPagesCount(chapter.id),
            currentPage = state.page,
            percent = computePercent(state.chapterId, state.page),
            incognito = isIncognitoMode.value == true,
            scrollProgress = lastScrollProgress,
        )
        uiState.value = newState
        if (isIncognitoMode.value == false) {
            statsCollector.onStateChanged(m.id, state)
            discordRpc.updateRpc(m.toManga(), newState)
        }
    }

    private fun computePercent(chapterId: Long, pageIndex: Int): Float {
        val branch = chaptersLoader.peekChapter(chapterId)?.branch
        val chapters = mangaDetails.value?.chapters?.get(branch) ?: return PROGRESS_NONE
        val chaptersCount = chapters.size
        val chapterIndex = chapters.indexOfFirst { x -> x.id == chapterId }
        val pagesCount = chaptersLoader.getPagesCount(chapterId)
        if (chaptersCount == 0 || pagesCount == 0) {
            return PROGRESS_NONE
        }
        return calculateReaderPercent(chapterIndex, chaptersCount, pageIndex, pagesCount)
    }

    private fun observeIsWebtoonZoomEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM,
        valueProducer = { isWebtoonZoomEnabled },
    )

    private fun observeWebtoonZoomOut() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM_OUT,
        valueProducer = { defaultWebtoonZoomOut },
    )

    private fun getObserveIsZoomControlEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_READER_ZOOM_BUTTONS,
        valueProducer = { isReaderZoomButtonsEnabled },
    )

    private fun initIncognitoMode() {
        if (isIncognitoMode.value != null) {
            return
        }
        launchJob(Dispatchers.Default) {
            interactor.observeIncognitoMode(manga)
                .collect {
                    when (it) {
                        TriStateOption.ENABLED -> isIncognitoMode.value = true
                        TriStateOption.ASK -> {
                            onAskNsfwIncognito.call(Unit)
                            return@collect
                        }

                        TriStateOption.DISABLED -> isIncognitoMode.value = false
                    }
                }
        }
    }

    private suspend fun getStateFromIntent(manga: Manga): ReaderState? {
        // check if we have at least some chapters loaded
        if (manga.chapters.isNullOrEmpty()) {
            return null
        }
        // specific state is requested
        val requestedState: ReaderState? = savedStateHandle[ReaderIntent.EXTRA_STATE]
        if (requestedState != null) {
            return if (manga.findChapterById(requestedState.chapterId) != null) {
                requestedState
            } else {
                null
            }
        }

        val requestedBranch: String? = savedStateHandle[ReaderIntent.EXTRA_BRANCH]
        // continue reading
        val history = historyRepository.getOne(manga)
        if (history != null) {
            val chapter = manga.findChapterById(history.chapterId) ?: return null
            // specified branch is requested
            return if (ReaderIntent.EXTRA_BRANCH in savedStateHandle) {
                if (chapter.branch == requestedBranch) {
                    ReaderState(history)
                } else {
                    ReaderState(manga, requestedBranch)
                }
            } else {
                ReaderState(history)
            }
        }

        // start from beginning
        val preferredBranch = requestedBranch ?: manga.getPreferredBranch(null)
        return ReaderState(manga, preferredBranch)
    }

    private fun Exception.mergeWith(other: Exception?): Exception = if (other == null) {
        this
    } else {
        other.addSuppressed(this)
        other
    }

    private data class PendingReaderReplacement(
        val id: Long,
        val state: ReaderState,
        val restoreExistingReader: Boolean,
    )
}
