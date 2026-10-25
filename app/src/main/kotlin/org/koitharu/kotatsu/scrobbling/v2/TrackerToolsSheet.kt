package org.koitharu.kotatsu.scrobbling.v2

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.net.toUri
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
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.v2.bulk.BulkTrackerSheet
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import javax.inject.Inject

internal data class TrackerToolsState(
    val provider: String = "COMICK",
    val connection: TrackerConnection? = null,
    val busy: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class TrackerToolsViewModel @Inject constructor(internal val tools: TrackerToolsRepository) : BaseViewModel() {
    internal val state = MutableStateFlow(TrackerToolsState())
    private var operation: Job? = null
    private var generation = 0L

    internal fun selectProvider(key: String) {
        operation?.cancel()
        generation += 1
        state.value = TrackerToolsState(provider = key)
        run {
            val connection = tools.knownConnection(key)
            currentCoroutineContext().ensureActive()
            if (state.value.provider == key) state.value = state.value.copy(connection = connection)
        }
    }

    internal fun verify() = run {
        val provider = state.value.provider
        val connection = tools.provider(provider).connection()
        currentCoroutineContext().ensureActive()
        if (state.value.provider == provider) state.value = state.value.copy(connection = connection)
    }

    internal fun disconnect() = run {
        val provider = state.value.provider
        tools.disconnect(provider)
        currentCoroutineContext().ensureActive()
        state.value = TrackerToolsState(provider = provider, busy = true)
    }

    internal fun stop() { operation?.cancel() }

    private fun run(block: suspend () -> Unit) {
        operation?.cancel()
        val expectedGeneration = ++generation
        operation = viewModelScope.launch(Dispatchers.IO) {
            state.value = state.value.copy(busy = true, error = null)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == expectedGeneration) state.value = state.value.copy(error = e)
            } finally {
                if (generation == expectedGeneration) state.value = state.value.copy(busy = false)
            }
        }
    }
}

/** Explicit account, edition and per-title confirmation; simply opening this screen sends no request. */
@AndroidEntryPoint
class TrackerToolsSheet : DialogFragment() {
    private val viewModel: TrackerToolsViewModel by viewModels()
    private lateinit var body: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * resources.displayMetrics.density).toInt())
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_tools_beta)
            .setView(ScrollView(requireContext()).apply { addView(body) })
            .setNegativeButton(R.string.close, null).create()
        lifecycleScope.launch { viewModel.state.collect { if (isAdded) render(it) } }
        return dialog
    }

    private fun render(state: TrackerToolsState) {
        body.removeAllViews()
        text(getString(R.string.tracker_tools_beta_notice))
        button("Comick", !state.busy) { viewModel.selectProvider("COMICK") }
        button("Anime-Planet", !state.busy) { viewModel.selectProvider("ANIME_PLANET") }
        org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.entries.forEach { service ->
            button(getString(service.titleResId), !state.busy) { viewModel.selectProvider(service.name) }
        }
        text("${state.provider}: ${state.connection?.owner?.accountId ?: getString(R.string.tracker_tools_not_connected)}")
        if (state.provider == "COMICK") button(getString(R.string.tracker_tools_connect_comick), !state.busy) {
            connectComick()
        } else if (state.provider == "ANIME_PLANET") button(getString(R.string.tracker_tools_website_login), !state.busy) {
            router.openBrowser("https://www.anime-planet.com/login", null, "Anime-Planet")
        }
        button(getString(R.string.tracker_tools_verify_account), !state.busy) { viewModel.verify() }
        if (state.connection != null) {
            button(getString(R.string.tracker_tools_library), !state.busy) { BulkTrackerSheet.show(parentFragmentManager, state.provider) }
            button(getString(R.string.tracker_tools_bulk), !state.busy) { BulkTrackerSheet.show(parentFragmentManager, state.provider) }
            button(getString(R.string.incoming_progress_beta), !state.busy) { chooseIncoming(state.connection) }
            button(getString(R.string.outgoing_progress_beta), !state.busy) {
                org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressSheet.show(parentFragmentManager, state.provider)
            }
            button(getString(R.string.tracker_metadata_beta), !state.busy) { chooseLinkedMetadata(state.connection) }
            button(getString(R.string.tracker_tools_disconnect), !state.busy) { viewModel.disconnect() }
        }
        if (state.busy) button(getString(R.string.cancel)) { viewModel.stop() }
        state.error?.let { error ->
            text(error.message.orEmpty())
            button(getString(R.string.synopsis_translation_copy_error)) {
                requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails())
            }
        }

    }

    private fun connectComick() {
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val client = EditText(requireContext()).apply {
            setHint(R.string.tracker_tools_comick_client)
            setText(viewModel.tools.comickClientId)
            layout.addView(this)
        }
        val write = CheckBox(requireContext()).apply { setText(R.string.tracker_tools_comick_write); layout.addView(this) }
        layout.addView(TextView(requireContext()).apply { text = getString(R.string.tracker_tools_comick_notice, ComickOAuth.CALLBACK) })
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_tools_connect_comick).setView(layout)
            .setPositiveButton(R.string._continue, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val id = client.text.toString().trim()
            if (id.isBlank()) { client.error = getString(R.string.tracker_tools_comick_client); return@setOnClickListener }
            viewModel.tools.comickClientId = id
            startActivity(Intent(Intent.ACTION_VIEW, viewModel.tools.beginComick(write.isChecked).toUri()))
            dialog.dismiss()
        } }
        dialog.show()
    }

    private fun chooseIncoming(connection: TrackerConnection) = lifecycleScope.launch {
        try {
            val titles = withContext(Dispatchers.IO) { viewModel.tools.incomingLinkedTitles(connection) }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.incoming_progress_beta)
                .setItems(titles.map { "${it.title} · ${it.source.name}" }.toTypedArray()) { _, index ->
                    IncomingProgressSheet.show(parentFragmentManager, titles[index].id, connection.owner.provider)
                }.setNegativeButton(R.string.cancel, null).show()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (isAdded) MaterialAlertDialogBuilder(requireContext()).setMessage(error.message)
                .setPositiveButton(R.string.copy) { _, _ -> requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails()) }
                .setNegativeButton(R.string.close, null).show()
        }
    }

    private fun chooseLinkedMetadata(connection: TrackerConnection) = lifecycleScope.launch {
        try {
            val titles = withContext(Dispatchers.IO) { viewModel.tools.incomingLinkedTitles(connection) }
            if (!isAdded || viewModel.state.value.connection != connection) return@launch
            if (titles.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.tracker_tools_no_titles)
                    .setPositiveButton(R.string.close, null).show()
            } else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_metadata_beta)
                .setItems(titles.map { "${it.title} · ${it.source.name}" }.toTypedArray()) { _, index ->
                    TrackerMetadataPreviewSheet.show(parentFragmentManager, titles[index].id, connection.owner.provider)
                }.setNegativeButton(R.string.cancel, null).show()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (isAdded) MaterialAlertDialogBuilder(requireContext()).setMessage(error.message)
                .setPositiveButton(R.string.copy) { _, _ ->
                    requireContext().copyToClipboard(getString(R.string.error), error.getCopyableErrorDetails())
                }.setNegativeButton(R.string.close, null).show()
        }
    }

    private fun text(value: String) { body.addView(TextView(requireContext()).apply { text = value; setTextIsSelectable(true) }) }
    private fun button(title: String, enabled: Boolean = true, action: () -> Unit) {
        body.addView(MaterialButton(requireContext()).apply { text = title; isEnabled = enabled; setOnClickListener { action() } })
    }

    companion object {
        fun show(manager: FragmentManager) {
            if (!manager.isStateSaved && manager.findFragmentByTag("trackerToolsBeta") == null) {
                TrackerToolsSheet().show(manager, "trackerToolsBeta")
            }
        }
    }
}
