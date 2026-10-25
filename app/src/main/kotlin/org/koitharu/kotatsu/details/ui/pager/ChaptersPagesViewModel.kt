package org.koitharu.kotatsu.details.ui.pager

import org.koitharu.kotatsu.reader.domain.readingOrderIndex
import android.app.Activity
import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import okio.FileNotFoundException
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.LocaleStringComparator
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.combine
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.ui.DetailsActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

internal val CHAPTER_NAME_COMPARATOR: Comparator<ChapterListItem> = Comparator { left, right ->
    val leftName = left.chapter.title?.takeIf { it.isNotBlank() } ?: left.chapter.number.toString()
    val rightName = right.chapter.title?.takeIf { it.isNotBlank() } ?: right.chapter.number.toString()
    AlphanumComparator().compare(leftName, rightName)
        .takeIf { it != 0 }
        ?: left.chapter.volume.compareTo(right.chapter.volume).takeIf { it != 0 }
        ?: left.chapter.number.compareTo(right.chapter.number)
}

/** Display ordering only: never feed this result into reader navigation. */
internal fun List<ChapterListItem>.orderChapterList(
    reversed: Boolean,
    sortedByName: Boolean,
    sortedByDate: Boolean = false,
): List<ChapterListItem> {
    if (sortedByDate && !sortedByName) {
        // Unknown dates stay at the end, in source order, in either direction.
        val known = filter { it.chapter.uploadDate > 0 }.sortedBy { it.chapter.uploadDate }
        return (if (reversed) known.asReversed() else known) + filter { it.chapter.uploadDate <= 0 }
    }
    val ordered = if (sortedByName) sortedWith(CHAPTER_NAME_COMPARATOR) else this
    return if (reversed) ordered.asReversed() else ordered
}

abstract class ChaptersPagesViewModel(
    private val appContext: Context,
    @JvmField protected val settings: AppSettings,
    @JvmField protected val interactor: DetailsInteractor,
    private val bookmarksRepository: BookmarksRepository,
    private val historyRepository: HistoryRepository,
    private val downloadScheduler: DownloadWorker.Scheduler,
    private val deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
    private val localStorageChanges: SharedFlow<LocalManga?>,
) : BaseViewModel() {

    val mangaDetails = MutableStateFlow<MangaDetails?>(null)
    val readingState = MutableStateFlow<ReaderState?>(null)

    val onActionDone = MutableEventFlow<ReversibleAction>()
    val onDownloadStarted = MutableEventFlow<Unit>()
    val onMangaRemoved = MutableEventFlow<Manga>()

    private val chaptersQuery = MutableStateFlow("")
    val selectedBranch = MutableStateFlow<String?>(null)

    val manga = mangaDetails.map { x -> x?.toManga() }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    val coverUrl = mangaDetails.map { x -> x?.coverUrl }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    val backdropUrl = mangaDetails.map { x -> x?.backdropUrl }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    private val sourceReadingOrderReversed = mangaDetails
        .map { it?.toManga()?.source }
        .distinctUntilChanged()
        .flatMapLatest { source ->
            if (source == null) flowOf(false) else SourceSettings(appContext, source).observeReadingOrderReversed()
        }

    val isGlobalChaptersReversed = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_REVERSE_CHAPTERS,
        valueProducer = { isChaptersReverse },
    )

    // Reading direction is source-specific. The global reverse setting only orders the list.
    val isReadingOrderReversed = sourceReadingOrderReversed
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    val isChaptersInGridView = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_GRID_VIEW_CHAPTERS,
        valueProducer = { isChaptersGridView },
    )

    private val sortChaptersByName = MutableStateFlow(false)
    val isChaptersSortedByName: StateFlow<Boolean> get() = sortChaptersByName
    private val sortChaptersByDate = MutableStateFlow(false)
    val isChaptersSortedByDate: StateFlow<Boolean> get() = sortChaptersByDate

    val isDownloadedOnly = MutableStateFlow(false)

    val newChaptersCount = mangaDetails.flatMapLatest { d ->
        if (d?.isLocal == false) {
            interactor.observeNewChapters(d.id)
        } else {
            flowOf(0)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

    val emptyReason: StateFlow<EmptyMangaReason?> = combine(
        mangaDetails,
        isLoading,
        onError.onStart { emit(null) },
    ) { details, loading, error ->
        when {
            details == null || loading -> null
            details.chapters.isNotEmpty() -> null
            details.toManga().state == MangaState.RESTRICTED -> EmptyMangaReason.RESTRICTED
            error != null -> EmptyMangaReason.LOADING_ERROR
            else -> EmptyMangaReason.NO_CHAPTERS
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(), null)

    val bookmarks = mangaDetails.flatMapLatest {
        if (it != null) {
            bookmarksRepository.observeBookmarks(it.toManga()).withErrorHandling()
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

    val exactChapterReads = mangaDetails.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(null) else historyRepository.chapterReads.observe(id)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    fun setExactCompletionEnabled(enabled: Boolean, proposal: Set<Long> = emptySet()) {
        val snapshot = getMangaOrNull() ?: return
        launchJob(Dispatchers.Default) {
            if (enabled && historyRepository.chapterReads.snapshot(snapshot.id).policy == null) {
                historyRepository.chapterReads.convert(snapshot, proposal, selectedBranch.value)
            } else historyRepository.chapterReads.setEnabled(snapshot, enabled)
        }
    }

    fun markChaptersRead(ids: Set<Long>, read: Boolean) {
        val snapshot = getMangaOrNull() ?: return
        launchJob(Dispatchers.Default) { historyRepository.chapterReads.mark(snapshot, ids, read) }
    }

    val chapters = combine(
        combine(
            mangaDetails,
            readingState.map { it?.chapterId ?: 0L }.distinctUntilChanged(),
            selectedBranch,
            newChaptersCount,
            bookmarks,
            isChaptersInGridView,
            isDownloadedOnly,
        ) { manga, currentChapterId, branch, news, bookmarks, grid, downloadedOnly ->
            manga?.mapChapters(
                currentChapterId = currentChapterId,
                newCount = news,
                branch = branch,
                bookmarks = bookmarks,
                isGrid = grid,
                isDownloadedOnly = downloadedOnly,
            ).orEmpty()
        },
        isGlobalChaptersReversed,
        sortChaptersByName,
        sortChaptersByDate,
        chaptersQuery,
    ) { list, reversed, sortedByName, sortedByDate, query ->
        list.orderChapterList(reversed, sortedByName, sortedByDate).filterSearch(query)
    }.combine(exactChapterReads) { list, explicit ->
        if (explicit?.isActive != true || explicit.policy?.mangaId != getMangaOrNull()?.id) list else {
            // Resolve before search/download filters: hiding one ambiguous URL must not make it unique.
            val branchChapters = mangaDetails.value?.chapters?.get(selectedBranch.value).orEmpty()
            val read = explicit.readIds(branchChapters).orEmpty()
            list.map { item ->
                val flags = item.flags.toInt()
                val unread = item.chapter.id !in read
                item.copy(flags = if (unread) (flags or ChapterListItem.FLAG_UNREAD.toInt()).toByte()
                    else (flags and ChapterListItem.FLAG_UNREAD.toInt().inv() and ChapterListItem.FLAG_NEW.toInt().inv()).toByte())
            }
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val quickFilter = combine(
        mangaDetails,
        selectedBranch,
    ) { details, branch ->
        val branches = details?.chapters?.toList()?.sortedWithSafe(
            compareBy(LocaleStringComparator()) { it.first },
        ).orEmpty()
        if (branches.size > 1) {
            branches.map {
                val option = ListFilterOption.Branch(titleText = it.first, chaptersCount = it.second.size)
                option.toChipModel(isChecked = it.first == branch)
            }
        } else {
            emptyList()
        }
    }

    init {
        launchJob(Dispatchers.Default) {
            combine(
                mangaDetails.map { it?.id }.distinctUntilChanged(),
                settings.observeAsFlow(AppSettings.KEY_CHAPTER_DATE_SORT_DEFAULT) { isChapterDateSortDefault },
            ) { id, _ -> id }.collect { mangaId ->
                sortChaptersByName.value = mangaId?.let(settings::isChaptersSortedByName) == true
                sortChaptersByDate.value = mangaId?.let(settings::isChaptersSortedByDate) == true
            }
        }
        launchJob(Dispatchers.Default) {
            localStorageChanges
                .collect { onDownloadComplete(it) }
        }
    }

    open fun setReadingOrderReversed(newValue: Boolean) {
        mangaDetails.value?.toManga()?.source?.let { source ->
            SourceSettings(appContext, source).isReadingOrderReversed = newValue
        }
    }

    protected fun isCurrentSourceChaptersReversed(): Boolean {
        val source = mangaDetails.value?.toManga()?.source ?: return false
        return SourceSettings(appContext, source).isReadingOrderReversed == true
    }

    fun setChaptersInGridView(newValue: Boolean) {
        settings.isChaptersGridView = newValue
    }

    fun setChaptersSortedByName(newValue: Boolean) {
        mangaDetails.value?.id?.let { settings.setChaptersSortedByName(it, newValue) }
        sortChaptersByName.value = newValue
        if (newValue) sortChaptersByDate.value = false
    }

    fun setChaptersSortedByDate(newValue: Boolean) {
        mangaDetails.value?.id?.let { settings.setChaptersSortedByDate(it, newValue) }
        sortChaptersByDate.value = newValue
        if (newValue) sortChaptersByName.value = false
    }

    fun setSelectedBranch(branch: String?) {
        selectedBranch.value = branch
    }

    fun performChapterSearch(query: String?) {
        chaptersQuery.value = query?.trim().orEmpty()
    }

    fun getMangaOrNull(): Manga? = mangaDetails.value?.toManga()

    fun requireManga() = mangaDetails.requireValue().toManga()

    fun markChapterAsCurrent(chapterId: Long) {
        launchJob(Dispatchers.Default) {
            val manga = mangaDetails.requireValue()
            val chapters = checkNotNull(manga.chapters[selectedBranch.value])
            val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
            check(chapterIndex in chapters.indices) { "Chapter not found" }
            val percent = readingOrderIndex(chapterIndex, chapters.size, isCurrentSourceChaptersReversed()) / chapters.size.toFloat()
            historyRepository.addOrUpdate(
                manga = manga.toManga(),
                chapterId = chapterId,
                page = 0,
                scroll = 0,
                percent = percent,
                force = true,
            )
        }
    }

    fun download(chaptersIds: Set<Long>?, allowMeteredNetwork: Boolean) {
        launchJob(Dispatchers.Default) {
            val manga = requireManga()
            val task = DownloadTask(
                mangaId = manga.id,
                isPaused = false,
                isSilent = false,
                chaptersIds = chaptersIds?.toLongArray(),
                destination = null,
                format = null,
                allowMeteredNetwork = allowMeteredNetwork,
            )
            downloadScheduler.schedule(setOf(manga to task))
            onDownloadStarted.call(Unit)
        }
    }

    fun deleteLocal() {
        val m = mangaDetails.value?.local?.manga
        if (m == null) {
            errorEvent.call(FileNotFoundException())
            return
        }
        launchLoadingJob(Dispatchers.Default) {
            deleteLocalMangaUseCase(m)
            onMangaRemoved.call(m)
        }
    }

    private fun List<ChapterListItem>.filterSearch(query: String): List<ChapterListItem> {
        if (query.isEmpty() || this.isEmpty()) {
            return this
        }
        return filter { it.contains(query) }
    }

    protected open suspend fun onDownloadComplete(downloadedManga: LocalManga?) {
        downloadedManga ?: return
        mangaDetails.update {
            interactor.updateLocal(it, downloadedManga)
        }
    }

    class ActivityVMLazy(
        private val fragment: Fragment,
    ) : Lazy<ChaptersPagesViewModel> {
        private var cached: ChaptersPagesViewModel? = null

        override val value: ChaptersPagesViewModel
            get() {
                val viewModel = cached
                return if (viewModel == null) {
                    val activity = fragment.requireActivity()
                    val vmClass = getViewModelClass(activity)
                    ViewModelProvider.create(
                        store = activity.viewModelStore,
                        factory = activity.defaultViewModelProviderFactory,
                        extras = activity.defaultViewModelCreationExtras,
                    )[vmClass].also { cached = it }
                } else {
                    viewModel
                }
            }

        override fun isInitialized(): Boolean = cached != null

        private fun getViewModelClass(activity: Activity) = when (activity) {
            is ReaderActivity -> ReaderViewModel::class.java
            is DetailsActivity -> DetailsViewModel::class.java
            else -> error("Wrong activity ${activity.javaClass.simpleName} for ${ChaptersPagesViewModel::class.java.simpleName}")
        }
    }
}
