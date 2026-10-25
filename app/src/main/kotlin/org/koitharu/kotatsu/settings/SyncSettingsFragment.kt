package org.koitharu.kotatsu.settings

import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentResultListener
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.SyncController
import org.koitharu.kotatsu.sync.drive.DriveAuthorization
import org.koitharu.kotatsu.sync.drive.DriveAuthorizationErrorPolicy
import org.koitharu.kotatsu.sync.drive.DriveContentSection
import org.koitharu.kotatsu.sync.drive.DriveSyncProgress
import org.koitharu.kotatsu.sync.drive.GoogleDriveAuth
import org.koitharu.kotatsu.sync.drive.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.drive.GoogleDriveWorker
import org.koitharu.kotatsu.sync.drive.SyncBackend
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import org.koitharu.kotatsu.sync.ui.SyncHostDialogFragment
import javax.inject.Inject

@AndroidEntryPoint
class SyncSettingsFragment : BasePreferenceFragment(R.string.sync_settings), FragmentResultListener {

    @Inject
    lateinit var syncSettings: SyncSettings

    @Inject
    lateinit var syncController: SyncController
    @Inject
    lateinit var backendSettings: SyncBackendSettings
    @Inject
    lateinit var driveAuth: GoogleDriveAuth
    @Inject
    lateinit var driveRepository: GoogleDriveSyncRepository
    @Inject
    lateinit var driveScheduler: GoogleDriveWorker.Scheduler
    @Inject
    lateinit var serverExtendedScheduler: org.koitharu.kotatsu.sync.extended.appserver.ServerExtendedWorker.Scheduler
    @Inject
    lateinit var serverExtendedRepository: org.koitharu.kotatsu.sync.extended.appserver.ServerExtendedSyncRepository
    @Inject
    lateinit var extendedDriveRepository: org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveRepository
    @Inject
    lateinit var extendedDriveScheduler: org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveWorker.Scheduler

    private val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        try {
            driveAuth.authorizationFromIntent(result.data)
            activateDrive(startNow = true)
        } catch (e: Throwable) {
            handleDriveAuthorizationError(e)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_sync)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.setFragmentResultListener(SyncHostDialogFragment.REQUEST_KEY, viewLifecycleOwner, this)
        bindProviderControls()
        bindSummaries()
        viewLifecycleScope.launch {
            driveRepository.progress.collect { progress -> bindDriveProgress(progress) }
        }
    }

    override fun onResume() {
        super.onResume()
        bindSummaries()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            SyncBackendSettings.KEY_DRIVE_AUTHORIZE -> {
                authorizeDrive()
                true
            }

            SyncBackendSettings.KEY_DRIVE_SYNC_NOW -> {
                authorizeDrive()
                true
            }

            SyncBackendSettings.KEY_DRIVE_DISCONNECT -> {
                selectBackend(SyncBackend.NONE)
                true
            }

            SyncSettings.KEY_SYNC_URL -> {
                SyncHostDialogFragment.show(childFragmentManager, null)
                true
            }

            SyncSettings.KEY_SYNC -> {
                if (backendSettings.backend != SyncBackend.KAISOKU_SERVER) {
                    selectServerBackend()
                    return true
                }
                val am = AccountManager.get(requireContext())
                val accountType = getString(R.string.account_type_sync)
                val account = am.getAccountsByType(accountType).firstOrNull()
                if (account == null) {
                    syncController.addAccount(requireActivity()) {
                        bindSummaries()
                    }
                } else {
                    if (!router.openSystemSyncSettings(account)) {
                        Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
                    }
                }
                true
            }

            SyncSettings.KEY_LOGOUT -> {
                val am = AccountManager.get(requireContext())
                val accountType = getString(R.string.account_type_sync)
                val account = am.getAccountsByType(accountType).firstOrNull()
                if(account != null) {
                    syncController.removeAccount(requireActivity(), account) {
                        viewLifecycleScope.launch(Dispatchers.IO) {
                            stopExtendedSync()
                        }
                        bindSummaries()
                    }
                }
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

    override fun onFragmentResult(requestKey: String, result: Bundle) {
        viewLifecycleScope.launch(Dispatchers.IO) {
            stopExtendedSync()
        }
        bindSummaries()
    }

    private suspend fun stopExtendedSync() {
        serverExtendedScheduler.unschedule()
        try { serverExtendedRepository.disable() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            withContext(Dispatchers.Main) {
                view?.let { current ->
                    Snackbar.make(current, error.getDisplayMessage(resources), Snackbar.LENGTH_LONG)
                        .setAction(R.string.copy) {
                            context?.copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails())
                        }.show()
                }
            }
        }
    }

    private fun bindHostSummary() {
        val preference = findPreference<Preference>(SyncSettings.KEY_SYNC_URL) ?: return
        preference.summary = syncSettings.syncUrl
    }

    private fun bindSummaries() {
        findPreference<ListPreference>(SyncBackendSettings.KEY_BACKEND)?.value = backendSettings.backend.name
        bindHostSummary()
        bindSyncSummary()
        bindDriveSummary()
    }

    private fun bindProviderControls() {
        findPreference<ListPreference>(SyncBackendSettings.KEY_BACKEND)?.setOnPreferenceChangeListener { _, value ->
            val backend = runCatching { SyncBackend.valueOf(value.toString()) }.getOrNull() ?: return@setOnPreferenceChangeListener false
            when (backend) {
                SyncBackend.GOOGLE_DRIVE -> authorizeDrive()
                SyncBackend.KAISOKU_SERVER -> selectServerBackend()
                SyncBackend.NONE -> selectBackend(SyncBackend.NONE)
            }
            false
        }
        findPreference<ListPreference>(SyncBackendSettings.KEY_DRIVE_INTERVAL)?.run {
            value = backendSettings.intervalMinutes.toString()
            setOnPreferenceChangeListener { _, value ->
                backendSettings.intervalMinutes = value.toString().toInt()
                rescheduleDrive()
                true
            }
        }
        findPreference<SwitchPreferenceCompat>(SyncBackendSettings.KEY_DRIVE_WIFI_ONLY)?.run {
            isChecked = backendSettings.isWifiOnly
            setOnPreferenceChangeListener { _, value ->
                backendSettings.isWifiOnly = value as Boolean
                rescheduleDrive()
                true
            }
        }
        findPreference<SwitchPreferenceCompat>(SyncBackendSettings.KEY_DRIVE_SYNC_ON_START)?.run {
            isChecked = backendSettings.isSyncOnStart
            setOnPreferenceChangeListener { _, value ->
                backendSettings.isSyncOnStart = value as Boolean
                true
            }
        }
        findPreference<MultiSelectListPreference>(SyncBackendSettings.KEY_DRIVE_CONTENT)?.run {
            values = backendSettings.contentSections.mapTo(hashSetOf()) { it.name }
            setOnPreferenceChangeListener { _, value ->
                @Suppress("UNCHECKED_CAST")
                val names = value as Set<String>
                backendSettings.contentSections = names.mapNotNullTo(hashSetOf()) {
                    runCatching { DriveContentSection.valueOf(it) }.getOrNull()
                }
                true
            }
        }
    }

    private fun selectServerBackend() {
        val am = AccountManager.get(requireContext())
        val account = am.getAccountsByType(getString(R.string.account_type_sync)).firstOrNull()
        if (account == null) {
            syncController.addAccount(requireActivity()) { added ->
                if (added != null) selectBackend(SyncBackend.KAISOKU_SERVER) else bindSummaries()
            }
        } else {
            selectBackend(SyncBackend.KAISOKU_SERVER)
        }
    }

    private fun selectBackend(backend: SyncBackend) {
        extendedDriveRepository.invalidateAuthorization()
        backendSettings.backend = backend
        val am = AccountManager.get(requireContext())
        val account = am.getAccountsByType(getString(R.string.account_type_sync)).firstOrNull()
        if (account != null) syncController.setEnabled(
            account,
            syncFavorites = backend == SyncBackend.KAISOKU_SERVER,
            syncHistory = backend == SyncBackend.KAISOKU_SERVER,
        )
        viewLifecycleScope.launch(Dispatchers.Default) {
            if (backend == SyncBackend.GOOGLE_DRIVE) driveScheduler.schedule() else driveScheduler.unschedule()
            if (backend == SyncBackend.GOOGLE_DRIVE) extendedDriveScheduler.schedule()
            else extendedDriveScheduler.unschedule()
            if (backend == SyncBackend.NONE) extendedDriveRepository.disable()
            if (backend == SyncBackend.KAISOKU_SERVER) serverExtendedScheduler.schedule()
            else serverExtendedScheduler.unschedule()
            if (backend == SyncBackend.KAISOKU_SERVER) syncController.requestFullSync()
        }
        bindSummaries()
    }

    private fun activateDrive(startNow: Boolean) {
        selectBackend(SyncBackend.GOOGLE_DRIVE)
        if (startNow) viewLifecycleScope.launch(Dispatchers.Default) {
            driveScheduler.startNow()
            withContext(Dispatchers.Main) {
                Snackbar.make(listView, R.string.drive_sync_queued, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun authorizeDrive() {
        extendedDriveRepository.invalidateAuthorization()
        viewLifecycleScope.launch {
            try {
                when (val authorization = driveAuth.authorize(requireActivity())) {
                    is DriveAuthorization.Token -> activateDrive(startNow = true)
                    is DriveAuthorization.Resolution -> {
                        driveAuthorizationLauncher.launch(IntentSenderRequest.Builder(authorization.pendingIntent).build())
                    }
                }
            } catch (e: Throwable) {
                handleDriveAuthorizationError(e)
            }
        }
    }

    private fun rescheduleDrive() {
        if (backendSettings.backend != SyncBackend.GOOGLE_DRIVE) return
        viewLifecycleScope.launch(Dispatchers.Default) {
            driveScheduler.schedule()
            extendedDriveScheduler.schedule()
        }
    }

    private fun bindDriveSummary() {
        findPreference<Preference>(SyncBackendSettings.KEY_DRIVE_AUTHORIZE)?.summary = when {
            backendSettings.accountEmail != null -> getString(R.string.drive_connected_as, backendSettings.accountEmail)
            backendSettings.backend == SyncBackend.GOOGLE_DRIVE -> getString(R.string.drive_connected_pending)
            else -> null
        }
        findPreference<Preference>(SyncBackendSettings.KEY_DRIVE_SYNC_NOW)?.isEnabled =
            backendSettings.backend == SyncBackend.GOOGLE_DRIVE
        findPreference<Preference>(SyncBackendSettings.KEY_DRIVE_DISCONNECT)?.isEnabled =
            backendSettings.backend == SyncBackend.GOOGLE_DRIVE
    }

    private fun bindDriveProgress(progress: DriveSyncProgress) {
        val preference = findPreference<Preference>(SyncBackendSettings.KEY_DRIVE_SYNC_NOW) ?: return
        preference.summary = if (progress.stage == DriveSyncProgress.Stage.IDLE) {
            backendSettings.lastSyncError
        } else {
            getString(R.string.google_drive_sync)
        }
    }

    private fun handleDriveAuthorizationError(error: Throwable) {
        if (error is CancellationException) throw error
        if (error is ApiException && error.statusCode == CommonStatusCodes.CANCELED) return
        if (
            error is ApiException &&
            DriveAuthorizationErrorPolicy.isApiConsoleSetupError(error.statusCode, error.message)
        ) {
            showDriveSetupError()
            return
        }
        if (error is ApiException && error.statusCode == CommonStatusCodes.API_NOT_CONNECTED) {
            showDriveApiUnavailableError()
            return
        }
        view?.let {
            Snackbar.make(it, error.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showDriveSetupError() {
        val identity = driveAuth.getClientIdentity()
        val fingerprints = identity.sha1Fingerprints.joinToString("\n").ifEmpty { getString(R.string.unknown) }
        val details = getString(R.string.drive_setup_required, identity.packageName, fingerprints)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.drive_setup_required_title)
            .setMessage(details)
            .setPositiveButton(R.string.copy) { _, _ ->
                requireContext().copyToClipboard(getString(R.string.google_drive), identity.asPlainText())
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showDriveApiUnavailableError() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.drive_api_unavailable_title)
            .setMessage(R.string.drive_api_unavailable)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun bindSyncSummary() {
        viewLifecycleScope.launch {
            val account = withContext(Dispatchers.Default) {
                val type = getString(R.string.account_type_sync)
                AccountManager.get(requireContext()).getAccountsByType(type).firstOrNull()
            }
            findPreference<Preference>(SyncSettings.KEY_SYNC)?.run {
                summary = when {
                    account == null -> getString(R.string.sync_login)
                    syncController.isEnabled(account) -> {
                        val enabledSync = ArrayList<String>()
                        if(syncController.isFavouritesEnabled(account)) enabledSync.add(getString(R.string.favourites))
                        if(syncController.isHistoryEnabled(account)) enabledSync.add(getString(R.string.history))

                        account.name + enabledSync.joinToString(", ", " (", ")")
                    }
                    else -> getString(R.string.disabled)
                }
            }
            findPreference<Preference>(SyncSettings.KEY_SYNC_URL)?.isEnabled = account != null
            findPreference<Preference>(SyncSettings.KEY_LOGOUT)?.isEnabled = account != null
        }
    }
}
