package org.koitharu.kotatsu.settings.work

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.sync.drive.GoogleDriveWorker
import org.koitharu.kotatsu.sync.drive.SyncBackend
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import org.koitharu.kotatsu.tracker.domain.TrackerUnstuckMigrationUseCase
import org.koitharu.kotatsu.tracker.work.TrackWorker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class WorkScheduleManager @Inject constructor(
    private val settings: AppSettings,
    private val suggestionScheduler: SuggestionsWorker.Scheduler,
    private val trackerScheduler: TrackWorker.Scheduler,
    private val trackerUnstuckMigrationProvider: Provider<TrackerUnstuckMigrationUseCase>,
    private val syncBackendSettings: SyncBackendSettings,
    private val googleDriveScheduler: GoogleDriveWorker.Scheduler,
    private val serverExtendedScheduler: org.koitharu.kotatsu.sync.extended.appserver.ServerExtendedWorker.Scheduler,
    private val extendedDriveScheduler: org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveWorker.Scheduler,
    private val communityReplyScheduler: org.koitharu.kotatsu.community.work.CommunityReplyWorker.Scheduler,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            AppSettings.KEY_COMMUNITY_ENABLED,
            AppSettings.KEY_COMMUNITY_REPLY_CHECKS -> updateWorker(
                communityReplyScheduler, settings.isCommunityReplyChecksEnabled, true,
            )
            AppSettings.KEY_TRACKER_ENABLED,
            AppSettings.KEY_TRACKER_FREQUENCY,
            AppSettings.KEY_TRACKER_WIFI_ONLY -> updateWorker(
                scheduler = trackerScheduler,
                isEnabled = settings.isTrackerEnabled,
                force = key != AppSettings.KEY_TRACKER_ENABLED,
            )

            AppSettings.KEY_SUGGESTIONS,
            AppSettings.KEY_SUGGESTIONS_WIFI_ONLY -> updateWorker(
                scheduler = suggestionScheduler,
                isEnabled = settings.isSuggestionsEnabled,
                force = key != AppSettings.KEY_SUGGESTIONS,
            )
        }
    }

    fun init() {
        settings.subscribe(this)
        processLifecycleScope.launch(Dispatchers.Default) {
            updateWorkerImpl(trackerScheduler, settings.isTrackerEnabled, true) // always force due to adaptive interval
            updateWorkerImpl(suggestionScheduler, settings.isSuggestionsEnabled, false)
            updateWorkerImpl(communityReplyScheduler, settings.isCommunityReplyChecksEnabled, false)
            val driveEnabled = syncBackendSettings.backend == SyncBackend.GOOGLE_DRIVE
            updateWorkerImpl(googleDriveScheduler, driveEnabled, driveEnabled)
            if (driveEnabled && syncBackendSettings.isSyncOnStart) googleDriveScheduler.startNow()
            if (driveEnabled) {
                extendedDriveScheduler.schedule()
                extendedDriveScheduler.startOnLaunch()
            } else extendedDriveScheduler.unschedule()
            if (syncBackendSettings.backend == SyncBackend.KAISOKU_SERVER) {
                serverExtendedScheduler.schedule()
                serverExtendedScheduler.startOnLaunch()
            } else serverExtendedScheduler.unschedule()
        }
        processLifecycleScope.launch(Dispatchers.Default) {
            trackerUnstuckMigrationProvider.get().runIfNeeded()
        }
    }

    private fun updateWorker(scheduler: PeriodicWorkScheduler, isEnabled: Boolean, force: Boolean) {
        processLifecycleScope.launch(Dispatchers.Default) {
            updateWorkerImpl(scheduler, isEnabled, force)
        }
    }

    private suspend fun updateWorkerImpl(scheduler: PeriodicWorkScheduler, isEnabled: Boolean, force: Boolean) {
        if (force || scheduler.isScheduled() != isEnabled) {
            if (isEnabled) {
                scheduler.schedule()
            } else {
                scheduler.unschedule()
            }
        }
    }
}
