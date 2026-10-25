package org.koitharu.kotatsu.settings.userdata

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.mihon.MihonImportChoices
import org.koitharu.kotatsu.backups.mihon.MihonImportPreview
import org.koitharu.kotatsu.backups.mihon.MihonImportTitlePreview

/** All lists use the native virtualized dialog list; no chapter/source requests occur while reviewing. */
class MihonImportReviewDialog : DialogFragment() {
    private val viewModel: BackupsSettingsViewModel by viewModels({ requireParentFragment() })
    private val children = ArrayList<AlertDialog>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * resources.displayMetrics.density).toInt())
        }
        val explanation = TextView(context).also(body::addView)
        val actions = listOf(
            R.string.mihon_review_titles,
            R.string.mihon_review_chapters,
            R.string.mihon_review_exact,
            R.string.mihon_review_resume,
            R.string.mihon_review_sources,
            R.string.mihon_review_refresh,
        ).map { text -> Button(context).apply { setText(text); body.addView(this) } }
        actions[5].setOnClickListener { viewModel.refreshMihonPreview() }
        val scroll = ScrollView(context).apply { addView(body) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.mihon_review_title).setView(scroll)
            .setPositiveButton(R.string.import_mihon_backup, null)
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.cancelMihonReview() }.create()
        dialog.setOnShowListener {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(viewModel.mihonPreview, viewModel.isLoading) { preview, busy -> preview to busy }
                        .collect { (preview, busy) ->
                            if (!viewModel.isMihonReviewRequested) {
                                dismissAllowingStateLoss()
                                return@collect
                            }
                            val apply = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                            apply.isEnabled = preview != null && !busy && preview.ambiguousSources.isEmpty()
                            actions.forEach { it.isEnabled = preview != null && !busy }
                            actions[5].isEnabled = !busy
                            if (preview == null) {
                                explanation.setText(if (busy) R.string.mihon_review_preparing else R.string.mihon_review_retry)
                                return@collect
                            }
                            fun summary() {
                                val choices = viewModel.mihonChoices.value ?: preview.defaultChoices()
                                explanation.text = getString(R.string.mihon_review_summary, choices.selectedTitles.size,
                                    preview.titles.size, choices.exactTitles.intersect(choices.selectedTitles).size,
                                    choices.replaceResumeTitles.intersect(choices.selectedTitles).size,
                                    preview.missingSources.size) + "\n\n" + getString(R.string.mihon_review_explanation)
                            }
                            summary()
                            actions[0].setOnClickListener {
                                selectTitles(preview, R.string.mihon_review_titles, { it.selectedTitles },
                                    { before, selected -> before.copy(selectedTitles = selected) }, ::summary)
                            }
                            actions[1].setOnClickListener { showTitleDetails(preview) }
                            actions[2].setOnClickListener {
                                selectTitles(preview, R.string.mihon_review_exact, { it.exactTitles },
                                    { before, selected -> before.copy(exactTitles = selected) }, ::summary) { it.chapters.isNotEmpty() }
                            }
                            actions[3].setOnClickListener {
                                selectTitles(preview, R.string.mihon_review_resume, { it.replaceResumeTitles },
                                    { before, selected -> before.copy(replaceResumeTitles = selected) }, ::summary) {
                                    it.hasExistingHistory && it.proposedResume != null
                                }
                            }
                            actions[4].isEnabled = !busy && (preview.ambiguousSources.isNotEmpty() || preview.missingSources.isNotEmpty())
                            actions[4].setOnClickListener { showSources(preview) }
                            apply.setOnClickListener {
                                val choices = viewModel.mihonChoices.value ?: return@setOnClickListener
                                if (choices.selectedTitles.isEmpty()) return@setOnClickListener
                                child(MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                                    .setMessage(getString(R.string.mihon_review_confirm, choices.selectedTitles.size,
                                        choices.exactTitles.intersect(choices.selectedTitles).size,
                                        choices.replaceResumeTitles.intersect(choices.selectedTitles).size))
                                    .setNegativeButton(R.string.cancel, null)
                                    .setPositiveButton(R.string.apply) { _, _ -> viewModel.applyMihonBackup(preview) }.create())
                            }
                        }
                }
            }
        }
        return dialog
    }

    private fun selectTitles(preview: MihonImportPreview, title: Int, selected: (MihonImportChoices) -> Set<Long>,
        update: (MihonImportChoices, Set<Long>) -> MihonImportChoices, done: () -> Unit,
        eligible: (MihonImportTitlePreview) -> Boolean = { true }) {
        val before = viewModel.mihonChoices.value ?: return
        val rows = preview.titles.filter(eligible)
        val labels = rows.map { row -> label(row) }.toTypedArray()
        val checked = rows.map { it.mangaId in selected(before) }.toBooleanArray()
        child(MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, index, value ->
                checked[index] = value
                val current = viewModel.mihonChoices.value ?: return@setMultiChoiceItems
                viewModel.setMihonChoices(update(current, rows.filterIndexed { i, _ -> checked[i] }
                    .mapTo(LinkedHashSet()) { it.mangaId }))
            }.setPositiveButton(R.string.close) { _, _ -> done() }.create())
    }

    private fun showTitleDetails(preview: MihonImportPreview) {
        child(MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mihon_review_chapters)
            .setItems(preview.titles.map { label(it) }.toTypedArray()) { _, index ->
                val row = preview.titles[index]
                val resume = row.proposedResume?.let { getString(R.string.mihon_review_anchor, it.title, it.page + 1L) }
                    ?: getString(R.string.mihon_review_no_anchor)
                child(MaterialAlertDialogBuilder(requireContext()).setTitle(row.title)
                    .setMessage("${row.source}\n${row.editionUrl}\n$resume")
                    .setPositiveButton(R.string.mihon_review_chapter_flags) { _, _ ->
                        child(MaterialAlertDialogBuilder(requireContext()).setTitle(row.title)
                            .setItems(row.chapters.map { chapter ->
                                getString(if (chapter.read) R.string.mihon_review_read else R.string.mihon_review_unread,
                                    chapter.title.ifBlank { chapter.url })
                            }.toTypedArray(), null).setPositiveButton(R.string.close, null).create())
                    }.setNegativeButton(R.string.close, null).create())
            }.setNegativeButton(R.string.close, null).create())
    }

    private fun showSources(preview: MihonImportPreview) {
        if (preview.ambiguousSources.isEmpty()) {
            child(MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mihon_review_sources)
                .setMessage(getString(R.string.mihon_review_missing_sources) + "\n\n" + preview.missingSources.joinToString("\n"))
                .setPositiveButton(R.string.close, null).create())
            return
        }
        val choice = preview.ambiguousSources.first()
        child(MaterialAlertDialogBuilder(requireContext()).setTitle(choice.title)
            .setItems(choice.options.map { "${it.title}\n${it.name}" }.toTypedArray()) { _, index ->
                viewModel.chooseMihonSource(choice.sourceId, choice.options[index].name)
            }.setNegativeButton(R.string.cancel, null).create())
    }

    private fun label(row: MihonImportTitlePreview): String = getString(R.string.mihon_review_row,
        row.title.take(120), row.readCount, row.chapters.size - row.readCount) +
        if (row.alreadyImported) " · ${getString(R.string.mihon_review_accepted)}" else ""

    private fun child(dialog: AlertDialog) {
        children += dialog
        dialog.setOnDismissListener { children.remove(dialog) }
        dialog.show()
    }

    override fun onCancel(dialog: DialogInterface) {
        viewModel.cancelMihonReview()
        super.onCancel(dialog)
    }

    override fun onDestroy() {
        children.toList().forEach { it.dismiss() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "mihonImportReview"
        fun show(manager: FragmentManager) {
            if (!manager.isStateSaved && manager.findFragmentByTag(TAG) == null) MihonImportReviewDialog().show(manager, TAG)
        }
    }
}
