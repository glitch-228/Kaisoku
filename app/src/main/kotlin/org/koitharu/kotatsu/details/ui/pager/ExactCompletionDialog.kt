package org.koitharu.kotatsu.details.ui.pager

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R

/** No network or progress writes while previewing the explicit conversion. */
class ExactCompletionDialog : DialogFragment() {
    private val viewModel by ChaptersPagesViewModel.ActivityVMLazy(this)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val manga = viewModel.getMangaOrNull()
        val expectedId = requireArguments().getLong("manga")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * resources.displayMetrics.density).toInt())
        }
        val explanation = TextView(context).also { body.addView(it) }
        val legacy = CheckBox(context).apply {
            setText(R.string.exact_completion_legacy_proposal)
            isChecked = savedInstanceState?.getBoolean("proposal", false) == true
            body.addView(this)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.exact_completion)
            .setView(body).setPositiveButton(R.string.enable, null).setNegativeButton(R.string.close, null).create()
        dialog.setOnShowListener {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            button.isEnabled = false
            if (manga == null || manga.id != expectedId || manga.chapters.isNullOrEmpty()) {
                explanation.setText(R.string.exact_completion_unavailable)
                legacy.visibility = android.view.View.GONE
                return@setOnShowListener
            }
            lifecycleScope.launch {
                val snapshot = viewModel.exactChapterReads.filterNotNull().first { it.policy == null || it.policy.mangaId == expectedId }
                if (viewModel.getMangaOrNull()?.id != expectedId || !isAdded) return@launch
                val active = snapshot.isActive
                val chapters = manga.chapters.orEmpty().filter { it.branch == viewModel.selectedBranch.value }
                val current = viewModel.readingState.value?.chapterId
                val index = chapters.indexOfFirst { it.id == current }
                val proposed = if (index > 0) chapters.take(index).mapTo(HashSet()) { it.id } else emptySet()
                val readCount = snapshot.readIds(chapters)?.size ?: 0
                explanation.text = if (active) getString(R.string.exact_completion_active, readCount, chapters.size)
                    else getString(R.string.exact_completion_preview, chapters.size, proposed.size)
                legacy.visibility = if (snapshot.policy == null && proposed.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                button.setText(if (active) R.string.disable else R.string.enable)
                button.isEnabled = true
                button.setOnClickListener {
                    if (viewModel.getMangaOrNull()?.id != expectedId) return@setOnClickListener
                    viewModel.setExactCompletionEnabled(!active, if (legacy.isChecked && snapshot.policy == null) proposed else emptySet())
                    dismiss()
                }
            }
        }
        return dialog
    }

    companion object {
        fun show(manager: FragmentManager, mangaId: Long) {
            if (manager.isStateSaved || manager.findFragmentByTag("exactCompletion") != null) return
            ExactCompletionDialog().apply { arguments = Bundle().apply { putLong("manga", mangaId) } }
                .show(manager, "exactCompletion")
        }
    }
}
