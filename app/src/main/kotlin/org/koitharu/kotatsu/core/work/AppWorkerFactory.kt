package org.koitharu.kotatsu.core.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import coil3.ImageLoader
import dagger.Lazy
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.ui.worker.DownloadNotificationFactory
import org.koitharu.kotatsu.download.ui.worker.DownloadSlowdownDispatcher
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.local.domain.DeleteReadChaptersUseCase
import org.koitharu.kotatsu.local.domain.MangaLock
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.ui.LocalStorageCleanupWorker
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.sync.drive.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.drive.GoogleDriveWorker
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import org.koitharu.kotatsu.tracker.domain.CheckNewChaptersUseCase
import org.koitharu.kotatsu.tracker.domain.GetTracksUseCase
import org.koitharu.kotatsu.tracker.work.TrackWorker
import org.koitharu.kotatsu.tracker.work.TrackerNotificationHelper
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppWorkerFactory @Inject constructor(
	@MangaHttpClient private val okHttp: Provider<OkHttpClient>,
	@PageCache private val cache: Provider<LocalStorageCache>,
	private val localMangaRepository: Provider<LocalMangaRepository>,
	private val mangaLock: Provider<MangaLock>,
	private val mangaDataRepository: Provider<MangaDataRepository>,
	private val mangaRepositoryFactory: Provider<MangaRepository.Factory>,
	private val settings: Provider<AppSettings>,
	@LocalStorageChanges private val localStorageChanges: Provider<MutableSharedFlow<LocalManga?>>,
	private val slowdownDispatcher: Provider<DownloadSlowdownDispatcher>,
	private val imageProxyInterceptor: Provider<ImageProxyInterceptor>,
	private val downloadNotificationFactoryFactory: Provider<DownloadNotificationFactory.Factory>,
	private val captchaHandler: Provider<CaptchaHandler>,
	private val trackerNotificationHelper: Provider<TrackerNotificationHelper>,
	private val getTracksUseCase: Provider<GetTracksUseCase>,
	private val checkNewChaptersUseCase: Provider<CheckNewChaptersUseCase>,
	private val workManager: Provider<WorkManager>,
	private val localRepositoryLazy: Lazy<LocalMangaRepository>,
	private val downloadSchedulerLazy: Lazy<DownloadWorker.Scheduler>,
	private val historyRepositoryLazy: Lazy<HistoryRepository>,
	private val storageManagerLazy: Lazy<LocalStorageManager>,
	private val deleteReadChaptersUseCase: Provider<DeleteReadChaptersUseCase>,
	private val coil: Provider<ImageLoader>,
	private val suggestionRepository: Provider<SuggestionRepository>,
	private val historyRepository: Provider<HistoryRepository>,
	private val favouritesRepository: Provider<FavouritesRepository>,
	private val sourcesRepository: Provider<MangaSourcesRepository>,
	private val googleDriveSyncRepository: Provider<GoogleDriveSyncRepository>,
	private val syncBackendSettings: Provider<SyncBackendSettings>,
) : WorkerFactory() {

	override fun createWorker(
		appContext: Context,
		workerClassName: String,
		workerParameters: WorkerParameters,
	): ListenableWorker? = when (workerClassName) {
		DownloadWorker::class.java.name -> DownloadWorker(
			appContext = appContext,
			params = workerParameters,
			okHttp = okHttp.get(),
			cache = cache.get(),
			localMangaRepository = localMangaRepository.get(),
			mangaLock = mangaLock.get(),
			mangaDataRepository = mangaDataRepository.get(),
			mangaRepositoryFactory = mangaRepositoryFactory.get(),
			settings = settings.get(),
			localStorageChanges = localStorageChanges.get(),
			slowdownDispatcher = slowdownDispatcher.get(),
			imageProxyInterceptor = imageProxyInterceptor.get(),
			notificationFactoryFactory = downloadNotificationFactoryFactory.get(),
		)

		TrackWorker::class.java.name -> TrackWorker(
			context = appContext,
			workerParams = workerParameters,
			captchaHandler = captchaHandler.get(),
			notificationHelper = trackerNotificationHelper.get(),
			settings = settings.get(),
			getTracksUseCase = getTracksUseCase.get(),
			checkNewChaptersUseCase = checkNewChaptersUseCase.get(),
			workManager = workManager.get(),
			localRepositoryLazy = localRepositoryLazy,
			downloadSchedulerLazy = downloadSchedulerLazy,
			historyRepositoryLazy = historyRepositoryLazy,
			storageManagerLazy = storageManagerLazy,
		)

		LocalStorageCleanupWorker::class.java.name -> LocalStorageCleanupWorker(
			appContext = appContext,
			params = workerParameters,
			settings = settings.get(),
			localMangaRepository = localMangaRepository.get(),
			dataRepository = mangaDataRepository.get(),
			deleteReadChaptersUseCase = deleteReadChaptersUseCase.get(),
		)

		SuggestionsWorker::class.java.name -> SuggestionsWorker(
			appContext = appContext,
			params = workerParameters,
			coil = coil.get(),
			suggestionRepository = suggestionRepository.get(),
			historyRepository = historyRepository.get(),
			favouritesRepository = favouritesRepository.get(),
			appSettings = settings.get(),
			captchaHandler = captchaHandler.get(),
			workManager = workManager.get(),
			mangaRepositoryFactory = mangaRepositoryFactory.get(),
			sourcesRepository = sourcesRepository.get(),
		)

		GoogleDriveWorker::class.java.name -> GoogleDriveWorker(
			context = appContext,
			params = workerParameters,
			repository = googleDriveSyncRepository.get(),
			settings = syncBackendSettings.get(),
		)

		else -> null
	}
}
