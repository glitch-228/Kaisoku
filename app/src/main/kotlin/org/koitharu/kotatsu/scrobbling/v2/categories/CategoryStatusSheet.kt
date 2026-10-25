package org.koitharu.kotatsu.scrobbling.v2.categories

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
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerToolsRepository
import javax.inject.Inject

internal data class CategoryStatusScreen(
    val view: CategoryStatusView? = null,
    val accounts: List<CategoryStatusAccount> = emptyList(),
    val categories: List<CategoryStatusBinding> = emptyList(),
    val owner: TrackerOwner? = null,
    val enabled: Boolean = false,
    val mappings: List<CategoryStatusMapping> = emptyList(),
    val busy: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class CategoryStatusViewModel @Inject constructor(
    internal val engine: CategoryStatusEngine,
    private val tools: TrackerToolsRepository,
) : BaseViewModel() {
    internal val state = MutableStateFlow(CategoryStatusScreen())
    private var operation: Job? = null

    init {
        viewModelScope.launch { engine.changes.collect { reload() } }
    }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val view = engine.view()
                val old = state.value
                val saved = view.configurations.singleOrNull { it.owner == old.owner && !it.deleted }
                val before = old.view?.configurations?.singleOrNull { it.owner == old.owner && !it.deleted }
                val changed = saved?.revision != before?.revision
                state.value = old.copy(view = view, accounts = engine.accounts(), categories = engine.categories(),
                    enabled = if (changed) old.owner in view.armedOwners else old.enabled,
                    mappings = if (changed) saved?.mappings.orEmpty() else old.mappings)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { state.value = state.value.copy(error = error) }
        }
    }

    internal fun verify(provider: String) = run {
        tools.incomingConnection(provider)
        state.value = state.value.copy(accounts = engine.accounts())
    }

    internal fun select(account: CategoryStatusAccount) {
        if (state.value.busy) return
        val config = state.value.view?.configurations?.singleOrNull { it.owner == account.connection.owner && !it.deleted }
        state.value = state.value.copy(owner = account.connection.owner,
            enabled = account.connection.owner in state.value.view?.armedOwners.orEmpty(), mappings = config?.mappings.orEmpty())
    }

    internal fun enabled(value: Boolean) { if (!state.value.busy) state.value = state.value.copy(enabled = value) }
    internal fun mapping(value: CategoryStatusMapping?) {
        if (!state.value.busy && value != null) state.value = state.value.copy(mappings =
            state.value.mappings.filterNot { it.categoryId == value.categoryId } + value)
    }
    internal fun remove(category: Long) { if (!state.value.busy) state.value = state.value.copy(mappings = state.value.mappings.filterNot { it.categoryId == category }) }
    internal fun run(action: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch(Dispatchers.IO) {
            state.value = state.value.copy(busy = true, error = null)
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state.value = state.value.copy(error = error) }
            finally { state.value = state.value.copy(busy = false); reload() }
        }
    }
    internal fun stop() { operation?.cancel() }
}

/** Account-scoped opt-in and initial selected-only review. Rendering sends no provider requests. */
@AndroidEntryPoint
class CategoryStatusSheet : DialogFragment() {
    private val model: CategoryStatusViewModel by viewModels()
    private lateinit var body: LinearLayout
    private var page = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_categories_beta)
            .setView(ScrollView(requireContext()).apply { addView(body) }).setNegativeButton(R.string.close, null).create()
        lifecycleScope.launch { model.state.collect { if (isAdded) render(it) } }
        return dialog
    }

    private fun render(state: CategoryStatusScreen) {
        body.removeAllViews()
        text(getString(R.string.tracker_categories_notice))
        button(getString(R.string.tracker_categories_verify), !state.busy) {
            val providers = arrayOf("ANILIST", "MAL", "KITSU", "SHIKIMORI", "MANGABAKA", "COMICK", "ANIME_PLANET")
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_categories_verify)
                .setItems(providers) { _, index -> model.verify(providers[index]) }.show()
        }
        for (account in state.accounts) button("${account.connection.owner.provider} · ${account.displayName}", !state.busy) { model.select(account) }
        val account = state.accounts.singleOrNull { it.connection.owner == state.owner }
        if (account != null) {
            text("${account.connection.owner.provider} · ${account.displayName}")
            body.addView(CheckBox(requireContext()).apply {
                setText(R.string.tracker_categories_enable); isChecked = state.enabled; isEnabled = !state.busy
                setOnCheckedChangeListener { _, value -> model.enabled(value) }
            })
            for (mapping in state.mappings) button("${state.categories.singleOrNull(mapping::matches)?.title ?: mapping.categoryId} → ${statusLabel(mapping.status)}", !state.busy) {
                MaterialAlertDialogBuilder(requireContext()).setItems(arrayOf(getString(R.string.remove))) { _, _ -> model.remove(mapping.categoryId) }.show()
            }
            button(getString(R.string.tracker_categories_add), !state.busy) {
                val categories = state.categories.filter { category -> state.mappings.none { it.matches(category) } }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_categories_add)
                    .setItems(categories.map { it.title }.toTypedArray()) { _, index ->
                        val category = categories[index]
                        MaterialAlertDialogBuilder(requireContext()).setTitle(category.title)
                            .setItems(account.statuses.map { statusLabel(it.value) }.toTypedArray()) { _, status ->
                                model.mapping(CategoryStatusMapping(category.categoryId, category.categoryCreatedAt, account.statuses[status].value))
                            }.show()
                    }.show()
            }
            button(getString(R.string.tracker_categories_review), !state.busy) {
                model.run {
                    val preview = model.engine.preview(account.connection, state.enabled, state.mappings)
                    kotlinx.coroutines.withContext(Dispatchers.Main) { if (isAdded) review(preview) else model.engine.cancelPreview(preview.id) }
                }
            }
            val saved = state.view?.configurations?.singleOrNull { it.owner == account.connection.owner }
            if (saved != null) button(getString(R.string.tracker_categories_disable), !state.busy) {
                model.run { model.engine.disable(account.connection.owner) }
            }
        }
        val pending = state.view?.pending.orEmpty()
        if (pending.isNotEmpty()) {
            text(getString(R.string.tracker_categories_pending, pending.size))
            page = page.coerceIn(0, (pending.lastIndex / 20).coerceAtLeast(0))
            for (action in pending.drop(page * 20).take(20)) {
                text("${action.title} · ${action.guard.owner.provider}\n${stateLabel(action.state)}")
                action.error?.let { diagnostic ->
                    text(diagnostic)
                    button(getString(R.string.copy), !state.busy) { requireContext().copyToClipboard(getString(R.string.error), diagnostic) }
                }
                when (action.state) {
                    CategoryStatusActionState.CONFLICT -> button(getString(R.string.tracker_categories_resolve), !state.busy) {
                        val statuses = action.conflicts.keys.toList()
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_categories_resolve)
                            .setItems(statuses.map { status -> statusLabel(status) + ": " + action.conflicts.getValue(status).joinToString { it.title } }.toTypedArray()) { _, index ->
                                model.run { model.engine.resolve(action.id, statuses[index]) }
                            }.show()
                    }
                    CategoryStatusActionState.READBACK_REQUIRED -> button(getString(R.string.tracker_categories_check), !state.busy) {
                        model.run { model.engine.check(action.id) }
                    }
                    CategoryStatusActionState.PAUSED, CategoryStatusActionState.REVIEW_REQUIRED -> button(getString(R.string.retry), !state.busy) {
                        MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.tracker_categories_retry_notice)
                            .setPositiveButton(R.string.retry) { _, _ -> model.run { model.engine.retry(action.id) } }
                            .setNegativeButton(R.string.cancel, null).show()
                    }
                    else -> Unit
                }
                if (action.state != CategoryStatusActionState.SENDING) button(getString(R.string.remove), !state.busy) {
                    model.run { model.engine.discard(action.id) }
                }
            }
            if (page > 0) button(getString(R.string.tracker_categories_previous)) { page--; render(model.state.value) }
            if ((page + 1) * 20 < pending.size) button(getString(R.string.next)) { page++; render(model.state.value) }
        }
        state.error?.let { error ->
            text(error.message.orEmpty())
            button(getString(R.string.copy)) { requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails()) }
        }
        if (state.busy) button(getString(R.string.cancel)) { model.stop() }
    }

    private fun review(preview: CategoryStatusPreview) {
        val selected = linkedSetOf<Long>()
        val list = ListView(requireContext())
        list.adapter = object : BaseAdapter() {
            override fun getCount() = preview.items.size
            override fun getItem(position: Int) = preview.items[position]
            override fun getItemId(position: Int) = preview.items[position].title.mangaId
            override fun isEnabled(position: Int) = getItem(position).let { it.status != null || it.conflicts.isNotEmpty() }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                ((convertView as? CheckedTextView) ?: layoutInflater.inflate(android.R.layout.simple_list_item_multiple_choice,
                    parent, false) as CheckedTextView).apply {
                    val item = getItem(position)
                    text = "${item.title.title} · ${item.title.sourceName}\n" + (item.status?.let(::statusLabel)
                        ?: if (item.conflicts.isNotEmpty()) getString(R.string.tracker_categories_conflict) else getString(R.string.tracker_categories_unmapped))
                    isChecked = item.title.mangaId in selected; isEnabled = isEnabled(position)
                }
        }
        list.setOnItemClickListener { _, view, position, _ ->
            val id = preview.items[position].title.mangaId
            if (!selected.add(id)) selected.remove(id)
            (view as CheckedTextView).isChecked = id in selected
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_categories_review)
            .setMessage(R.string.tracker_categories_review_notice).setView(list)
            .setPositiveButton(R.string.apply) { _, _ -> model.run { model.engine.confirm(preview.id, selected.toSet()) } }
            .setNegativeButton(R.string.cancel) { _, _ -> model.engine.cancelPreview(preview.id) }.create()
        dialog.setOnCancelListener { model.engine.cancelPreview(preview.id) }
        dialog.show()
    }

    private fun statusLabel(value: String): String = getString(when (value.lowercase()) {
        "current", "reading", "watching" -> R.string.status_reading
        "completed" -> R.string.status_completed
        "planning", "planned", "plan_to_read" -> R.string.status_planned
        "repeating", "rereading", "rewatching", "reconsuming" -> R.string.status_re_reading
        "paused", "on_hold" -> R.string.status_on_hold
        "dropped" -> R.string.status_dropped
        else -> R.string.tracker_categories_wont_read
    })
    private fun stateLabel(value: CategoryStatusActionState): String = getString(when (value) {
        CategoryStatusActionState.CONFLICT -> R.string.tracker_categories_conflict
        CategoryStatusActionState.READY -> R.string.tracker_categories_ready
        CategoryStatusActionState.SENDING -> R.string.tracker_categories_sending
        CategoryStatusActionState.READBACK_REQUIRED -> R.string.tracker_categories_check
        else -> R.string.tracker_categories_needs_review
    })
    private fun text(value: String) { body.addView(TextView(requireContext()).apply { text = value; setPadding(0, 12, 0, 12) }) }
    private fun button(label: String, enabled: Boolean = true, action: () -> Unit) {
        body.addView(MaterialButton(requireContext()).apply { text = label; isEnabled = enabled; setOnClickListener { action() } })
    }
    companion object {
        fun show(manager: FragmentManager) {
            if (!manager.isStateSaved && manager.findFragmentByTag("tracker-categories") == null)
                CategoryStatusSheet().show(manager, "tracker-categories")
        }
    }
}
