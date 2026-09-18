package org.koitharu.kotatsu.settings.sources.repo

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.settings.sources.ExtensionLanguageFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonPrivateExtensionStore
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonRepoExtensionDescriptor
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonExtensionRepoRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class MihonRepoExtensionsViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repoRepository: MihonExtensionRepoRepository,
	private val mangaSourcesRepository: MangaSourcesRepository,
	savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

	private val baseUrl = checkNotNull(savedStateHandle.get<String>(AppRouter.KEY_URL))
	private val refreshTrigger = MutableStateFlow(0)
	private val searchQuery = MutableStateFlow<String?>(null)

	val onMessage = MutableEventFlow<String>()

	val screenTitle = MutableStateFlow(
		repoRepository.findRepo(baseUrl)?.name
			?: savedStateHandle.get<String>(AppRouter.KEY_TITLE).orEmpty().ifBlank { context.getString(R.string.extensions) },
	)

	val languageFilter = ExtensionLanguageFilter(context, "mihon")
	private val rawContent: StateFlow<List<ListModel>> = refreshTrigger.mapLatest {
		withLoading {
			runCatchingCancellable { loadContent(null) }.getOrElse { error ->
				errorEvent.call(error)
				listOf(MihonRepoExtensionListItem.Hint(R.drawable.ic_error_large, R.string.error, R.string.try_again))
			}
		}
	}.stateIn(
		viewModelScope + Dispatchers.IO, SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS), listOf(LoadingState),
	)

	val availableLanguages = rawContent.map { items ->
		items.filterIsInstance<MihonRepoExtensionListItem.Extension>().flatMap {
			(it.descriptor.extension.sources.map { source -> source.lang } + it.descriptor.extension.lang)
		}.map(ExtensionLanguageFilter::normalize).distinct().sorted()
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS), emptyList())

	val content: StateFlow<List<ListModel>> = combine(
		rawContent,
		searchQuery.debounce(SEARCH_DEBOUNCE_TIMEOUT).distinctUntilChanged(),
		languageFilter.selected,
	) { items, query, languages ->
		val extensions = items.filterIsInstance<MihonRepoExtensionListItem.Extension>()
		if (extensions.isEmpty()) items else {
			val filtered = extensions.filter {
				(query.isNullOrEmpty() || it.descriptor.matchesQuery(query)) &&
					ExtensionLanguageFilter.matches(languages, (it.descriptor.extension.sources.map { source -> source.lang } + it.descriptor.extension.lang))
			}
			filtered.ifEmpty { listOf(MihonRepoExtensionListItem.Hint(R.drawable.ic_empty_feed, R.string.nothing_found, R.string.no_repo_extensions_found)) }
		}
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS), listOf(LoadingState))

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()?.takeIf { it.isNotEmpty() }
	}

	fun refresh() {
		refreshTrigger.value += 1
	}

	fun onExtensionClick(descriptor: MihonRepoExtensionDescriptor) {
		launchLoadingJob(Dispatchers.IO) {
			when {
				descriptor.hasUpdate || (!descriptor.isInstalledPrivately && !descriptor.isInstalledExternally) -> {
					when (val result = repoRepository.installExtension(descriptor.extension)) {
						MihonPrivateExtensionStore.InstallResult.Success -> {
							mangaSourcesRepository.refreshInstalledMihonSources()
							onMessage.call(context.getString(R.string.extension_installed))
							refresh()
						}

						MihonPrivateExtensionStore.InstallResult.InvalidPackage,
						MihonPrivateExtensionStore.InstallResult.PackageMismatch,
						MihonPrivateExtensionStore.InstallResult.UnsignedPackage -> {
							onMessage.call(context.getString(R.string.extension_install_invalid_package))
						}

						MihonPrivateExtensionStore.InstallResult.UntrustedSignature -> {
							onMessage.call(context.getString(R.string.extension_install_untrusted_signature))
						}

						MihonPrivateExtensionStore.InstallResult.DowngradeNotAllowed -> {
							onMessage.call(context.getString(R.string.extension_install_downgrade_not_allowed))
						}

						MihonPrivateExtensionStore.InstallResult.SignatureMismatch -> {
							onMessage.call(context.getString(R.string.extension_install_signature_mismatch))
						}

						is MihonPrivateExtensionStore.InstallResult.CopyFailed -> {
							errorEvent.call(result.error)
						}
					}
				}

				descriptor.isInstalledPrivately -> {
					if (repoRepository.uninstallExtension(descriptor.extension.pkgName)) {
						mangaSourcesRepository.refreshInstalledMihonSources()
						onMessage.call(context.getString(R.string.extension_removed))
						refresh()
					}
				}
			}
		}
	}

	private suspend fun loadContent(query: String?): List<ListModel> {
		val repo = repoRepository.findRepo(baseUrl)
		if (repo == null) {
			return listOf(
				MihonRepoExtensionListItem.Hint(
					icon = R.drawable.ic_empty_feed,
					title = R.string.extension_repo_not_found,
					text = R.string.no_repo_extensions_text,
				),
			)
		}
		screenTitle.value = repo.name
		val filtered = repoRepository.getExtensions(baseUrl)
			.asSequence()
			.filter { descriptor ->
				query.isNullOrEmpty() || descriptor.matchesQuery(query)
			}
			.sortedWith(
				compareByDescending<MihonRepoExtensionDescriptor> { it.hasUpdate }
					.thenByDescending { it.isInstalledPrivately || it.isInstalledExternally }
					.thenBy { it.extension.name.lowercase() },
			)
			.mapTo(ArrayList<ListModel>()) { descriptor ->
				MihonRepoExtensionListItem.Extension(descriptor)
			}
		return if (filtered.isEmpty()) {
			listOf(
				MihonRepoExtensionListItem.Hint(
					icon = R.drawable.ic_empty_feed,
					title = if (query.isNullOrEmpty()) R.string.nothing_found else R.string.nothing_found,
					text = if (query.isNullOrEmpty()) {
						R.string.no_repo_extensions_text
					} else {
						R.string.no_repo_extensions_found
					},
				),
			)
		} else {
			filtered
		}
	}

	private fun MihonRepoExtensionDescriptor.matchesQuery(query: String): Boolean {
		return extension.name.contains(query, ignoreCase = true) ||
			extension.pkgName.contains(query, ignoreCase = true) ||
			extension.lang.contains(query, ignoreCase = true) ||
			extension.sources.any { source ->
				source.name.contains(query, ignoreCase = true) ||
					source.baseUrl.contains(query, ignoreCase = true)
			}
	}

	private companion object {
		private const val SEARCH_DEBOUNCE_TIMEOUT = 180L
		private const val CONTENT_STOP_TIMEOUT_MS = 5000L
	}
}
