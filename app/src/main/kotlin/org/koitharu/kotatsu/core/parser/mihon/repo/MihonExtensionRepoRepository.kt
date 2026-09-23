package org.koitharu.kotatsu.core.parser.mihon.repo

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil
import org.koitharu.kotatsu.core.parser.mihon.MihonInstalledExtensionPackage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionRepoRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repoStore: MihonExtensionRepoStore,
	private val repoService: MihonExtensionRepoService,
	private val privateExtensionStore: MihonPrivateExtensionStore,
	private val settings: org.koitharu.kotatsu.core.prefs.AppSettings,
	@MangaHttpClient private val httpClient: OkHttpClient,
) {

	private val pm
		get() = context.packageManager

	fun observeRepos(): Flow<List<MihonExtensionRepo>> = repoStore.observeAll()

	fun getRepos(): List<MihonExtensionRepo> = repoStore.getAll()

	fun findRepo(baseUrl: String): MihonExtensionRepo? = repoStore.find(baseUrl)

	suspend fun addRepo(indexUrl: String): AddRepoResult {
		return when (val result = repoService.resolveRepo(indexUrl)) {
			MihonExtensionRepoService.ResolveResult.InvalidRepo -> AddRepoResult.InvalidRepo
			MihonExtensionRepoService.ResolveResult.InvalidUrl -> AddRepoResult.InvalidUrl
			is MihonExtensionRepoService.ResolveResult.Success -> when (val insert = repoStore.add(result.repo)) {
				MihonExtensionRepoStore.AddResult.RepoAlreadyExists -> AddRepoResult.RepoAlreadyExists
				MihonExtensionRepoStore.AddResult.Success -> AddRepoResult.Success(result.repo)
				is MihonExtensionRepoStore.AddResult.DuplicateFingerprint -> {
					AddRepoResult.DuplicateFingerprint(insert.existingRepo)
				}
			}
		}
	}

	fun removeRepo(baseUrl: String): Boolean = repoStore.remove(baseUrl)

	suspend fun getExtensions(baseUrl: String): List<MihonRepoExtensionDescriptor> {
		val repo = repoStore.find(baseUrl) ?: return emptyList()
		return repoService.fetchExtensions(repo).map { extension ->
			val installed = getInstalledPackage(extension.pkgName)
			val packageInfo = installed?.packageInfo
			val installedVersionName = packageInfo?.versionName
			val installedVersionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode)
			val installedLibVersion = MihonExtensionPackageUtil.parseLibVersion(installedVersionName)
			val hasUpdate = when {
				installedVersionCode == null -> false
				extension.versionCode > installedVersionCode -> true
				extension.versionCode < installedVersionCode -> false
				else -> extension.libVersion > (installedLibVersion ?: 0.0)
			}
			MihonRepoExtensionDescriptor(
				extension = extension,
				installedVersionName = installedVersionName,
				installedVersionCode = installedVersionCode,
				installedLibVersion = installedLibVersion,
				isInstalledPrivately = installed?.isPrivate == true,
				isInstalledExternally = installed?.isPrivate == false,
				hasUpdate = hasUpdate,
			)
		}
	}

	suspend fun installExtension(extension: MihonAvailableExtension): MihonPrivateExtensionStore.InstallResult {
		val tempFile = File.createTempFile("mihon-ext-", ".apk", context.cacheDir)
		return try {
			httpClient.newCall(
				Request.Builder()
					.url(repoService.getApkUrl(extension))
					.build(),
			).awaitSuccess().use { response ->
				response.body.byteStream().use { input ->
					tempFile.outputStream().use { output ->
						input.copyTo(output)
					}
				}
			}
			privateExtensionStore.installDownloadedExtension(tempFile, extension)
		} finally {
			tempFile.delete()
		}
	}

	fun uninstallExtension(pkgName: String): Boolean = privateExtensionStore.uninstall(pkgName)

	private fun getInstalledPackage(pkgName: String): MihonInstalledExtensionPackage? {
		val privatePkg = privateExtensionStore.findInstalledPackage(pkgName)?.let {
			MihonInstalledExtensionPackage(it, isPrivate = true)
		}
		val sharedPkg = if (settings.useAndroidInstalledExtensions) {
			MihonExtensionPackageUtil.getPackageInfoOrNull(pm, pkgName)
			?.takeIf(MihonExtensionPackageUtil::isMihonExtension)
			?.let { MihonInstalledExtensionPackage(it, isPrivate = false) }
		} else {
			null
		}
		return MihonExtensionPackageUtil.selectPreferred(sharedPkg, privatePkg)
	}

	sealed interface AddRepoResult {
		data class Success(val repo: MihonExtensionRepo) : AddRepoResult
		data object InvalidUrl : AddRepoResult
		data object InvalidRepo : AddRepoResult
		data object RepoAlreadyExists : AddRepoResult
		data class DuplicateFingerprint(val existingRepo: MihonExtensionRepo) : AddRepoResult
	}
}
