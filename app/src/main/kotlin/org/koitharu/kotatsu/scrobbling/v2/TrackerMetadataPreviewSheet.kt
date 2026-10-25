package org.koitharu.kotatsu.scrobbling.v2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.parseAsHtml
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetTrackerMetadataBinding
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

@HiltViewModel
class TrackerMetadataPreviewViewModel @Inject constructor(
    state: SavedStateHandle,
    private val overrides: TrackerMetadataOverrides,
    private val data: MangaDataRepository,
) : androidx.lifecycle.ViewModel() {
    val preview = MutableStateFlow<TrackerMetadataPreview?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val saved = MutableStateFlow(false)
    val selected = MutableStateFlow(TrackerMetadataSelection(false, false, false))
    private val mangaId = requireNotNull(state.get<Long>("manga"))
    private val provider = requireNotNull(state.get<String>("provider") ?: state.get<String>("service"))
    private var work: Job? = null

    init { refresh() }

    fun select(value: TrackerMetadataSelection) { selected.value = value }

    fun refresh() {
        if (loading.value) return
        val oldPreview = preview.value
        preview.value = null
        selected.value = TrackerMetadataSelection(false, false, false)
        saved.value = false
        work = viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                oldPreview?.let { overrides.discard(it) }
                val manga = requireNotNull(data.findMangaById(mangaId, withChapters = false))
                preview.value = overrides.preview(manga, provider)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.getCopyableErrorDetails() }
            finally { loading.value = false }
        }
    }

    fun apply() {
        val current = preview.value ?: return
        val selection = selected.value
        if (loading.value || !selection.any) return
        work = viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                overrides.apply(current, selection)
                saved.value = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.getCopyableErrorDetails() }
            finally { loading.value = false }
        }
    }

    fun cancel() { work?.cancel() }

    fun discard() {
        cancel()
        preview.value?.let { overrides.discard(it) }
        preview.value = null
        selected.value = TrackerMetadataSelection(false, false, false)
    }

    override fun onCleared() {
        discard()
        super.onCleared()
    }
}

@AndroidEntryPoint
class TrackerMetadataPreviewSheet : BaseAdaptiveSheet<SheetTrackerMetadataBinding>() {
    private val viewModel: TrackerMetadataPreviewViewModel by viewModels()

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
        SheetTrackerMetadataBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: SheetTrackerMetadataBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        // A process restart prepares a fresh review; Android view-state must not reselect old fields.
        binding.checkTitle.isSaveEnabled = false
        binding.checkDescription.isSaveEnabled = false
        binding.checkCover.isSaveEnabled = false
        val selectionChanged = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            viewModel.select(TrackerMetadataSelection(binding.checkTitle.isChecked,
                binding.checkDescription.isChecked, binding.checkCover.isChecked))
        }
        binding.checkTitle.setOnCheckedChangeListener(selectionChanged)
        binding.checkDescription.setOnCheckedChangeListener(selectionChanged)
        binding.checkCover.setOnCheckedChangeListener(selectionChanged)
        binding.buttonApply.setOnClickListener { viewModel.apply() }
        binding.buttonRefresh.setOnClickListener { viewModel.refresh() }
        binding.buttonCancel.setOnClickListener { viewModel.cancel() }
        binding.buttonCopyError.setOnClickListener {
            viewModel.error.value?.let { requireContext().copyToClipboard(getString(R.string.error), it) }
        }
        viewModel.preview.observe(viewLifecycleOwner) { preview ->
            if (preview == null) {
                binding.textTitle.text = null
                binding.textDescription.text = null
                binding.textCover.text = null
                binding.checkTitle.isEnabled = false
                binding.checkDescription.isEnabled = false
                binding.checkCover.isEnabled = false
                return@observe
            }
            binding.textTitle.text = getString(R.string.tracker_metadata_field_preview,
                preview.previous?.title ?: preview.manga.title, preview.title.orEmpty())
            binding.textDescription.text = getString(R.string.tracker_metadata_field_preview,
                (preview.previous?.description ?: preview.manga.description).orEmpty().parseAsHtml(),
                preview.description.orEmpty().parseAsHtml())
            binding.textCover.text = getString(R.string.tracker_metadata_field_preview,
                coverDisplay(preview.previous?.coverUrl ?: preview.manga.coverUrl), coverDisplay(preview.cover))
            binding.checkTitle.isEnabled = preview.title != null
            binding.checkDescription.isEnabled = preview.description != null
            binding.checkCover.isEnabled = preview.cover != null
        }
        viewModel.selected.observe(viewLifecycleOwner) { selected ->
            binding.checkTitle.isChecked = selected.title
            binding.checkDescription.isChecked = selected.description
            binding.checkCover.isChecked = selected.cover
            binding.buttonApply.isEnabled = selected.any && !viewModel.loading.value
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progress.isVisible = loading
            binding.buttonApply.isEnabled = !loading && viewModel.selected.value.any
            binding.buttonCancel.isVisible = loading
            binding.buttonRefresh.isEnabled = !loading
            binding.checkTitle.isEnabled = !loading && viewModel.preview.value?.title != null
            binding.checkDescription.isEnabled = !loading && viewModel.preview.value?.description != null
            binding.checkCover.isEnabled = !loading && viewModel.preview.value?.cover != null
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            binding.textError.text = error?.lineSequence()?.firstOrNull()?.take(240)
            binding.textError.isVisible = error != null
            binding.buttonCopyError.isVisible = error != null
        }
        viewModel.saved.observe(viewLifecycleOwner) { if (it) dismiss() }
    }

    override fun onStart() { super.onStart(); setExpanded(true, false) }

    private fun coverDisplay(url: String?): String = if (url?.startsWith("file:") == true ||
        url?.startsWith("content:") == true) getString(R.string.tracker_metadata_saved_cover)
    else trackerMetadataCoverDisplay(url)

    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (activity?.isChangingConfigurations != true) viewModel.discard()
        super.onDismiss(dialog)
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val mask = WindowInsetsCompat.Type.systemBars()
        v.updatePadding(bottom = insets.getInsets(mask).bottom)
        return insets.consume(v, mask, bottom = true)
    }

    companion object {
        fun show(manager: FragmentManager, mangaId: Long, service: ScrobblerService) {
            show(manager, mangaId, service.name)
        }

        fun show(manager: FragmentManager, mangaId: Long, provider: String) {
            if (manager.isStateSaved || manager.findFragmentByTag("TrackerMetadataPreview") != null) return
            TrackerMetadataPreviewSheet().apply {
                arguments = Bundle().apply { putLong("manga", mangaId); putString("provider", provider) }
            }.showNow(manager, "TrackerMetadataPreview")
        }
    }
}
