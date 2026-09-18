package org.koitharu.kotatsu.settings.sources.lnreader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.parser.lnreader.LNReaderRepository
import org.koitharu.kotatsu.core.parser.lnreader.RepoEntry
import javax.inject.Inject
import javax.inject.Singleton

data class LnReaderPluginRepo(
	val url: String,
	val label: String,
)

/**
 * User-managed list of LNReader plugin repository URLs (plugins.min.json indexes).
 * The official LNReader repository is always present.
 */
@Singleton
class LnReaderPluginReposRepository @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private val reposSubject = MutableStateFlow(loadRepos())

	fun observeRepos(): Flow<List<LnReaderPluginRepo>> = reposSubject.asStateFlow()

	fun findRepo(url: String): LnReaderPluginRepo? = reposSubject.value.find { it.url == url }

	suspend fun addRepo(url: String): AddRepoResult = withContext(Dispatchers.IO) {
		val normalized = url.trim().removeSuffix("/")
		if (!normalized.startsWith("https://") || !normalized.endsWith(".json")) {
			return@withContext AddRepoResult.INVALID_URL
		}
		val current = prefs.getStringSet(KEY_REPOS, null).orEmpty().toMutableSet()
		if (normalized in current || normalized == LNReaderRepository.OFFICIAL_REPO_URL) {
			return@withContext AddRepoResult.ALREADY_EXISTS
		}
		current.add(normalized)
		prefs.edit().putStringSet(KEY_REPOS, current).apply()
		reposSubject.value = loadRepos()
		AddRepoResult.SUCCESS
	}

	fun removeRepo(url: String): Boolean {
		if (url == LNReaderRepository.OFFICIAL_REPO_URL) {
			return false // preset repo cannot be removed
		}
		val current = prefs.getStringSet(KEY_REPOS, null).orEmpty().toMutableSet()
		if (url !in current) {
			return false
		}
		current.remove(url)
		prefs.edit().putStringSet(KEY_REPOS, current).apply()
		reposSubject.value = loadRepos()
		return true
	}

	private fun loadRepos(): List<LnReaderPluginRepo> {
		val custom = prefs.getStringSet(KEY_REPOS, null).orEmpty()
			.map { LnReaderPluginRepo(url = it, label = it.substringAfterLast('/')) }
		return (LNReaderRepository.PRESET_REPOS.map { it.toRepo() } + custom)
			.distinctBy { it.url }
			.sortedBy { it.label.lowercase() }
	}

	private fun RepoEntry.toRepo() = LnReaderPluginRepo(url = url, label = label)

	enum class AddRepoResult {
		SUCCESS,
		INVALID_URL,
		ALREADY_EXISTS,
	}

	private companion object {
		private const val PREFS_NAME = "lnreader_plugin_repos"
		private const val KEY_REPOS = "repos"
	}
}
