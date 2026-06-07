package org.koitharu.kotatsu.settings.userdata

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.domain.BackupUtils
import org.koitharu.kotatsu.backups.ui.backup.BackupService
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch

@AndroidEntryPoint
class BackupsSettingsFragment : BasePreferenceFragment(R.string.backup_restore),
    ActivityResultCallback<Uri?> {

    private val viewModel: BackupsSettingsViewModel by viewModels()

    private val backupSelectCall = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
        this,
    )

    private val backupCreateCall = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            if (!BackupService.start(requireContext(), uri)) {
                Snackbar.make(
                    listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private val mihonExportCall = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportMihon(uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_backups)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindPeriodicalBackupSummary()
        viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(listView, this))
        viewModel.onMihonConverted.observeEvent(viewLifecycleOwner) { router.showBackupRestoreDialog(it) }
        viewModel.onMihonExported.observeEvent(viewLifecycleOwner) {
            Snackbar.make(listView, R.string.export_complete, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            AppSettings.KEY_BACKUP -> {
                if (!backupCreateCall.tryLaunch(BackupUtils.generateFileName(preference.context))) {
                    Snackbar.make(
                        listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
                    ).show()
                }
                true
            }

            AppSettings.KEY_RESTORE, AppSettings.KEY_IMPORT_MIHON -> {
                if (!backupSelectCall.tryLaunch(arrayOf("*/*"))) {
                    Snackbar.make(
                        listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
                    ).show()
                }
                true
            }

            AppSettings.KEY_EXPORT_MIHON -> {
                if (!mihonExportCall.tryLaunch("kaisoku_${System.currentTimeMillis()}.tachibk")) {
                    Snackbar.make(
                        listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
                    ).show()
                }
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

    override fun onActivityResult(result: Uri?) {
        if (result != null) {
            if (isMihonBackup(result)) {
                viewModel.importMihon(result)
            } else {
                router.showBackupRestoreDialog(result)
            }
        }
    }

    // Mihon `.tachibk` backups are gzip (magic bytes 1f 8b); Kaisoku backups are ZIP ("PK").
    private fun isMihonBackup(uri: Uri): Boolean = runCatching {
        requireContext().contentResolver.openInputStream(uri)?.use { stream ->
            stream.read() == 0x1f && stream.read() == 0x8b
        } == true
    }.getOrDefault(false)

    private fun bindPeriodicalBackupSummary() {
        val preference = findPreference<Preference>(AppSettings.KEY_BACKUP_PERIODICAL_ENABLED) ?: return
        val entries = resources.getStringArray(R.array.backup_frequency)
        val entryValues = resources.getStringArray(R.array.values_backup_frequency)
        viewModel.periodicalBackupFrequency.observe(viewLifecycleOwner) { freq ->
            preference.summary = if (freq == 0L) {
                getString(R.string.disabled)
            } else {
                val index = entryValues.indexOf(freq.toString())
                entries.getOrNull(index)
            }
        }
    }
}
