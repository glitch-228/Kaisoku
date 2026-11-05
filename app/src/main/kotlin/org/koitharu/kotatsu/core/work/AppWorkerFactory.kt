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
import org.koitharu.kotatsu.download.ui.worker.DownloadChapterDispatcher
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
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.anilist.work.AniListProgressWorker
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
    private val chapterDispatcher: Provider<DownloadChapterDispatcher>,
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
    private val mangaDataRepositoryLazy: Lazy<MangaDataRepository>,
    private val staleTracksUseCaseLazy: Lazy<org.koitharu.kotatsu.tracker.domain.StaleTracksUseCase>,
    private val deleteReadChaptersUseCase: Provider<DeleteReadChaptersUseCase>,
    private val coil: Provider<ImageLoader>,
    private val suggestionRepository: Provider<SuggestionRepository>,
    private val historyRepository: Provider<HistoryRepository>,
    private val favouritesRepository: Provider<FavouritesRepository>,
    private val sourcesRepository: Provider<MangaSourcesRepository>,
    private val googleDriveSyncRepository: Provider<GoogleDriveSyncRepository>,
    private val syncBackendSettings: Provider<SyncBackendSettings>,
    private val aniListRepository: Provider<AniListRepository>,
    private val trackerLibrary: Provider<org.koitharu.kotatsu.scrobbling.common.data.TrackerLibraryRepository>,
    private val recommendationEngine: Provider<org.koitharu.kotatsu.suggestions.local.RecommendationEngine>,
    private val remoteLibraries: Provider<org.koitharu.kotatsu.remote.library.RemoteLibraryManager>,
    private val libraryImages: Provider<org.koitharu.kotatsu.remote.library.RemoteLibraryImageCache>,
    private val serverExtendedRepository: Provider<org.koitharu.kotatsu.sync.extended.appserver.ServerExtendedSyncRepository>,
    private val extendedDriveRepository: Provider<org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveRepository>,
    private val outgoingProgress: Provider<org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressEngine>,
    private val outgoingCoordinator: Provider<org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressCoordinator>,
    private val community: Provider<org.koitharu.kotatsu.community.data.CommunityRepository>,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        org.koitharu.kotatsu.community.work.CommunityReplyWorker::class.java.name ->
            org.koitharu.kotatsu.community.work.CommunityReplyWorker(appContext, workerParameters, community.get())
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
            chapterDispatcher = chapterDispatcher.get(),
            imageProxyInterceptor = imageProxyInterceptor.get(),
            remoteLibraries = remoteLibraries.get(),
            libraryImages = libraryImages.get(),
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
            mangaDataRepositoryLazy = mangaDataRepositoryLazy,
            staleTracksUseCaseLazy = staleTracksUseCaseLazy,
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
            recommendationEngine = recommendationEngine.get(),
        )

        GoogleDriveWorker::class.java.name -> GoogleDriveWorker(
            context = appContext,
            params = workerParameters,
            repository = googleDriveSyncRepository.get(),
            settings = syncBackendSettings.get(),
        )

        org.koitharu.kotatsu.sync.extended.appserver.ServerExtendedWorker::class.java.name ->
            org.koitharu.kotatsu.sync.extended.appserver.ServerExtendedWorker(
                appContext, workerParameters, serverExtendedRepository.get(), syncBackendSettings.get(),
            )

        org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveWorker::class.java.name ->
            org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveWorker(
                appContext, workerParameters, extendedDriveRepository.get(),
            )

        AniListProgressWorker::class.java.name -> AniListProgressWorker(
            context = appContext,
            params = workerParameters,
            repository = aniListRepository.get(),
        )

        org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressWorker::class.java.name ->
            org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressWorker(appContext, workerParameters, outgoingProgress.get())

        org.koitharu.kotatsu.scrobbling.common.work.TrackerProgressWorker::class.java.name ->
            org.koitharu.kotatsu.scrobbling.common.work.TrackerProgressWorker(appContext, workerParameters, trackerLibrary.get(), outgoingCoordinator)

        else -> null
    }
}
