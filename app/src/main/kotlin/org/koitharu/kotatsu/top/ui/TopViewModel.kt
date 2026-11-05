package org.koitharu.kotatsu.top.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.top.domain.TopFilter
import org.koitharu.kotatsu.top.domain.TopRanking
import org.koitharu.kotatsu.top.domain.TopRepository
import org.koitharu.kotatsu.top.domain.TopWindow
import javax.inject.Inject

@HiltViewModel
class TopViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TopRepository,
    settings: AppSettings,
    private val mangaListMapper: MangaListMapper,
    mangaDataRepository: MangaDataRepository,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges) {

    val window = MutableStateFlow(TopWindow.WEEK)
    val filter = MutableStateFlow(TopFilter())
    private val refresh = MutableStateFlow(0L to false)

    val genres: List<String> get() = repository.getGenres()
    val contentTypes: List<ContentType> = listOf(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA)
    val hiddenCount: Int get() = repository.hiddenCount()

    override val content = combine(window, filter, refresh) { w, f, r -> Triple(w, f, r) }
        .flatMapLatest { (window, filter, refresh) ->
            combine(
                repository.observeTop(window, filter, force = refresh.second),
                observeListModeWithTriggers(),
            ) { section, mode ->
                val header = ListHeader(headerText(window, filter))
                val cards = when {
                    section.manga.isNotEmpty() -> mangaListMapper.toListModelList(section.manga, mode)
                    !section.isFinal -> listOf(LoadingState)
                    else -> listOf(
                        EmptyState(
                            icon = R.drawable.ic_empty_common,
                            textPrimary = R.string.nothing_found,
                            textSecondary = R.string.top_empty,
                            actionStringRes = 0,
                        ),
                    )
                }
                listOf<ListModel>(header) + cards + if (!section.isFinal && section.manga.isNotEmpty()) {
                    listOf(LoadingState)
                } else {
                    emptyList()
                }
            }
        }.catch {
            emit(listOf(it.toErrorState(canRetry = true)))
        }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), listOf(LoadingState))

    fun setWindow(value: TopWindow) {
        window.value = value
    }

    fun setFilter(value: TopFilter) {
        filter.value = value
    }

    override fun onRefresh() {
        repository.invalidate()
        refresh.update { (it.first + 1) to true }
    }

    override fun onRetry() = onRefresh()

    fun hide(manga: Collection<Manga>) {
        val keys = repository.hide(manga)
        // Stored titles leave the list at once; the shown chart is rebuilt from the cache
        refresh.update { (it.first + 1) to false }
        onActionDone.call(
            ReversibleAction(R.string.top_hidden, ReversibleHandle {
                repository.unhide(keys)
                refresh.update { (it.first + 1) to false }
            }),
        )
    }

    fun clearHidden() {
        repository.clearHidden()
        refresh.update { (it.first + 1) to false }
    }

    private fun headerText(window: TopWindow, filter: TopFilter): String = buildList {
        add(context.getString(window.titleRes))
        filter.type?.let { add(it.name.lowercase().replaceFirstChar(Char::uppercase)) }
        filter.genre?.let { add(TopRanking.genreTitle(it)) }
    }.joinToString(" · ")
}
