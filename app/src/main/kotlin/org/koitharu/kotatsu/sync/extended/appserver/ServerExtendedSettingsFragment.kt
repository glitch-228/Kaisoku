package org.koitharu.kotatsu.sync.extended.appserver

import android.os.Bundle
import android.view.View
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
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import javax.inject.Inject

/** Opening this screen reads only local settings. Every connection or upload requires an explicit action. */
@AndroidEntryPoint
class ServerExtendedSettingsFragment : BasePreferenceFragment(R.string.server_extended_title) {
    @Inject lateinit var repository: ServerExtendedSyncRepository
    @Inject lateinit var scheduler: ServerExtendedWorker.Scheduler
    private var available = emptySet<String>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_server_extended, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        findPreference<MultiSelectListPreference>(CONTENT)?.run {
            entries = ServerExtendedNamespaces.all.map { label(it) }.toTypedArray()
            entryValues = ServerExtendedNamespaces.all.toTypedArray()
            setOnPreferenceChangeListener { _, raw ->
                @Suppress("UNCHECKED_CAST") val selected = raw as Set<String>
                action { change { copy(namespaces = selected, enabled = enabled && selected.isNotEmpty()) } }
                false
            }
        }
        bindSwitch(AUTOMATIC) { copy(automatic = it) }
        bindSwitch(STARTUP) { copy(syncOnStart = it) }
        bindSwitch(WIFI) { copy(wifiOnly = it) }
        findPreference<SwitchPreferenceCompat>(CLEARTEXT)?.setOnPreferenceChangeListener { _, raw ->
            val enabled = raw as Boolean
            if (enabled) MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.server_extended_http)
                .setMessage(R.string.server_extended_http_consent)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    action { change { copy(allowCleartext = true) } }
                }.setNegativeButton(android.R.string.cancel, null).show()
            else action { change { copy(allowCleartext = false) } }
            false
        }
        findPreference<SwitchPreferenceCompat>(ENABLED)?.setOnPreferenceChangeListener { _, raw ->
            if (raw == true) {
                action {
                    val settings = withContext(Dispatchers.IO) { repository.storage.store.configuration() }
                    require(available.containsAll(settings.namespaces) && settings.namespaces.isNotEmpty()) {
                        "Check this connection and select supported content first"
                    }
                    repository.enable(settings.namespaces, settings.allowCleartext)
                    scheduler.schedule()
                    available = emptySet()
                }
            } else action { repository.disable(); scheduler.unschedule(); available = emptySet() }
            false
        }
        action { bind() }
        viewLifecycleScope.launch { repository.progress.collect { bind() } }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
        CHECK -> {
            action {
                val settings = withContext(Dispatchers.IO) { repository.storage.store.configuration() }
                available = repository.checkConnection(settings.allowCleartext)
                val binding = checkNotNull(repository.checkedBinding())
                findPreference<Preference>(CHECK)?.summary = getString(R.string.server_extended_identity,
                    binding.accountName, binding.accountId, binding.endpoint) + "\n" +
                    getString(R.string.server_extended_available, available.size, ServerExtendedNamespaces.all.size)
            }
            true
        }
        SYNC -> { action { repository.sync(ServerExtendedRunMode.MANUAL) }; true }
        REVIEW -> {
            action {
                val status = withContext(Dispatchers.IO) { repository.storage.store.lastResult() }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.server_extended_review_action)
                    .setMessage(statusText(status) + "\n\n" + getString(R.string.server_extended_review_help))
                    .setNeutralButton(R.string.copy) { _, _ ->
                        requireContext().copyToClipboard(getString(R.string.server_extended_title), diagnostics(status))
                    }
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            true
        }
        RESUME -> {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.server_extended_resume)
                .setMessage(R.string.server_extended_resume_consent)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    action { repository.sync(ServerExtendedRunMode.RESUME_PENDING) }
                }.setNegativeButton(android.R.string.cancel, null).show()
            true
        }
        else -> super.onPreferenceTreeClick(preference)
    }

    private fun bindSwitch(key: String, update: ServerExtendedConfiguration.(Boolean) -> ServerExtendedConfiguration) {
        findPreference<SwitchPreferenceCompat>(key)?.setOnPreferenceChangeListener { _, raw ->
            action { change { update(raw as Boolean) } }
            false
        }
    }

    private suspend fun change(update: ServerExtendedConfiguration.() -> ServerExtendedConfiguration) {
        withContext(Dispatchers.IO) {
            val store = repository.storage.store
            store.configure(store.configuration().update())
        }
        scheduler.schedule()
    }

    private suspend fun bind() {
        val snapshot = try {
            withContext(Dispatchers.IO) {
                repository.storage.store.configuration() to repository.storage.store.lastResult()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            findPreference<Preference>(STATUS)?.setSummary(R.string.server_extended_storage)
            listOf(ENABLED, SYNC, RESUME, CONTENT, AUTOMATIC, STARTUP, WIFI, CLEARTEXT).forEach {
                findPreference<Preference>(it)?.isEnabled = false
            }
            return
        }
        val (settings, storedStatus) = snapshot
        val status = if (repository.progress.value.status == ServerExtendedStatus.RUNNING) {
            repository.progress.value
        } else storedStatus
        findPreference<SwitchPreferenceCompat>(ENABLED)?.run {
            isChecked = settings.enabled
            summary = settings.binding?.let { getString(R.string.server_extended_identity,
                it.accountName, it.accountId, it.endpoint) }
        }
        findPreference<SwitchPreferenceCompat>(CLEARTEXT)?.isChecked = settings.allowCleartext
        findPreference<SwitchPreferenceCompat>(AUTOMATIC)?.isChecked = settings.automatic
        findPreference<SwitchPreferenceCompat>(STARTUP)?.isChecked = settings.syncOnStart
        findPreference<SwitchPreferenceCompat>(WIFI)?.isChecked = settings.wifiOnly
        findPreference<MultiSelectListPreference>(CONTENT)?.values = settings.namespaces
        findPreference<Preference>(SYNC)?.isEnabled = settings.enabled
        findPreference<Preference>(RESUME)?.isEnabled = settings.enabled
        findPreference<Preference>(STATUS)?.summary = statusText(status)
    }

    private fun statusText(result: ServerExtendedResult): String = buildString {
        append(getString(when (result.status) {
            ServerExtendedStatus.DISABLED -> R.string.disabled
            ServerExtendedStatus.READY -> R.string.server_extended_ready
            ServerExtendedStatus.RUNNING -> R.string.server_extended_running
            ServerExtendedStatus.SUCCESS -> R.string.server_extended_success
            ServerExtendedStatus.UNSUPPORTED -> R.string.server_extended_unsupported
            ServerExtendedStatus.SIGN_IN_REQUIRED -> R.string.server_extended_signin
            ServerExtendedStatus.STALE_SESSION -> R.string.server_extended_stale
            ServerExtendedStatus.RECOVERY_REQUIRED -> R.string.server_extended_recovery
            ServerExtendedStatus.QUOTA -> R.string.server_extended_quota
            ServerExtendedStatus.STORAGE_ERROR -> R.string.server_extended_storage
            ServerExtendedStatus.NETWORK_ERROR -> R.string.server_extended_network
            else -> R.string.server_extended_review
        }))
        result.lanes.filterValues { it != ServerExtendedStatus.SUCCESS }.forEach { (namespace, status) ->
            append("\n").append(label(namespace)).append(": ").append(when (status) {
                ServerExtendedStatus.RECOVERY_REQUIRED -> getString(R.string.server_extended_recovery)
                ServerExtendedStatus.UNSUPPORTED -> getString(R.string.server_extended_unsupported)
                ServerExtendedStatus.SIGN_IN_REQUIRED -> getString(R.string.server_extended_signin)
                ServerExtendedStatus.QUOTA -> getString(R.string.server_extended_quota)
                ServerExtendedStatus.NETWORK_ERROR -> getString(R.string.server_extended_network)
                else -> getString(R.string.server_extended_review)
            })
        }
    }

    private fun label(namespace: String): String = getString(when (namespace) {
        "chapter_reads_v1" -> R.string.server_extended_chapters
        "tracker_links_v1" -> R.string.server_extended_links
        "foreign_import_state_v1" -> R.string.server_extended_imports
        "metadata_overrides_v1" -> R.string.server_extended_metadata
        "remote_libraries_v1" -> R.string.server_extended_libraries
        else -> R.string.unknown
    })

    private fun diagnostics(result: ServerExtendedResult): String = buildString {
        append("Extended server: ").append(result.status.name)
        result.lanes.forEach { (namespace, status) -> append('\n').append(namespace).append(": ").append(status.name) }
        append("\nUnknown uploads require an explicit Resume action.")
    }

    private fun action(block: suspend () -> Unit) {
        viewLifecycleScope.launch {
            try { block(); bind() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.error)
                    .setMessage(R.string.server_extended_action_failed)
                    .setNeutralButton(R.string.copy) { _, _ ->
                        requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails())
                    }
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    companion object {
        private const val ENABLED = "server_extended_enabled"
        private const val CHECK = "server_extended_check"
        private const val CONTENT = "server_extended_content"
        private const val CLEARTEXT = "server_extended_http"
        private const val AUTOMATIC = "server_extended_automatic"
        private const val STARTUP = "server_extended_startup"
        private const val WIFI = "server_extended_wifi"
        private const val SYNC = "server_extended_sync"
        private const val RESUME = "server_extended_resume"
        private const val STATUS = "server_extended_status"
        private const val REVIEW = "server_extended_review_action"
    }
}
