package org.koitharu.kotatsu.settings.userdata

import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.backups.mihon.MihonBackupExporter
import org.koitharu.kotatsu.backups.mihon.MihonBackupImporter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import java.io.FileNotFoundException
import javax.inject.Inject

@HiltViewModel
class BackupsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val mihonImporter: MihonBackupImporter,
    private val mihonExporter: MihonBackupExporter,
) : BaseViewModel() {

    val onMihonConverted = MutableEventFlow<Uri>()
    val onMihonExported = MutableEventFlow<Unit>()

    val periodicalBackupFrequency = settings.observeAsFlow(
        key = AppSettings.KEY_BACKUP_PERIODICAL_ENABLED,
        valueProducer = { isPeriodicalBackupEnabled },
    ).flatMapLatest { isEnabled ->
        if (isEnabled) {
            settings.observeAsFlow(
                key = AppSettings.KEY_BACKUP_PERIODICAL_FREQUENCY,
                valueProducer = { periodicalBackupFrequency },
            )
        } else {
            flowOf(0)
        }
    }

    fun importMihon(uri: Uri) {
        launchLoadingJob(Dispatchers.Default) {
            val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
            val tempUri = input.use { mihonImporter.import(it) }
            onMihonConverted.call(tempUri)
        }
    }

    fun exportMihon(uri: Uri) {
        launchLoadingJob(Dispatchers.Default) {
            val output = context.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException()
            output.use { mihonExporter.export(it) }
            onMihonExported.call(Unit)
        }
    }
}
