package org.koitharu.kotatsu.core.archive

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import javax.inject.Inject

/** Explicit local export only; jobs/passwords are not restarted after recreation or process death. */
@AndroidEntryPoint
class EncryptedArchiveExportDialog : DialogFragment() {
    @Inject lateinit var storageManager: LocalStorageManager
    @Inject @LocalStorageChanges lateinit var changes: MutableSharedFlow<LocalManga?>
    private var password: TextInputEditText? = null
    private var confirmation: TextInputEditText? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var job: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isSaveEnabled = false
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(context).apply { setText(R.string.archive_export_summary) })
        }
        fun field(label: Int): TextInputEditText = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            setSingleLine()
        }.also { input -> container.addView(TextInputLayout(context).apply {
            hint = getString(label); isSaveEnabled = false; addView(input)
        }) }
        password = field(R.string.archive_password)
        confirmation = field(R.string.archive_password_confirm)
        container.addView(ProgressBar(context).also { progress = it; it.isVisible = false })
        container.addView(TextView(context).also { status = it; it.isVisible = false; it.isSaveEnabled = false; it.setTextIsSelectable(true) })
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.archive_export_beta)
            .setView(container)
            .setPositiveButton(R.string.archive_export, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create().also { dialog -> dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { export() }
            } }
    }

    private fun export() {
        if (job?.isActive == true) return
        val editable = password?.text ?: return
        val repeated = confirmation?.text ?: return
        val secret = CharArray(editable.length) { editable[it] }
        val matches = repeated.length == secret.size && secret.indices.all { secret[it] == repeated[it] }
        editable.clear(); repeated.clear()
        if (secret.isEmpty() || !matches) {
            secret.fill('\u0000')
            status?.apply { setText(R.string.archive_password_mismatch); isVisible = true }
            return
        }
        val path = requireArguments().getString(ArchiveUnlockDialog.KEY_FILE)
        if (path == null) { secret.fill('\u0000'); return }
        val source = File(path)
        val archives = ArchiveAccess.get(requireContext())
        password?.isEnabled = false; confirmation?.isEnabled = false
        progress?.isVisible = true; status?.apply { setText(R.string.archive_export_in_progress); isVisible = true }
        job = lifecycleScope.launch {
            try {
                val output = storageManager.getDefaultWriteableDir() ?: throw IOException(getString(R.string.archive_export_no_storage))
                val coroutineContext = currentCoroutineContext()
                val published = runInterruptible(Dispatchers.IO) {
                    var suffix = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val extension = if (source.extension.equals("epub", true)) "epub" else "cbz"
                        val name = source.nameWithoutExtension + "-encrypted" + (if (suffix == 0) "" else "-$suffix") + ".$extension"
                        val target = File(output, name)
                        if (target.exists()) { suffix++; continue }
                        try {
                            return@runInterruptible EncryptedArchiveExporter(archives).export(source, target, secret,
                                checkCancelled = { coroutineContext.ensureActive(); checkArchiveInterrupted() })
                        } catch (_: FileAlreadyExistsException) { suffix++ }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                // The copy remains encrypted; indexing stores only its public filename while locked.
                changes.emit(withContext(Dispatchers.IO) { LocalMangaParser(published).getManga(withDetails = false) })
                parentFragmentManager.setFragmentResult(RESULT_EXPORTED, Bundle().apply { putString(ArchiveUnlockDialog.KEY_FILE, published.canonicalPath) })
                dismiss()
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                status?.apply { text = error.message ?: getString(R.string.error_occurred); isVisible = true }
            } finally {
                secret.fill('\u0000')
                password?.isEnabled = true; confirmation?.isEnabled = true; progress?.isVisible = false
            }
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        password?.text?.clear(); confirmation?.text?.clear()
        password = null; confirmation = null; status?.text = ""; status = null; progress = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_EXPORTED = "archive_exported"
        private const val TAG = "archive_export"
        fun show(manager: FragmentManager, file: File) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            EncryptedArchiveExportDialog().apply {
                arguments = Bundle().apply { putString(ArchiveUnlockDialog.KEY_FILE, file.canonicalPath) }
            }.show(manager, TAG)
        }
    }
}
