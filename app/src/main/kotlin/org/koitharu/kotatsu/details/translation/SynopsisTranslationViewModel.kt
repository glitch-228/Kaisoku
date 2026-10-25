package org.koitharu.kotatsu.details.translation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.translate.TextTranslationConfig
import javax.inject.Inject

@HiltViewModel
class SynopsisTranslationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val settings: AppSettings,
    repository: SynopsisTranslationRepository,
) : ViewModel() {
    private val sensitiveToken: String? = savedState[SynopsisTranslationSheet.ARG_SESSION]
    internal val controller = SynopsisTranslationController(
        original = sensitiveToken?.let(SynopsisTranslationSheet::sensitiveText)
            ?: savedState.get<String>(SynopsisTranslationSheet.ARG_TEXT).orEmpty(),
        allowPersistentCache = sensitiveToken == null,
        configuration = TextTranslationConfig.capture(settings),
        service = repository.service,
        scope = viewModelScope,
    )
    internal val original: String
        get() = controller.original
    internal val unavailable: Boolean
        get() = original.isBlank()

    internal fun refreshConfiguration() = controller.updateConfiguration(TextTranslationConfig.capture(settings))

    internal fun dismiss() {
        controller.cancel()
        controller.clearSensitiveContent()
        sensitiveToken?.let(SynopsisTranslationSheet::forgetSensitiveText)
    }

    override fun onCleared() {
        dismiss()
    }
}
