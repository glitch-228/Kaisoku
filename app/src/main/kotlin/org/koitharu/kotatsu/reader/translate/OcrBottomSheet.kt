package org.koitharu.kotatsu.reader.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetOcrBinding
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

@AndroidEntryPoint
class OcrBottomSheet : BaseAdaptiveSheet<SheetOcrBinding>() {

	private val viewModel: ReaderViewModel by activityViewModels()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetOcrBinding =
		SheetOcrBinding.inflate(inflater, container, false)

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.root?.updatePadding(bottom = insets.getInsets(typeMask).bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onViewBindingCreated(binding: SheetOcrBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.buttonRerun.setOnClickListener { viewModel.requestOcrCurrentPage() }
		binding.buttonCopy.setOnClickListener { copyToClipboard() }
		binding.buttonShare.setOnClickListener { shareResult() }
		viewModel.ocrSheetState.observe(viewLifecycleOwner) { renderState(binding, it) }
	}

	private fun renderState(binding: SheetOcrBinding, state: OcrSheetState) {
		when (state) {
			OcrSheetState.Idle, OcrSheetState.Loading -> {
				binding.groupProgress.isVisible = true
				binding.scrollViewResult.isVisible = false
				binding.textViewStatus.setText(R.string.ocr_recognizing)
				toggleResultButtons(binding, visible = false)
			}
			is OcrSheetState.Done -> {
				binding.groupProgress.isGone = true
				binding.scrollViewResult.isVisible = true
				if (state.text.isBlank()) {
					binding.textViewResult.setText(R.string.ocr_no_text)
					toggleResultButtons(binding, visible = false)
				} else {
					binding.textViewResult.text = state.text
					toggleResultButtons(binding, visible = true)
				}
			}
			is OcrSheetState.Failed -> {
				binding.groupProgress.isVisible = true
				binding.scrollViewResult.isVisible = false
				binding.textViewStatus.text = describeError(state.error)
				toggleResultButtons(binding, visible = false)
			}
		}
	}

	private fun describeError(error: Throwable): String = when (error) {
		is TranslateException.NoEndpoint -> getString(R.string.translate_setup_required)
		is TranslateException.NoKey -> getString(R.string.translate_setup_required)
		is TranslateException.Http -> "HTTP ${error.code}: ${error.responseBody.take(160)}"
		is TranslateException.Network -> getString(R.string.network_error) + ": ${error.message.orEmpty()}"
		is TranslateException.Parse -> error.message.orEmpty()
		else -> error.localizedMessage ?: getString(R.string.error_occurred)
	}

	private fun toggleResultButtons(binding: SheetOcrBinding, visible: Boolean) {
		binding.buttonCopy.isVisible = visible
		binding.buttonShare.isVisible = visible
	}

	private fun copyToClipboard() {
		val text = currentText() ?: return
		val ctx = context ?: return
		ctx.getSystemService(ClipboardManager::class.java)
			?.setPrimaryClip(ClipData.newPlainText("ocr", text))
	}

	private fun shareResult() {
		val text = currentText() ?: return
		val intent = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_TEXT, text)
		}
		startActivity(Intent.createChooser(intent, getString(R.string.share)))
	}

	private fun currentText(): String? =
		(viewModel.ocrSheetState.value as? OcrSheetState.Done)?.text?.takeIf { it.isNotBlank() }

	companion object {
		const val TAG = "OcrBottomSheet"

		fun show(fm: FragmentManager) {
			OcrBottomSheet().show(fm, TAG)
		}
	}
}
