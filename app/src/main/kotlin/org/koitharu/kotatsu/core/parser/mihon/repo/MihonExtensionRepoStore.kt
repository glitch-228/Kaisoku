package org.koitharu.kotatsu.core.parser.mihon.repo

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.core.util.ext.observe
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionRepoStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}

	fun getAll(): List<MihonExtensionRepo> {
		val value = prefs.getString(KEY_REPOS, null).orEmpty()
		if (value.isBlank()) {
			return emptyList()
		}
		return runCatching {
			json.decodeFromString<List<MihonExtensionRepo>>(value)
		}.getOrDefault(emptyList())
	}

	fun observeAll(): Flow<List<MihonExtensionRepo>> = prefs.observe(KEY_REPOS) { getAll() }

	fun find(baseUrl: String): MihonExtensionRepo? = getAll().firstOrNull { it.baseUrl == baseUrl }

	fun add(repo: MihonExtensionRepo): AddResult {
		val all = getAll()
		if (all.any { it.baseUrl == repo.baseUrl }) {
			return AddResult.RepoAlreadyExists
		}
		val isKeiyoushiMigration = repo.baseUrl.contains("keiyoushi/extensions", ignoreCase = true)
		val fingerprintConflict = all.firstOrNull {
			it.signingKeyFingerprint == repo.signingKeyFingerprint
		}?.takeUnless { isKeiyoushiMigration }
		if (fingerprintConflict != null) {
			return AddResult.DuplicateFingerprint(fingerprintConflict)
		}
		save(all + repo)
		return AddResult.Success
	}

	fun remove(baseUrl: String): Boolean {
		val all = getAll()
		val filtered = all.filterNot { it.baseUrl == baseUrl }
		if (filtered.size == all.size) {
			return false
		}
		save(filtered)
		return true
	}

	private fun save(value: List<MihonExtensionRepo>) {
		prefs.edit {
			putString(KEY_REPOS, json.encodeToString(value))
		}
	}

	sealed interface AddResult {
		data object Success : AddResult
		data object RepoAlreadyExists : AddResult
		data class DuplicateFingerprint(val existingRepo: MihonExtensionRepo) : AddResult
	}

	private companion object {
		const val PREFS_NAME = "mihon_extension_repos"
		const val KEY_REPOS = "repos"
	}
}
