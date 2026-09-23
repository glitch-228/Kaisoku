package org.koitharu.kotatsu.scrobbling.anilist.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.common.domain.model.AniListLibraryFilter
import org.koitharu.kotatsu.scrobbling.common.domain.model.AniListLibraryEntry
import javax.inject.Inject

@HiltViewModel
class AniListLibraryViewModel @Inject constructor(
	private val repository: AniListRepository,
) : BaseViewModel() {

	val entries = MutableStateFlow<List<AniListLibraryEntry>>(emptyList())
	private val status = MutableStateFlow<String?>(null)
	private val search = MutableStateFlow("")
	val sortByTitle = MutableStateFlow(false)
	val errorMessage = MutableStateFlow<String?>(null)
	val isConnected = MutableStateFlow(repository.isAuthorized)
	val selectedStatus = status

	val content = combine(entries, status, search, sortByTitle, AniListLibraryFilter::apply)
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	fun setStatus(value: String?) {
		status.value = value
	}

	fun setSearch(value: String) {
		search.value = value
	}

	fun toggleSort() {
		sortByTitle.value = !sortByTitle.value
	}

	fun refreshIfStale() = load(force = false)
	fun refresh() = load(force = true)

	private fun load(force: Boolean) {
		launchLoadingJob(Dispatchers.IO) {
			errorMessage.value = null
			isConnected.value = repository.isAuthorized
			if (!repository.isAuthorized) {
				entries.value = emptyList()
				errorMessage.value = null
				return@launchLoadingJob
			}
			try {
				val user = repository.cachedUser ?: repository.loadUser()
				entries.value = repository.cachedLibrary(user.id)
				entries.value = repository.refreshLibrary(user.id, force)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				errorMessage.value = error.message?.takeIf(String::isNotBlank)
					?: error.javaClass.simpleName
			}
		}
	}
}
