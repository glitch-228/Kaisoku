package org.koitharu.kotatsu.details.translation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetSynopsisTranslationBinding
import org.koitharu.kotatsu.reader.translate.TranslateProvider
import java.util.UUID

@AndroidEntryPoint
class SynopsisTranslationSheet : BaseAdaptiveSheet<SheetSynopsisTranslationBinding>() {

    private val viewModel: SynopsisTranslationViewModel by viewModels()

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetSynopsisTranslationBinding = SheetSynopsisTranslationBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: SheetSynopsisTranslationBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        // Selectable TextView state can otherwise save a selected plaintext passage on recreation.
        binding.textResult.isSaveEnabled = viewModel.controller.allowPersistentCache
        binding.buttonSettings.setOnClickListener {
            viewModel.controller.cancel()
            router.openReaderSettings()
        }
        binding.buttonTranslate.setOnClickListener {
            viewModel.refreshConfiguration()
            viewModel.controller.translate(force = viewModel.controller.state.value.result != null)
        }
        binding.buttonCancel.setOnClickListener { viewModel.controller.cancel() }
        binding.buttonOriginal.setOnClickListener { viewModel.controller.toggleOriginal() }
        binding.buttonCopy.setOnClickListener { currentText()?.let(::copy) }
        binding.buttonShare.setOnClickListener {
            currentText()?.let { text ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share)))
            }
        }
        binding.buttonCopyError.setOnClickListener {
            viewModel.controller.state.value.errorDetails?.let(::copy)
        }
        viewModel.controller.state.observe(viewLifecycleOwner) { render(binding, it) }
    }

    override fun onStart() {
        super.onStart()
        setExpanded(isExpanded = true, isLocked = false)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshConfiguration()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) viewModel.dismiss()
        super.onDismiss(dialog)
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val mask = WindowInsetsCompat.Type.systemBars()
        v.updatePadding(bottom = insets.getInsets(mask).bottom)
        return insets.consume(v, mask, bottom = true)
    }

    private fun render(binding: SheetSynopsisTranslationBinding, state: SynopsisTranslationState) {
        val config = state.configuration
        val provider = when (config.provider) {
            TranslateProvider.GOOGLE_LENS -> getString(R.string.synopsis_translation_google)
            TranslateProvider.GEMINI -> getString(R.string.translate_provider_gemini)
            TranslateProvider.OPENAI_COMPATIBLE -> getString(R.string.translate_provider_openai)
        }
        binding.textConfiguration.text = getString(
            R.string.synopsis_translation_configuration,
            if (config.model.isBlank()) provider else "$provider (${config.model})",
            config.sourceLanguage.takeUnless { it == "auto" } ?: getString(R.string.synopsis_translation_auto_language),
            config.targetLanguage,
        )
        binding.progress.isVisible = state.loading
        binding.textStatus.isVisible = state.loading || state.cached
        binding.textStatus.text = when {
            state.loading && state.totalParts > 0 -> getString(
                R.string.synopsis_translation_progress, state.completedParts, state.totalParts,
            )
            state.loading -> getString(R.string.synopsis_translation_loading)
            state.cached -> getString(R.string.synopsis_translation_cached)
            else -> ""
        }
        val error = when {
            viewModel.unavailable -> getString(R.string.synopsis_translation_unavailable)
            state.rejection == SynopsisTranslationRejected.Reason.INPUT_TOO_LONG ->
                getString(R.string.synopsis_translation_too_long)
            state.rejection == SynopsisTranslationRejected.Reason.EMPTY_RESPONSE ->
                getString(R.string.synopsis_translation_empty_result)
            state.rejection == SynopsisTranslationRejected.Reason.RESPONSE_TOO_LONG ->
                getString(R.string.synopsis_translation_result_too_long)
            else -> state.errorSummary
        }
        binding.textError.text = error
        binding.textError.isVisible = !error.isNullOrBlank()
        binding.buttonCopyError.isVisible = state.errorDetails != null
        binding.buttonOriginal.isVisible = state.result != null
        binding.buttonOriginal.setText(
            if (state.showOriginal) R.string.synopsis_translation_result else R.string.synopsis_translation_original,
        )
        val displayed = currentText().orEmpty()
        if (binding.textResult.text.toString() != displayed) binding.textResult.text = displayed
        binding.buttonTranslate.isVisible = !state.loading
        binding.buttonTranslate.isEnabled = !viewModel.unavailable &&
            state.rejection != SynopsisTranslationRejected.Reason.INPUT_TOO_LONG
        binding.buttonTranslate.setText(
            if (state.result == null) R.string.synopsis_translation_translate else R.string.synopsis_translation_again,
        )
        binding.buttonSettings.isEnabled = !state.loading
        binding.buttonCancel.isVisible = state.loading
        binding.groupResultActions.isVisible = state.result != null
    }

    private fun currentText(): String? {
        val state = viewModel.controller.state.value
        return if (state.showOriginal || state.result == null) viewModel.original else state.result
    }

    private fun copy(text: String) {
        val clip = ClipData.newPlainText(getString(R.string.synopsis_translate_beta), text)
        if (!viewModel.controller.allowPersistentCache) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        context?.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    companion object {
        const val TAG = "SynopsisTranslationSheet"
        internal const val ARG_TEXT = "synopsis_text"
        internal const val ARG_SESSION = "synopsis_session"
        private val sensitiveSessions = LinkedHashMap<String, String>()

        fun show(manager: FragmentManager, text: String, allowPersistentCache: Boolean = true) {
            if (text.isBlank() || manager.isStateSaved || manager.isDestroyed || manager.findFragmentByTag(TAG) != null) return
            val args = Bundle()
            if (allowPersistentCache) {
                args.putString(ARG_TEXT, text)
            } else {
                val token = UUID.randomUUID().toString()
                synchronized(sensitiveSessions) {
                    // Sensitive text never enters arguments, SavedStateHandle or disk.
                    if (sensitiveSessions.size >= 4) sensitiveSessions.remove(sensitiveSessions.keys.first())
                    sensitiveSessions[token] = text
                }
                args.putString(ARG_SESSION, token)
            }
            SynopsisTranslationSheet().apply { arguments = args }.showNow(manager, TAG)
        }

        /** Archive/session owners call this before showing content after locking. */
        fun dismissSensitive(manager: FragmentManager) {
            val sheet = manager.findFragmentByTag(TAG) as? SynopsisTranslationSheet
            if (sheet?.arguments?.containsKey(ARG_SESSION) == true) {
                sheet.viewModel.dismiss()
                sheet.viewBinding?.textResult?.text = ""
                sheet.dismissAllowingStateLoss()
            }
            synchronized(sensitiveSessions) { sensitiveSessions.clear() }
        }

        internal fun sensitiveText(token: String): String? = synchronized(sensitiveSessions) { sensitiveSessions[token] }

        internal fun forgetSensitiveText(token: String) {
            synchronized(sensitiveSessions) { sensitiveSessions.remove(token) }
        }
    }
}
