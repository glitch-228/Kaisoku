package org.koitharu.kotatsu.sync.extended.drive

import android.os.Bundle
import android.view.View
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.sync.drive.DriveAuthorization
import javax.inject.Inject

/** This screen opens locally; OAuth, uploads and unknown-outcome recovery are separate explicit actions. */
@AndroidEntryPoint
class ExtendedDriveSettingsFragment : BasePreferenceFragment(R.string.extended_drive_title) {
    @Inject lateinit var repository: ExtendedDriveRepository
    @Inject lateinit var scheduler: ExtendedDriveWorker.Scheduler
    private var pending = Action.CHECK
    private var revision = -1L
    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        action {
            repository.authorizationFromIntent(result.data, revision)
            finishAuthorization()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_extended_drive, rootKey)
        revision = savedInstanceState?.getLong("authorizationRevision", -1) ?: -1
        pending = savedInstanceState?.getString("authorizationAction")?.let {
            runCatching { Action.valueOf(it) }.getOrNull()
        } ?: Action.CHECK
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("authorizationRevision", revision)
        outState.putString("authorizationAction", pending.name)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        findPreference<MultiSelectListPreference>(CONTENT)?.run {
            entryValues = ExtendedDriveProtocol.namespaces.toTypedArray()
            entries = ExtendedDriveProtocol.namespaces.map { label(it) }.toTypedArray()
            setOnPreferenceChangeListener { _, raw ->
                @Suppress("UNCHECKED_CAST") val namespaces = raw as Set<String>
                action {
                    withContext(Dispatchers.IO) {
                        val store = repository.storage.store
                        store.configure(store.configuration().copy(namespaces = namespaces,
                            enabled = store.configuration().enabled && namespaces.isNotEmpty()))
                    }
                    scheduler.schedule()
                }
                false
            }
        }
        findPreference<SwitchPreferenceCompat>(ENABLED)?.setOnPreferenceChangeListener { _, raw ->
            action {
                if (raw == true) {
                    val namespaces = withContext(Dispatchers.IO) { repository.storage.configuration().namespaces }
                    repository.enable(namespaces)
                    scheduler.schedule()
                } else { repository.disable(); scheduler.unschedule() }
            }
            false
        }
        findPreference<SwitchPreferenceCompat>(AUTOMATIC)?.setOnPreferenceChangeListener { _, raw ->
            action {
                withContext(Dispatchers.IO) { repository.storage.setAutomatic(raw as Boolean) }
                scheduler.schedule()
            }
            false
        }
        action { bind() }
        viewLifecycleScope.launch { repository.progress.collect { bind() } }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
        CHECK -> { beginAuthorization(Action.CHECK); true }
        SYNC -> { beginAuthorization(Action.SYNC); true }
        RESUME -> {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.extended_drive_resume)
                .setMessage(R.string.extended_drive_resume_description)
                .setPositiveButton(android.R.string.ok) { _, _ -> beginAuthorization(Action.RESUME) }
                .setNegativeButton(android.R.string.cancel, null).show()
            true
        }
        COPY -> { action { copyDiagnostics() }; true }
        else -> super.onPreferenceTreeClick(preference)
    }

    private fun beginAuthorization(mode: Action) = action {
        pending = mode
        val request = repository.authorize(requireActivity())
        revision = request.revision
        when (val result = request.authorization) {
            is DriveAuthorization.Token -> { repository.acceptToken(result.value, revision); finishAuthorization() }
            is DriveAuthorization.Resolution -> authorization.launch(IntentSenderRequest.Builder(result.pendingIntent).build())
        }
    }

    private suspend fun finishAuthorization() {
        val user = checkNotNull(repository.checkedUser())
        findPreference<Preference>(CHECK)?.summary = getString(R.string.extended_drive_verified,
            user.emailAddress ?: user.displayName.orEmpty(), user.permissionId)
        when (pending) {
            Action.CHECK -> Unit
            Action.SYNC -> repository.sync()
            Action.RESUME -> repository.sync(resume = true)
        }
        pending = Action.CHECK
    }

    private suspend fun bind() {
        val state = try { withContext(Dispatchers.IO) { repository.storage.configuration() to repository.storage.result() } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            findPreference<Preference>(STATUS)?.setSummary(R.string.extended_drive_storage)
            listOf(ENABLED, CONTENT, AUTOMATIC, SYNC, RESUME).forEach { findPreference<Preference>(it)?.isEnabled = false }
            return
        }
        findPreference<SwitchPreferenceCompat>(ENABLED)?.isChecked = state.first.enabled
        findPreference<SwitchPreferenceCompat>(AUTOMATIC)?.isChecked = state.first.automatic
        findPreference<MultiSelectListPreference>(CONTENT)?.values = state.first.namespaces
        findPreference<Preference>(SYNC)?.isEnabled = state.first.enabled
        findPreference<Preference>(RESUME)?.isEnabled = state.first.enabled
        findPreference<Preference>(ENABLED)?.summary = state.first.accountLabel
        val progress = repository.progress.value.takeIf { it.status == ExtendedDriveStatus.RUNNING } ?: state.second
        findPreference<Preference>(STATUS)?.summary = status(progress)
    }

    private fun status(result: ExtendedDriveResult): String = buildString {
        append(getString(when (result.status) {
            ExtendedDriveStatus.DISABLED -> R.string.disabled
            ExtendedDriveStatus.READY -> R.string.extended_drive_ready
            ExtendedDriveStatus.RUNNING -> R.string.extended_drive_running
            ExtendedDriveStatus.SUCCESS -> R.string.extended_drive_success
            ExtendedDriveStatus.SIGN_IN_REQUIRED -> R.string.extended_drive_signin
            ExtendedDriveStatus.STALE_SESSION -> R.string.extended_drive_stale
            ExtendedDriveStatus.RECOVERY_REQUIRED -> R.string.extended_drive_recovery
            ExtendedDriveStatus.STORAGE_ERROR -> R.string.extended_drive_storage
            ExtendedDriveStatus.NETWORK_ERROR -> R.string.extended_drive_network
            ExtendedDriveStatus.QUOTA -> R.string.extended_drive_quota
            else -> R.string.extended_drive_review
        }))
        result.lanes.filterValues { it != ExtendedDriveStatus.SUCCESS }.forEach { (namespace, reason) ->
            append("\n").append(label(namespace)).append(": ").append(getString(when (reason) {
                ExtendedDriveStatus.RECOVERY_REQUIRED -> R.string.extended_drive_recovery
                ExtendedDriveStatus.NETWORK_ERROR -> R.string.extended_drive_network
                ExtendedDriveStatus.SIGN_IN_REQUIRED -> R.string.extended_drive_signin
                ExtendedDriveStatus.QUOTA -> R.string.extended_drive_quota
                else -> R.string.extended_drive_review
            }))
        }
    }

    private fun label(namespace: String): String = getString(when (namespace) {
        "chapter_reads_v1" -> R.string.extended_drive_chapters
        "tracker_links_v1" -> R.string.extended_drive_links
        "metadata_overrides_v1" -> R.string.extended_drive_metadata
        "remote_libraries_v1" -> R.string.extended_drive_libraries
        else -> R.string.extended_drive_imports
    })
    private suspend fun copyDiagnostics() {
        val text = withContext(Dispatchers.IO) { repository.diagnostics() }
        requireContext().copyToClipboard(getString(R.string.extended_drive_title), text)
    }
    private fun action(block: suspend () -> Unit) {
        viewLifecycleScope.launch {
            try { block(); bind() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.error)
                    .setMessage(R.string.extended_drive_action_failed)
                    .setPositiveButton(R.string.copy) { _, _ -> action { copyDiagnostics() } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }
    private enum class Action { CHECK, SYNC, RESUME }
    companion object {
        private const val CHECK = "extended_drive_check"
        private const val CONTENT = "extended_drive_content"
        private const val ENABLED = "extended_drive_enabled"
        private const val AUTOMATIC = "extended_drive_automatic"
        private const val SYNC = "extended_drive_sync"
        private const val RESUME = "extended_drive_resume"
        private const val STATUS = "extended_drive_status"
        private const val COPY = "extended_drive_copy"
    }
}
