package org.koitharu.kotatsu.scrobbling.v2.bulk

import android.app.Dialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
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
import org.koitharu.kotatsu.scrobbling.v2.TrackerToolsRepository
import java.util.UUID
import javax.inject.Inject

internal data class BulkTrackerScreen(
    val account: BulkTrackerAccount? = null,
    val drafts: List<BulkTrackerDraft> = emptyList(),
    val library: BulkTrackerLibrary? = null,
    val selected: String? = null,
    val canCreate: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class BulkTrackerViewModel @Inject internal constructor(
    private val saved: SavedStateHandle,
    internal val access: BulkTrackerRepositoryAccess,
    internal val engine: BulkTrackerEngine,
    private val tools: TrackerToolsRepository,
) : BaseViewModel() {
    internal val state = MutableStateFlow(BulkTrackerScreen(selected = saved["draft"]))
    private val provider: String = requireNotNull(saved["provider"])
    private var operation: Job? = null

    init {
        run {
            val account = tools.knownConnection(provider)?.let(BulkTrackerAccount::from)
            state.value = state.value.copy(account = account)
            if (account != null) seedSelection(account)
            reload()
        }
        viewModelScope.launch { engine.changes.collect { if (operation?.isActive != true) run { reload() } } }
    }

    internal fun verify() = run {
        state.value = state.value.copy(account = BulkTrackerAccount.from(tools.provider(provider).connection()))
        seedSelection(requireNotNull(state.value.account))
        reload()
    }

    internal fun select(id: String) {
        saved["draft"] = id
        state.value = state.value.copy(selected = id)
    }

    internal fun match(locals: List<BulkTrackerLocal>) = run {
        val draft = engine.match(requireNotNull(state.value.account), locals)
        select(draft.id)
        reload()
        engine.search(draft.id)
        reload()
    }

    internal fun importList(entries: List<BulkTrackerRemote>) = run {
        select(engine.importList(requireNotNull(state.value.account), entries).id)
        reload()
    }

    internal fun loadLibrary(append: Boolean) = run {
        engine.loadLibrary(requireNotNull(state.value.account), append)
        reload()
    }

    internal fun search(id: String, item: String? = null) = run { engine.search(id, item); reload() }
    internal fun apply(id: String) = run { engine.apply(id); reload() }
    internal fun check(id: String, item: String) = run { engine.check(id, item); reload() }
    internal fun rebind(id: String) = run { engine.rebind(id, requireNotNull(state.value.account)); reload() }
    internal fun discard(id: String) = run { engine.discard(id); reload() }
    internal fun choose(draft: String, item: String, candidate: BulkTrackerCandidate, local: BulkTrackerLocal?,
        category: BulkTrackerCategory?, create: Boolean) = run {
        engine.choose(draft, item, candidate, local, category, create)
        reload()
    }

    internal fun validateWebsite(mediaId: String) = run {
        tools.validateAnimePlanetWrites(requireNotNull(state.value.account).connection(), mediaId)
        reload()
    }

    internal fun stop() { operation?.cancel() }

    /** The favorites action may preselect exact local IDs, but opening a sheet never starts a provider search. */
    private suspend fun seedSelection(account: BulkTrackerAccount) {
        val ids = saved.get<LongArray>("selectedMangaIds")?.toList().orEmpty()
        if (ids.isEmpty()) return
        val request = requireNotNull(saved.get<String>("selectionRequest"))
        val selected = ids.map { requireNotNull(access.local(it)) { "A selected local title is unavailable" } }
        select(engine.match(account, selected, request).id)
    }

    private suspend fun reload() {
        val account = state.value.account ?: return
        state.value = state.value.copy(drafts = engine.drafts(account), library = engine.library(account),
            canCreate = engine.canCreate(account))
    }

    private fun run(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch(Dispatchers.IO) {
            state.value = state.value.copy(busy = true, error = null)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                state.value = state.value.copy(error = "This operation stopped. Saved choices and completed items remain available.")
            } finally {
                try { reload() } catch (_: Exception) { /* Keep the prior visible state when the account disconnects. */ }
                state.value = state.value.copy(busy = false)
            }
        }
    }
}

/** Virtualized list choosers; durable drafts own selection, consent and interruption recovery. */
@AndroidEntryPoint
class BulkTrackerSheet : DialogFragment() {
    private val viewModel: BulkTrackerViewModel by viewModels()
    private lateinit var body: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * resources.displayMetrics.density).toInt())
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_bulk_title)
            .setView(ScrollView(requireContext()).apply { addView(body) }).setNegativeButton(R.string.close, null).create()
        lifecycleScope.launch { viewModel.state.collect { if (isAdded) render(it) } }
        return dialog
    }

    private fun render(screen: BulkTrackerScreen) {
        body.removeAllViews()
        text(getString(R.string.tracker_bulk_notice))
        val account = screen.account
        text(account?.let { "${it.provider} · ${it.nickname ?: it.account}" }
            ?: getString(R.string.tracker_tools_not_connected))
        button(getString(R.string.tracker_tools_verify_account), !screen.busy) { viewModel.verify() }
        if (account != null) {
            if (!screen.canCreate) text(getString(R.string.tracker_bulk_read_only))
            button(getString(R.string.tracker_tools_bulk), !screen.busy) { chooseFavorites() }
            button(getString(R.string.tracker_tools_library), !screen.busy) { viewModel.loadLibrary(false) }
            screen.library?.let { library ->
                text(getString(if (library.complete) R.string.tracker_bulk_list_complete else R.string.tracker_bulk_list_partial,
                    library.entries.size))
                library.error?.let(::text)
                if (library.entries.isNotEmpty()) button(getString(R.string.tracker_bulk_import_selected), !screen.busy) {
                    chooseRemote(library)
                }
                if (library.loaded && !library.complete) button(getString(R.string.tracker_tools_next), !screen.busy) {
                    viewModel.loadLibrary(true)
                }
                if (account.provider == "ANIME_PLANET" && !screen.canCreate && library.entries.isNotEmpty()) {
                    button(getString(R.string.tracker_bulk_validate_website), !screen.busy) {
                        chooseOne(getString(R.string.tracker_bulk_validate_website), library.entries,
                            { it.candidate.title }) { entry -> confirm(getString(R.string.tracker_bulk_validate_notice)) {
                            viewModel.validateWebsite(entry.candidate.mediaId)
                        } }
                    }
                }
            }
            if (screen.drafts.isNotEmpty()) button(getString(R.string.tracker_bulk_saved_drafts, screen.drafts.size), !screen.busy) {
                chooseOne(getString(R.string.tracker_bulk_title), screen.drafts,
                    { "${directionLabel(it.direction)} · ${it.items.count { item -> item.state == BulkTrackerItemState.APPLIED }}/${it.items.size}" }) {
                    viewModel.select(it.id)
                }
            }
            screen.drafts.singleOrNull { it.id == screen.selected }?.let { draft ->
                text("${directionLabel(draft.direction)} · ${draft.items.size}")
                draft.error?.let(::text)
                if (draft.retryAt > System.currentTimeMillis()) text(getString(R.string.tracker_bulk_cooldown,
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(draft.retryAt))))
                if (draft.account != account) button(getString(R.string.tracker_bulk_rebind), !screen.busy) {
                    confirm(getString(R.string.tracker_bulk_rebind_notice)) { viewModel.rebind(draft.id) }
                } else {
                    button(getString(R.string.tracker_bulk_review_items), !screen.busy) {
                        chooseOne(getString(R.string.tracker_bulk_review_items), draft.items, { itemLabel(it) }) { item ->
                            reviewItem(draft, item, screen.canCreate)
                        }
                    }
                    if (draft.direction == BulkTrackerDirection.MATCH_FAVORITES) {
                        button(getString(R.string.tracker_bulk_search_unresolved), !screen.busy) { viewModel.search(draft.id) }
                    }
                    if (draft.items.any { it.state in setOf(BulkTrackerItemState.CONFIRMED, BulkTrackerItemState.READY_TO_LINK,
                            BulkTrackerItemState.READBACK_REQUIRED) }) {
                        button(getString(R.string.tracker_tools_apply_selected), !screen.busy) { reviewApply(draft) }
                    }
                }
                if (draft.items.none { it.uncertain }) button(getString(R.string.tracker_bulk_discard), !screen.busy) {
                    confirm(getString(R.string.tracker_bulk_discard_notice)) { viewModel.discard(draft.id) }
                }
            }
        }
        screen.error?.let { error ->
            text(getString(R.string.tracker_bulk_operation_failed))
            button(getString(R.string.copy)) { requireContext().copyToClipboard(getString(R.string.error), error) }
        }
        if (screen.busy) button(getString(R.string.cancel)) { viewModel.stop() }
    }

    private fun chooseFavorites() = safely {
        val favorites = withContext(Dispatchers.IO) { viewModel.access.favorites() }
        if (!isAdded) return@safely
        chooseMany(getString(R.string.tracker_tools_bulk), favorites, { "${it.title} · ${it.source} · ${it.language.orEmpty()}" }) {
            viewModel.match(it)
        }
    }

    private fun chooseRemote(library: BulkTrackerLibrary) {
        chooseMany(getString(R.string.tracker_bulk_import_selected), library.entries, {
            "${it.candidate.title} · ${it.status.orEmpty()} · ${it.chapter.orEmpty()} ${it.unit.orEmpty()}"
        }) { viewModel.importList(it) }
    }

    private fun reviewItem(draft: BulkTrackerDraft, item: BulkTrackerItem, canCreate: Boolean) {
        if (item.uncertain || item.state == BulkTrackerItemState.READY_TO_LINK) {
            confirm("${itemLabel(item)}\n${getString(R.string.tracker_bulk_readback_notice)}") {
                viewModel.check(draft.id, item.id)
            }
        } else if (item.state != BulkTrackerItemState.APPLIED) {
            if (draft.direction == BulkTrackerDirection.IMPORT_LIST) chooseSource(draft, item)
            else if (item.candidates.isEmpty() || item.state == BulkTrackerItemState.RETAINED) {
                confirm("${itemLabel(item)}\n${getString(R.string.tracker_bulk_search_notice)}") {
                    viewModel.search(draft.id, item.id)
                }
            } else {
                MaterialAlertDialogBuilder(requireContext()).setTitle(itemLabel(item))
                    .setItems(arrayOf(getString(R.string.tracker_tools_confirm_match),
                        getString(R.string.tracker_bulk_search_again))) { _, action ->
                        if (action == 1) viewModel.search(draft.id, item.id)
                        else chooseOne(getString(R.string.tracker_tools_confirm_match), item.candidates,
                            { "${it.title} · ${it.format.orEmpty()}\n${it.url}" }) { candidate ->
                            confirmChoice(draft, item, candidate, requireNotNull(item.local), null, canCreate)
                        }
                    }.setNegativeButton(R.string.cancel, null).show()
            }
        } else MaterialAlertDialogBuilder(requireContext()).setMessage(itemLabel(item))
            .setPositiveButton(R.string.close, null).show()
    }

    private fun chooseSource(draft: BulkTrackerDraft, item: BulkTrackerItem) = safely {
        val sources = withContext(Dispatchers.IO) { viewModel.access.sourceNames() }
        if (!isAdded) return@safely
        chooseOne(getString(R.string.tracker_bulk_choose_source), sources, { it }) { source ->
            val query = EditText(requireContext()).apply { setText(item.remote?.candidate?.title.orEmpty()) }
            MaterialAlertDialogBuilder(requireContext()).setTitle(source).setView(query)
                .setPositiveButton(R.string.search) { _, _ -> searchSource(draft, item, source, query.text.toString()) }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun searchSource(draft: BulkTrackerDraft, item: BulkTrackerItem, source: String, query: String) = safely {
        val results = withContext(Dispatchers.IO) { viewModel.access.searchSource(source, query) }
        val categories = withContext(Dispatchers.IO) { viewModel.access.categories() }
        if (!isAdded) return@safely
        if (results.isEmpty() || categories.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext()).setMessage(if (results.isEmpty()) R.string.tracker_bulk_no_results
                else R.string.tracker_bulk_no_categories).setPositiveButton(R.string.close, null).show()
            return@safely
        }
        chooseOne(getString(R.string.tracker_bulk_choose_edition), results, {
            "${it.title} · ${it.format.orEmpty()} · ${it.language.orEmpty()}\n${it.source} · ${it.publicUrl}"
        }) { local -> chooseOne(getString(R.string.tracker_bulk_choose_category), categories, { it.title }) { category ->
            confirmChoice(draft, item, requireNotNull(item.chosen), local, category, false)
        } }
    }

    private fun confirmChoice(draft: BulkTrackerDraft, item: BulkTrackerItem, candidate: BulkTrackerCandidate,
        local: BulkTrackerLocal, category: BulkTrackerCategory?, canCreate: Boolean) {
        val addition = CheckBox(requireContext()).apply {
            setText(R.string.tracker_tools_allow_remote_add)
            isChecked = false
            isEnabled = canCreate
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_tools_confirm_match)
            .setMessage(getString(R.string.tracker_tools_confirm_edition, candidate.title, local.title,
                "${local.source} · ${local.language.orEmpty()} · ${local.format.orEmpty()}\n${local.publicUrl}") +
                category?.let { "\n${it.title}" }.orEmpty() + if (canCreate) "\n${getString(R.string.tracker_bulk_creation_race)}" else "")
            .setView(addition)
            .setPositiveButton(R.string.apply) { _, _ -> viewModel.choose(draft.id, item.id, candidate, local, category,
                addition.isChecked && canCreate) }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun reviewApply(draft: BulkTrackerDraft) {
        val items = draft.items.filter { it.state in setOf(BulkTrackerItemState.CONFIRMED,
            BulkTrackerItemState.READY_TO_LINK, BulkTrackerItemState.READBACK_REQUIRED) }
        val lines = items.map { item -> "${itemLabel(item)}\n${item.category?.title.orEmpty()}" +
            if (item.allowCreate && item.state == BulkTrackerItemState.CONFIRMED)
                "\n${getString(R.string.tracker_bulk_addition)}" else "" }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_bulk_final_review)
            .setItems(lines.toTypedArray(), null).setPositiveButton(R.string.apply) { _, _ ->
                confirm(getString(R.string.tracker_bulk_apply_notice, items.size,
                    items.count { it.allowCreate && it.state == BulkTrackerItemState.CONFIRMED }) +
                    if (items.any { it.allowCreate && it.state == BulkTrackerItemState.CONFIRMED })
                        "\n${getString(R.string.tracker_bulk_creation_race)}" else "") {
                    viewModel.apply(draft.id)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun itemLabel(item: BulkTrackerItem): String = buildString {
        append(item.local?.title ?: item.remote?.candidate?.title.orEmpty())
        item.local?.let { append(" · ${it.source}") }
        item.chosen?.let { append(" → ${it.title}") }
        append(" · ${getString(when (item.state) {
            BulkTrackerItemState.UNRESOLVED -> R.string.tracker_bulk_state_unresolved
            BulkTrackerItemState.SUGGESTED -> R.string.tracker_bulk_state_suggested
            BulkTrackerItemState.RETAINED -> R.string.tracker_bulk_state_retained
            BulkTrackerItemState.CONFIRMED -> R.string.tracker_bulk_state_confirmed
            BulkTrackerItemState.CREATING -> R.string.tracker_bulk_state_creating
            BulkTrackerItemState.READBACK_REQUIRED -> R.string.tracker_bulk_state_readback
            BulkTrackerItemState.READY_TO_LINK -> R.string.tracker_bulk_state_link
            BulkTrackerItemState.APPLIED -> R.string.tracker_bulk_state_applied
            BulkTrackerItemState.FAILED -> R.string.tracker_bulk_state_failed
            BulkTrackerItemState.SKIPPED -> R.string.tracker_bulk_state_skipped
        })}")
        item.error?.let { append("\n$it") }
    }

    private fun <T> chooseOne(title: String, items: List<T>, label: (T) -> String, chosen: (T) -> Unit) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setItems(items.map(label).toTypedArray()) { _, index -> chosen(items[index]) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun <T> chooseMany(title: String, items: List<T>, label: (T) -> String, chosen: (List<T>) -> Unit) {
        val selected = BooleanArray(items.size)
        val selectedIndices = hashSetOf<Int>()
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setMultiChoiceItems(items.map(label).toTypedArray(), selected) { listDialog, index, checked ->
                if (checked && index !in selectedIndices && selectedIndices.size >= 100) {
                    selected[index] = false
                    (listDialog as androidx.appcompat.app.AlertDialog).listView.setItemChecked(index, false)
                } else {
                    selected[index] = checked
                    if (checked) selectedIndices += index else selectedIndices -= index
                }
            }.setPositiveButton(R.string._continue, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val values = items.filterIndexed { index, _ -> selected[index] }
            if (values.isNotEmpty() && values.size <= 100) { chosen(values); dialog.dismiss() }
        } }
        dialog.show()
    }

    private fun directionLabel(direction: BulkTrackerDirection): String = getString(
        if (direction == BulkTrackerDirection.MATCH_FAVORITES) R.string.tracker_bulk_matching else R.string.tracker_bulk_importing,
    )

    private fun confirm(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext()).setMessage(message).setPositiveButton(R.string.apply) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun safely(block: suspend () -> Unit) = lifecycleScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (isAdded) MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.tracker_bulk_operation_failed).setPositiveButton(R.string.close, null).show() }
    }

    private fun text(value: String) { body.addView(TextView(requireContext()).apply { text = value; setTextIsSelectable(true) }) }
    private fun button(title: String, enabled: Boolean = true, action: () -> Unit) {
        body.addView(MaterialButton(requireContext()).apply { text = title; isEnabled = enabled; setOnClickListener { action() } })
    }

    companion object {
        fun show(manager: FragmentManager, provider: String) {
            show(manager, provider, longArrayOf())
        }

        fun show(manager: FragmentManager, provider: String, selectedMangaIds: LongArray) {
            require(selectedMangaIds.size <= 100 && selectedMangaIds.distinct().size == selectedMangaIds.size)
            if (!manager.isStateSaved && manager.findFragmentByTag("durableTrackerBulk") == null) {
                BulkTrackerSheet().apply { arguments = bundleOf("provider" to provider,
                    "selectedMangaIds" to selectedMangaIds, "selectionRequest" to UUID.randomUUID().toString()) }
                    .show(manager, "durableTrackerBulk")
            }
        }
    }
}
