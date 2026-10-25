package org.koitharu.kotatsu.settings.userdata

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.backups.mihon.MihonImportChoices
import org.koitharu.kotatsu.backups.mihon.MihonImportPreview
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.backups.mihon.MihonBackupManager
import javax.inject.Inject

@HiltViewModel
class BackupsSettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val mihonBackupManager: MihonBackupManager,
    private val savedState: SavedStateHandle,
) : BaseViewModel() {

    val mihonImportCompleted = MutableEventFlow<MihonBackupManager.RestoreReport>()
    val mihonPreview = MutableStateFlow<MihonImportPreview?>(null)
    val mihonChoices = MutableStateFlow<MihonImportChoices?>(null)
    private var mihonPrepareJob: Job? = null
    private var mihonApplyJob: Job? = null

    val isMihonReviewRequested: Boolean get() = savedState.get<String>(MIHON_URI) != null

    init {
        savedState.get<String>(MIHON_URI)?.let { prepareMihonBackup(Uri.parse(it)) }
    }

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

    fun importMihonBackup(uri: Uri) {
        if (mihonApplyJob?.isActive == true) return
        clearMihonChoices()
        savedState[MIHON_SOURCE_IDS] = longArrayOf()
        savedState[MIHON_SOURCE_NAMES] = arrayOf<String>()
        prepareMihonBackup(uri)
    }

    fun chooseMihonSource(id: Long, source: String) {
        if (mihonApplyJob?.isActive == true) return
        val ids = savedState.get<LongArray>(MIHON_SOURCE_IDS) ?: longArrayOf()
        val names = savedState.get<Array<String>>(MIHON_SOURCE_NAMES).orEmpty()
        val token = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val bindings = ids.zip(names).toMap().toMutableMap().apply { put(id, token) }
        savedState[MIHON_SOURCE_IDS] = bindings.keys.toLongArray()
        savedState[MIHON_SOURCE_NAMES] = bindings.values.toTypedArray()
        clearMihonChoices()
        savedState.get<String>(MIHON_URI)?.let { prepareMihonBackup(Uri.parse(it)) }
    }

    fun setMihonChoices(choices: MihonImportChoices) {
        if (mihonApplyJob?.isActive == true) return
        val preview = mihonPreview.value ?: return
        choices.validate(preview)
        mihonChoices.value = choices
        savedState[MIHON_SELECTED] = choices.selectedTitles.toLongArray()
        savedState[MIHON_EXACT] = choices.exactTitles.toLongArray()
        savedState[MIHON_RESUME] = choices.replaceResumeTitles.toLongArray()
        savedState[MIHON_DIGEST] = preview.digest
    }

    fun applyMihonBackup(expected: MihonImportPreview) {
        if (mihonApplyJob?.isActive == true || mihonPreview.value !== expected) return
        val choices = mihonChoices.value ?: return
        mihonApplyJob = launchLoadingJob {
            val report = mihonBackupManager.apply(expected, choices)
            clearMihonReview()
            mihonImportCompleted.call(report)
        }
    }

    fun cancelMihonReview() {
        mihonPrepareJob?.cancel()
        mihonApplyJob?.cancel()
        clearMihonReview()
    }

    fun refreshMihonPreview() {
        if (mihonApplyJob?.isActive == true) return
        clearMihonChoices()
        savedState[MIHON_SOURCE_IDS] = longArrayOf()
        savedState[MIHON_SOURCE_NAMES] = arrayOf<String>()
        savedState.get<String>(MIHON_URI)?.let { prepareMihonBackup(Uri.parse(it)) }
    }

    private fun prepareMihonBackup(uri: Uri) {
        mihonPrepareJob?.cancel()
        savedState[MIHON_URI] = uri.toString()
        mihonPreview.value = null
        val ids = savedState.get<LongArray>(MIHON_SOURCE_IDS) ?: longArrayOf()
        val names = savedState.get<Array<String>>(MIHON_SOURCE_NAMES).orEmpty()
        val bindings = ids.zip(names).toMap()
        mihonPrepareJob = launchLoadingJob {
            val preview = mihonBackupManager.prepare(uri, bindings)
            val validIds = preview.titles.mapTo(HashSet()) { it.mangaId }
            val defaults = preview.defaultChoices()
            val same = savedState.get<String>(MIHON_DIGEST) == preview.digest
            val choices = if (same) MihonImportChoices(
                savedState.get<LongArray>(MIHON_SELECTED)?.toSet()?.intersect(validIds) ?: defaults.selectedTitles,
                savedState.get<LongArray>(MIHON_EXACT)?.toSet()?.intersect(validIds) ?: defaults.exactTitles,
                savedState.get<LongArray>(MIHON_RESUME)?.toSet()?.intersect(validIds).orEmpty(),
            ) else defaults
            mihonPreview.value = preview
            setMihonChoices(choices)
        }
    }

    private fun clearMihonChoices() {
        for (key in listOf(MIHON_SELECTED, MIHON_EXACT, MIHON_RESUME, MIHON_DIGEST)) savedState.remove<Any>(key)
        mihonChoices.value = null
    }

    private fun clearMihonReview() {
        clearMihonChoices()
        for (key in listOf(MIHON_URI, MIHON_SOURCE_IDS, MIHON_SOURCE_NAMES)) savedState.remove<Any>(key)
        mihonPreview.value = null
    }

    private companion object {
        const val MIHON_URI = "mihon.review.uri"
        const val MIHON_SOURCE_IDS = "mihon.review.source.ids"
        const val MIHON_SOURCE_NAMES = "mihon.review.source.names"
        const val MIHON_SELECTED = "mihon.review.selected"
        const val MIHON_EXACT = "mihon.review.exact"
        const val MIHON_RESUME = "mihon.review.resume"
        const val MIHON_DIGEST = "mihon.review.digest"
    }
}
