package org.koitharu.kotatsu.scrobbling.v2

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.history.exact.decodeReadBranch
import org.koitharu.kotatsu.history.exact.encodeReadBranch
import javax.inject.Inject

internal data class IncomingProgressView(
    val preview: IncomingProgressPreview? = null,
    val selection: IncomingProgressSelection? = null,
    val busy: Boolean = false,
    val applied: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class IncomingProgressViewModel @Inject constructor(
    private val imports: IncomingProgressRepository,
    private val tools: TrackerToolsRepository,
) : BaseViewModel() {
    internal val state = MutableStateFlow(IncomingProgressView())
    private var operation: Job? = null

    internal fun prepare(mangaId: Long, provider: String) = run {
        val connection = tools.incomingConnection(provider)
        val preview = imports.prepare(connection, mangaId)
        state.value = IncomingProgressView(preview, IncomingProgressSelection(preview.branches.first()), busy = true)
    }

    internal fun selection(transform: (IncomingProgressSelection) -> IncomingProgressSelection) {
        if (!state.value.busy) state.value.selection?.let { state.value = state.value.copy(selection = transform(it)) }
    }

    internal fun throughReported() {
        val current = state.value
        val preview = current.preview ?: return
        val selected = current.selection ?: return
        val ids = IncomingProgressPolicy.throughReportedChapter(preview.entry.progress,
            preview.manga.chapters.orEmpty(), selected.branchKey) ?: return
        selection { it.copy(readChapterIds = it.readChapterIds + ids) }
    }

    internal fun apply() = run {
        val current = state.value
        imports.apply(requireNotNull(current.preview).id, requireNotNull(current.selection))
        state.value = current.copy(preview = null, selection = null, applied = true, busy = true, error = null)
    }

    internal fun stop() { operation?.cancel() }
    private fun run(block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch(Dispatchers.IO) {
            state.value = state.value.copy(busy = true, error = null)
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state.value = state.value.copy(error = error) }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
}

/** Opening the dialog is local-only; Verify and preview is an explicit read, Apply is a separate confirmation. */
@AndroidEntryPoint
class IncomingProgressSheet : DialogFragment() {
    private val model: IncomingProgressViewModel by viewModels()
    private lateinit var body: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.incoming_progress_beta)
            .setView(ScrollView(requireContext()).apply { addView(body) }).setNegativeButton(R.string.close, null).create()
        lifecycleScope.launch { model.state.collect { if (isAdded) render(it) } }
        return dialog
    }

    private fun render(state: IncomingProgressView) {
        body.removeAllViews()
        text(getString(R.string.incoming_progress_notice))
        if (state.applied) { text(getString(R.string.incoming_progress_applied)); return }
        val preview = state.preview
        val selected = state.selection
        if (preview == null || selected == null) {
            button(getString(R.string.incoming_progress_preview), !state.busy) {
                model.prepare(requireArguments().getLong("manga"), requireNotNull(requireArguments().getString("provider")))
            }
        } else {
            text("${preview.manga.title}\n${preview.manga.source.name}\n${preview.manga.url}\n" +
                "${preview.connection.owner.provider} · ${preview.connection.owner.accountId}\n" +
                "${preview.entry.reference.mediaId} · ${preview.entry.reference.listEntryId.orEmpty()}")
            text(getString(R.string.incoming_progress_raw, preview.entry.progress?.number ?: "?",
                preview.entry.progress?.volume ?: "?", preview.entry.progress?.unit.orEmpty()))
            button(getString(R.string.incoming_progress_branch, decodeReadBranch(selected.branchKey).orEmpty()), !state.busy) {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.incoming_progress_choose_branch)
                    .setItems(preview.branches.map { decodeReadBranch(it) ?: getString(R.string.incoming_progress_default_branch) }.toTypedArray()) { _, index ->
                        model.selection { it.copy(branchKey = preview.branches[index], readChapterIds = emptySet(), resumeChapterId = null) }
                    }.show()
            }
            text(getString(R.string.incoming_progress_selected, selected.readChapterIds.size))
            button(getString(R.string.incoming_progress_choose_chapters), !state.busy) { chooseChapters(preview, selected) }
            val through = IncomingProgressPolicy.throughReportedChapter(preview.entry.progress,
                preview.manga.chapters.orEmpty(), selected.branchKey)
            button(getString(R.string.incoming_progress_through), !state.busy && through != null) {
                MaterialAlertDialogBuilder(requireContext()).setMessage(getString(R.string.incoming_progress_through_confirm, through?.size ?: 0))
                    .setPositiveButton(R.string.apply) { _, _ -> model.throughReported() }.setNegativeButton(R.string.cancel, null).show()
            }
            if (!preview.completion.isActive) {
                body.addView(CheckBox(requireContext()).apply {
                    setText(R.string.incoming_progress_exact_consent); isChecked = selected.enableExactCompletion; isEnabled = !state.busy
                    setOnCheckedChangeListener { _, checked -> model.selection { it.copy(enableExactCompletion = checked) } }
                })
            }
            text(getString(R.string.incoming_progress_resume_preserved))
            button(getString(R.string.incoming_progress_resume_choose), !state.busy) { chooseResume(preview, selected) }
            if (selected.resumeChapterId != null) {
                text(preview.manga.chapters.orEmpty().single { it.id == selected.resumeChapterId }.title.orEmpty())
                button(getString(R.string.incoming_progress_resume_clear), !state.busy) { model.selection { it.copy(resumeChapterId = null) } }
            }
            button(getString(R.string.apply), !state.busy && (selected.readChapterIds.isNotEmpty() || selected.resumeChapterId != null) &&
                (preview.completion.isActive || selected.enableExactCompletion)) {
                MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.incoming_progress_confirm)
                    .setPositiveButton(R.string.apply) { _, _ -> model.apply() }.setNegativeButton(R.string.cancel, null).show()
            }
            button(getString(R.string.incoming_progress_refresh), !state.busy) {
                model.prepare(preview.manga.id, preview.connection.owner.provider)
            }
        }
        if (state.busy) button(getString(R.string.cancel)) { model.stop() }
        state.error?.let { error ->
            text(error.message.orEmpty())
            button(getString(R.string.copy)) { requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails()) }
        }
    }

    private fun chooseChapters(preview: IncomingProgressPreview, selection: IncomingProgressSelection) {
        val chapters = preview.manga.chapters.orEmpty().filter { encodeReadBranch(it.branch) == selection.branchKey }
        val ids = selection.readChapterIds.toMutableSet()
        val list = ListView(requireContext())
        list.adapter = object : BaseAdapter() {
            override fun getCount() = chapters.size
            override fun getItem(position: Int) = chapters[position]
            override fun getItemId(position: Int) = chapters[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                ((convertView as? CheckedTextView) ?: layoutInflater.inflate(android.R.layout.simple_list_item_multiple_choice,
                    parent, false) as CheckedTextView).apply {
                    text = chapters[position].title; isChecked = chapters[position].id in ids
                }
        }
        list.setOnItemClickListener { _, view, position, _ ->
            val id = chapters[position].id
            if (!ids.add(id)) ids.remove(id)
            (view as CheckedTextView).isChecked = id in ids
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.incoming_progress_choose_chapters).setView(list)
            .setPositiveButton(R.string.apply) { _, _ -> model.selection { it.copy(readChapterIds = ids.toSet()) } }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseResume(preview: IncomingProgressPreview, selection: IncomingProgressSelection) {
        val chapters = preview.manga.chapters.orEmpty().filter { encodeReadBranch(it.branch) == selection.branchKey }
        val list = ListView(requireContext())
        list.adapter = object : BaseAdapter() {
            override fun getCount() = chapters.size
            override fun getItem(position: Int) = chapters[position]
            override fun getItemId(position: Int) = chapters[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                ((convertView as? TextView) ?: layoutInflater.inflate(android.R.layout.simple_list_item_1,
                    parent, false) as TextView).apply { text = chapters[position].title }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.incoming_progress_resume_choose).setView(list)
            .setNegativeButton(R.string.cancel, null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            model.selection { it.copy(resumeChapterId = chapters[position].id) }; dialog.dismiss()
        }
        dialog.show()
    }

    private fun text(value: String) { body.addView(TextView(requireContext()).apply { text = value; setPadding(0, 12, 0, 12) }) }
    private fun button(label: String, enabled: Boolean = true, action: () -> Unit) {
        body.addView(MaterialButton(requireContext()).apply { text = label; isEnabled = enabled; setOnClickListener { action() } })
    }

    companion object {
        fun show(manager: FragmentManager, mangaId: Long, provider: String) {
            if (!manager.isStateSaved && manager.findFragmentByTag("incoming-progress") == null) IncomingProgressSheet().apply {
                arguments = bundleOf("manga" to mangaId, "provider" to provider)
            }.show(manager, "incoming-progress")
        }
    }
}
