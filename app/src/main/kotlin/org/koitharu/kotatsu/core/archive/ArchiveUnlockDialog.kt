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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.R
import java.io.File

/** Password input is never placed in arguments, instance state, the clipboard or persistent storage. */
class ArchiveUnlockDialog : DialogFragment() {
    private var password: TextInputEditText? = null
    private var progress: ProgressBar? = null
    private var errorText: TextView? = null
    private var job: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val field = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            setSingleLine()
        }
        password = field
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isSaveEnabled = false
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(context).apply { setText(R.string.archive_unlock_summary) })
            addView(TextInputLayout(context).apply {
                hint = getString(R.string.archive_password)
                isSaveEnabled = false
                addView(field)
            })
            addView(ProgressBar(context).also { progress = it; it.isVisible = false })
            addView(TextView(context).also { errorText = it; it.isVisible = false; it.isSaveEnabled = false; it.setTextIsSelectable(true) })
        }
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.archive_unlock_beta)
            .setView(container)
            .setPositiveButton(R.string.archive_unlock, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { unlock() }
                    field.setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) { unlock(); true } else false
                    }
                }
            }
    }

    private fun unlock() {
        if (job?.isActive == true) return
        val field = password ?: return
        val editable = field.text ?: return
        val secret = CharArray(editable.length) { editable[it] }
        editable.clear()
        if (secret.isEmpty()) { errorText?.apply { setText(R.string.archive_password_required); isVisible = true }; return }
        val path = requireArguments().getString(KEY_FILE)
        if (path == null) { secret.fill('\u0000'); return }
        val file = File(path)
        val archives = ArchiveAccess.get(requireContext())
        field.isEnabled = false
        progress?.isVisible = true
        errorText?.isVisible = false
        job = lifecycleScope.launch {
            try {
                runInterruptible(Dispatchers.IO) { archives.unlock(file, secret) }
                parentFragmentManager.setFragmentResult(RESULT_UNLOCKED, Bundle().apply { putString(KEY_FILE, file.canonicalPath) })
                dismiss()
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                errorText?.apply {
                    text = when (error) {
                        is ArchiveAccessException.WrongPassword -> getString(R.string.archive_wrong_password)
                        else -> error.message ?: getString(R.string.error_occurred)
                    }
                    isVisible = true
                }
            } finally {
                secret.fill('\u0000')
                field.isEnabled = true
                progress?.isVisible = false
            }
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        password?.text?.clear()
        password = null
        progress = null
        errorText?.text = ""
        errorText = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_UNLOCKED = "archive_unlocked"
        const val KEY_FILE = "archive_file"
        private const val TAG = "archive_unlock"

        fun show(manager: FragmentManager, file: File) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            ArchiveUnlockDialog().apply { arguments = Bundle().apply { putString(KEY_FILE, file.canonicalPath) } }
                .show(manager, TAG)
        }
    }
}
