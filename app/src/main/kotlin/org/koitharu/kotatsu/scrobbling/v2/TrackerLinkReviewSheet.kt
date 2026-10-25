package org.koitharu.kotatsu.scrobbling.v2

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

internal data class RestoredTrackerLink(val link: TrackerLinkV2Entity, val title: String)
internal data class TrackerLinkReviewState(
    val provider: String = "ANILIST",
    val connection: TrackerConnection? = null,
    val links: List<RestoredTrackerLink> = emptyList(),
    val expiresAt: Long = 0,
    val busy: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class TrackerLinkReviewViewModel @Inject constructor(private val tools: TrackerToolsRepository) : BaseViewModel() {
    internal val state = MutableStateFlow(TrackerLinkReviewState())
    private var operation: Job? = null
    private val operations = AtomicLong()

    internal fun select(provider: String) {
        operations.incrementAndGet()
        operation?.cancel()
        state.value = TrackerLinkReviewState(provider = provider)
    }

    internal fun verify() = run {
        val key = state.value.provider
        val connection = tools.incomingConnection(key)
        val pending = tools.restoredLinks(connection).map { RestoredTrackerLink(it,
            tools.localManga(it.mangaId)?.title ?: it.mediaId) }
        currentCoroutineContext().ensureActive()
        check(state.value.provider == key) { "Tracker provider changed; verify it again" }
        tools.checkConnection(connection)
        state.value = state.value.copy(connection = connection, links = pending,
            expiresAt = SystemClock.elapsedRealtime() + 10 * 60_000L)
    }

    internal fun confirm(item: RestoredTrackerLink) = run {
        val current = state.value
        check(SystemClock.elapsedRealtime() < current.expiresAt && item in current.links) {
            "This review expired; verify the account again"
        }
        val connection = requireNotNull(current.connection)
        tools.confirmRestored(connection, item.link)
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(links = state.value.links - item)
    }

    internal fun stop() { operation?.cancel() }

    private fun run(block: suspend () -> Unit) {
        val generation = operations.incrementAndGet()
        operation?.cancel()
        operation = viewModelScope.launch(Dispatchers.IO) {
            state.value = state.value.copy(busy = true, error = null)
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (operations.get() == generation) state.value = state.value.copy(error = error)
            }
            finally { if (operations.get() == generation) state.value = state.value.copy(busy = false) }
        }
    }
}

/** Explicit account verification and a separate per-edition confirmation; opening performs no request. */
@AndroidEntryPoint
class TrackerLinkReviewSheet : DialogFragment() {
    private val model: TrackerLinkReviewViewModel by viewModels()
    private lateinit var body: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * resources.displayMetrics.density).toInt())
        }
        lifecycleScope.launch { model.state.collect { if (isAdded) render(it) } }
        return MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_link_review)
            .setView(ScrollView(requireContext()).apply { addView(body) })
            .setNegativeButton(R.string.close, null).create()
    }

    private fun render(state: TrackerLinkReviewState) {
        body.removeAllViews()
        text(getString(R.string.tracker_link_review_notice))
        button(state.provider, !state.busy) {
            val keys = ScrobblerService.entries.map { it.name } + listOf("COMICK", "ANIME_PLANET")
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_link_review)
                .setItems(keys.toTypedArray()) { _, index -> model.select(keys[index]) }
                .setNegativeButton(R.string.cancel, null).show()
        }
        state.connection?.let { text("${it.owner.provider} · ${it.owner.accountId}") }
        button(getString(R.string.tracker_link_verify), !state.busy) { model.verify() }
        if (state.connection != null) {
            if (state.links.isEmpty()) text(getString(R.string.tracker_link_pending_empty))
            else button(getString(R.string.tracker_link_review), !state.busy) { choose(state) }
        }
        if (state.busy) button(getString(R.string.cancel)) { model.stop() }
        state.error?.let { error ->
            text(error.message.orEmpty())
            button(getString(R.string.copy)) {
                requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails())
            }
        }
    }

    private fun choose(state: TrackerLinkReviewState) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_link_review)
            .setItems(state.links.map { "${it.title} · ${it.link.source}" }.toTypedArray()) { _, index ->
                val item = state.links[index]
                val link = item.link
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_link_review)
                    .setMessage(getString(R.string.tracker_link_review_details, item.title, link.source, link.editionUrl,
                        link.accountId, link.mediaId, link.listEntryId.orEmpty(), link.progressNumber.orEmpty(),
                        link.progressVolume.orEmpty(), link.progressEntryId.orEmpty(), link.progressUnit.orEmpty()))
                    .setPositiveButton(R.string.tracker_link_confirm) { _, _ -> model.confirm(item) }
                    .setNegativeButton(R.string.cancel, null).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun text(value: String) {
        body.addView(TextView(requireContext()).apply { text = value; setTextIsSelectable(true) })
    }

    private fun button(title: String, enabled: Boolean = true, action: () -> Unit) {
        body.addView(MaterialButton(requireContext()).apply {
            text = title; isEnabled = enabled; setOnClickListener { action() }
        })
    }

    companion object {
        fun show(manager: FragmentManager) {
            if (!manager.isStateSaved && manager.findFragmentByTag("trackerLinkReview") == null) {
                TrackerLinkReviewSheet().show(manager, "trackerLinkReview")
            }
        }
    }
}
