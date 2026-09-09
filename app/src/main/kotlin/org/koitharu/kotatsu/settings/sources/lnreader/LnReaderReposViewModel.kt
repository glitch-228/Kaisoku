package org.koitharu.kotatsu.settings.sources.lnreader

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.list.ui.model.ListModel
import javax.inject.Inject

@HiltViewModel
class LnReaderReposViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val reposRepository: LnReaderPluginReposRepository,
) : BaseViewModel() {

	val onMessage = MutableEventFlow<String>()

	val content: StateFlow<List<ListModel>> = reposRepository.observeRepos()
		.map { repos ->
			repos.sortedBy { it.label.lowercase() }
				.mapTo(ArrayList<ListModel>(repos.size.coerceAtLeast(1))) { repo ->
					LnReaderRepoListItem.Repo(repo)
				}
				.ifEmpty {
					listOf(
						LnReaderRepoListItem.Hint(
							icon = R.drawable.ic_empty_feed,
							title = R.string.no_lnreader_repositories,
							text = R.string.no_lnreader_repositories_text,
						),
					)
				}
		}.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS),
			emptyList(),
		)

	fun addRepo(rawUrl: String) {
		launchLoadingJob(Dispatchers.IO) {
			when (reposRepository.addRepo(rawUrl.trim())) {
				LnReaderPluginReposRepository.AddRepoResult.SUCCESS -> {
					onMessage.call(context.getString(R.string.extension_repo_added))
				}

				LnReaderPluginReposRepository.AddRepoResult.INVALID_URL -> {
					onMessage.call(context.getString(R.string.invalid_extension_repo))
				}

				LnReaderPluginReposRepository.AddRepoResult.ALREADY_EXISTS -> {
					onMessage.call(context.getString(R.string.duplicate_extension_repo))
				}
			}
		}
	}

	fun removeRepo(repo: LnReaderPluginRepo) {
		if (reposRepository.removeRepo(repo.url)) {
			onMessage.call(context.getString(R.string.extension_repo_removed))
		}
	}

	private companion object {
		private const val CONTENT_STOP_TIMEOUT_MS = 5000L
	}
}
