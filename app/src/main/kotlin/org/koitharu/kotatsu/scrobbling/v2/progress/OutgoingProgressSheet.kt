package org.koitharu.kotatsu.scrobbling.v2.progress

import android.app.Dialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.scrobbling.v2.TrackerToolsRepository
import javax.inject.Inject

internal data class OutgoingProgressScreen(
    val accounts: List<OutgoingProgressAccount> = emptyList(),
    val view: OutgoingProgressView? = null,
    val busy: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class OutgoingProgressViewModel @Inject constructor(
    internal val engine: OutgoingProgressEngine,
    internal val tools: TrackerToolsRepository,
    private val coordinator: OutgoingProgressCoordinator,
) : BaseViewModel() {
    internal val state = MutableStateFlow(OutgoingProgressScreen())
    private var operation: Job? = null
    init { viewModelScope.launch { engine.changes.collect { reload() } } }
    private suspend fun reload() = withContext(Dispatchers.IO) {
        try { state.value = state.value.copy(accounts = engine.accounts(), view = engine.view()) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { state.value = state.value.copy(error = error) }
    }
    internal fun run(action: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch(Dispatchers.IO) {
            state.value = state.value.copy(busy = true, error = null)
            try { action(); coordinator.schedule() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state.value = state.value.copy(error = error) }
            finally { state.value = state.value.copy(busy = false); reload() }
        }
    }
    internal fun stop() { operation?.cancel() }
}

/** Rendering is local. Each remote read/write is selected explicitly, or follows account automation consent. */
@AndroidEntryPoint
class OutgoingProgressSheet : DialogFragment() {
    private val model: OutgoingProgressViewModel by viewModels()
    private lateinit var body: LinearLayout
    private var page = 0
    private val provider get() = requireArguments().getString("provider").orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.outgoing_progress_beta)
            .setView(ScrollView(requireContext()).apply { addView(body) }).setNegativeButton(R.string.close, null).create()
        lifecycleScope.launch { model.state.collect { if (isAdded) render(it) } }
        return dialog
    }

    private fun render(state: OutgoingProgressScreen) {
        body.removeAllViews()
        text(getString(R.string.outgoing_progress_notice))
        button(getString(R.string.tracker_tools_verify_account), !state.busy) {
            model.run { model.tools.incomingConnection(provider) }
        }
        val accounts = state.accounts.filter { it.connection.provider == provider }
        for (account in accounts) {
            val connection = account.connection
            text("${connection.provider} · ${account.displayName}")
            val enabled = state.view?.consents?.any { it.automatic && it.connection.sameSession(connection) } == true
            button(getString(if (enabled) R.string.outgoing_progress_disable else R.string.outgoing_progress_enable), !state.busy && account.writable) {
                if (enabled) model.run { model.engine.setAutomatic(connection, false) }
                else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.outgoing_progress_enable)
                    .setMessage(R.string.outgoing_progress_automatic_notice)
                    .setPositiveButton(R.string.enable) { _, _ -> model.run { model.engine.setAutomatic(connection, true) } }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            button(getString(R.string.outgoing_progress_manual), !state.busy && account.writable) { chooseTitle(account) }
        }
        val lanes = state.view?.lanes.orEmpty().filter { it.provider == provider && it.primary != null }
        page = page.coerceIn(0, (lanes.lastIndex / 20).coerceAtLeast(0))
        for (lane in lanes.drop(page * 20).take(20)) {
            val action = requireNotNull(lane.primary)
            val account = accounts.singleOrNull { it.connection.owner() == action.guard.connection.owner() }
            text("${action.guard.snapshot.title} · ${lane.accountId}\n${action.guard.snapshot.source} · ${action.guard.snapshot.branchKey}\n" +
                "${action.fields.number ?: action.fields.entryId.orEmpty()} · ${action.state}")
            action.error?.let { details ->
                text(details)
                button(getString(R.string.copy)) { requireContext().copyToClipboard(getString(R.string.error), details) }
            }
            if (action.uncertain || action.requiresReview) button(getString(R.string.outgoing_progress_check), !state.busy && account != null) {
                model.run { model.engine.check(action.id, requireNotNull(account).connection) }
            }
            if (action.state == OutgoingProgressState.PAUSED && !action.uncertain && !action.requiresReview) {
                button(getString(R.string.retry), !state.busy) { model.run { model.engine.retry(action.id) } }
            }
            if (!action.uncertain && action.state != OutgoingProgressState.SENDING) {
                button(getString(R.string.remove), !state.busy) { model.run { model.engine.discard(action.id) } }
            }
        }
        if (page > 0) button("←", !state.busy) { page--; render(state) }
        if ((page + 1) * 20 < lanes.size) button("→", !state.busy) { page++; render(state) }
        if (state.busy) button(getString(R.string.cancel)) { model.stop() }
        state.error?.let { error ->
            text(error.message.orEmpty())
            button(getString(R.string.synopsis_translation_copy_error)) {
                requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails())
            }
        }
    }

    private fun chooseTitle(account: OutgoingProgressAccount) = model.run {
        val titles = model.tools.incomingLinkedTitles(account.connection.tracker())
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.outgoing_progress_manual)
                .setItems(titles.map { "${it.title} · ${it.source.name}" }.toTypedArray()) { _, index ->
                    fields(account, titles[index].id)
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun fields(account: OutgoingProgressAccount, mangaId: Long) {
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val chapter = EditText(requireContext()).apply { setHint(R.string.outgoing_progress_chapter); layout.addView(this) }
        val volume = EditText(requireContext()).apply { setHint(R.string.outgoing_progress_volume); layout.addView(this) }
        val entry = EditText(requireContext()).apply {
            setHint(R.string.outgoing_progress_entry)
            if (account.supportsEntryIds) layout.addView(this)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.outgoing_progress_manual).setView(layout)
            .setPositiveButton(R.string.outgoing_progress_preview, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val fields = runCatching { OutgoingProgressFields(chapter.text.toString().trim().ifBlank { null },
                volume.text.toString().trim().ifBlank { null }, entry.text.toString().trim().ifBlank { null }) }
                .getOrElse { chapter.error = it.message; return@setOnClickListener }
            model.run {
                val preview = model.engine.preview(account.connection, mangaId, fields)
                withContext(Dispatchers.Main) { if (isAdded) review(preview) else model.engine.cancelPreview(preview.id) }
            }
            dialog.dismiss()
        } }
        dialog.show()
    }

    private fun review(preview: OutgoingProgressPreview) {
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(requireContext()).apply {
            text = "${preview.guard.snapshot.title}\n${preview.guard.snapshot.source} · ${preview.guard.snapshot.editionUrl}\n" +
                "${preview.guard.snapshot.branchKey}\n" + getString(R.string.outgoing_progress_values,
                    preview.remote?.number ?: "?", preview.remote?.volume ?: "?", preview.remote?.entryId ?: "?",
                    preview.fields.number ?: "—", preview.fields.volume ?: "—", preview.fields.entryId ?: "—")
            setTextIsSelectable(true)
        })
        val lower = CheckBox(requireContext()).apply {
            setText(R.string.outgoing_progress_allow_lower)
            if (preview.requiresLowerConsent) layout.addView(this)
        }
        val unknown = CheckBox(requireContext()).apply {
            setText(R.string.outgoing_progress_allow_unknown)
            if (preview.requiresUnknownConsent) layout.addView(this)
        }
        var confirmed = false
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.outgoing_progress_preview).setView(layout)
            .setPositiveButton(R.string.outgoing_progress_confirm) { _, _ ->
                confirmed = true
                val replaceLower = lower.isChecked
                val replaceUnknown = unknown.isChecked
                model.run {
                    try { model.engine.confirm(preview.id, replaceLower, replaceUnknown) }
                    finally { model.engine.cancelPreview(preview.id) }
                }
            }.setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (!confirmed) model.engine.cancelPreview(preview.id) }.show()
    }

    private fun text(value: String) { body.addView(TextView(requireContext()).apply { text = value; setTextIsSelectable(true) }) }
    private fun button(label: String, enabled: Boolean = true, action: () -> Unit) {
        body.addView(MaterialButton(requireContext()).apply { text = label; isEnabled = enabled; setOnClickListener { action() } })
    }
    companion object {
        fun show(manager: FragmentManager, provider: String) {
            val tag = "outgoingProgressBeta:$provider"
            if (!manager.isStateSaved && manager.findFragmentByTag(tag) == null) {
                OutgoingProgressSheet().apply { arguments = Bundle().apply { putString("provider", provider) } }.show(manager, tag)
            }
        }
    }
}
