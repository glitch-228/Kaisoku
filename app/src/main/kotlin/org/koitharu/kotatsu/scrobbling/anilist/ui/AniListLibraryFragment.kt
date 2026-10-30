package org.koitharu.kotatsu.scrobbling.anilist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentAnilistLibraryBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SourceScopeKind
import org.koitharu.kotatsu.search.unified.*
import javax.inject.Inject

@AndroidEntryPoint
class AniListLibraryFragment : BaseFragment<FragmentAnilistLibraryBinding>(), TrackerSearchTarget {
    private val viewModel by viewModels<AniListLibraryViewModel>()
    @Inject lateinit var settings: AppSettings
    override val trackerSearchQuery get() = viewModel.filter.value.query
    override val trackerSearchPreview get() = viewModel.content.map { it.take(12) }
    override fun onSearchQueryChanged(query: String) = viewModel.setSearch(query)
    override fun searchRequest(query: String, kind: SearchKind) = viewModel.request(query).copy(kind = kind)
    override fun onTrackerSearchClick(card: TrackerSearchCard) = openCard(card)

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentAnilistLibraryBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: FragmentAnilistLibraryBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        val sizes = DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false)
        val adapter = TrackerCardAdapter(sizes, ::openCard).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        val grid = GridLayoutManager(requireContext(), 1)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.currentList.getOrNull(position) is TrackerSectionHeading)
                grid.spanCount else 1
        }
        binding.recyclerView.layoutManager = grid
        binding.recyclerView.adapter = adapter
        fun columns() {
            grid.spanCount = if (settings.isFixedGridColumnsEnabled) settings.gridColumns else
                ((binding.recyclerView.width - binding.recyclerView.paddingLeft - binding.recyclerView.paddingRight) /
                    sizes.cellWidth.coerceAtLeast(1)).coerceIn(1, 12)
        }
        binding.recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> columns() }
        settings.observe(AppSettings.KEY_GRID_SIZE, AppSettings.KEY_FIXED_GRID_COLUMNS, AppSettings.KEY_GRID_COLUMNS).observe(viewLifecycleOwner) { columns() }
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh)
        binding.buttonLogin.setOnClickListener { router.openScrobblerSettings(viewModel.service.value) }
        binding.textBeta.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_library_beta)
                .setMessage(R.string.tracker_beta_risks).setPositiveButton(android.R.string.ok, null).show()
        }
        binding.chipService.setOnClickListener {
            val services = ScrobblerService.entries
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracking)
                .setSingleChoiceItems(services.map { getString(it.titleResId) }.toTypedArray(), services.indexOf(viewModel.service.value)) { d, i ->
                    viewModel.selectService(services[i]); d.dismiss()
                }.show()
        }
        val statuses = listOf(null to R.string.anilist_all, "CURRENT" to R.string.status_reading,
            "COMPLETED" to R.string.status_completed, "PLANNING" to R.string.status_planned,
            "PAUSED" to R.string.status_on_hold, "DROPPED" to R.string.status_dropped, "REPEATING" to R.string.status_re_reading)
        fun statusLabels() = statuses.map { (status, label) ->
            val count = viewModel.cards.value.count { status == null || it.saved?.status?.let { raw ->
                trackerStatus(viewModel.service.value, raw) } == status }
            getString(R.string.anilist_status_count, getString(label), count)
        }
        binding.chipStatus.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracking)
                .setSingleChoiceItems(statusLabels().toTypedArray(), statuses.indexOfFirst { it.first == viewModel.filter.value.status }) { d, i ->
                    viewModel.updateFilter(viewModel.filter.value.copy(status = statuses[i].first)); d.dismiss()
                }.show()
        }
        binding.chipSource.setOnClickListener {
            showReadingSourceSelector(requireContext(), viewModel.availableSources.value) {
                viewModel.updateFilter(viewModel.filter.value.copy(sources = it))
            }
        }
        binding.chipLink.setOnClickListener {
            val states = listOf("ALL" to R.string.tracker_link_filter_all, "LINKED" to R.string.tracker_link_filter_linked,
                "UNLINKED" to R.string.tracker_link_filter_unlinked)
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_link_filter_all)
                .setSingleChoiceItems(states.map { getString(it.second) }.toTypedArray(), states.indexOfFirst { it.first == viewModel.filter.value.linkState }) { d, i ->
                    viewModel.updateFilter(viewModel.filter.value.copy(linkState = states[i].first)); d.dismiss()
                }.show()
        }
        binding.chipDownloaded.setOnClickListener { viewModel.updateFilter(viewModel.filter.value.copy(downloaded = !viewModel.filter.value.downloaded)) }
        binding.chipSort.setOnClickListener { viewModel.updateFilter(viewModel.filter.value.copy(alphabetical = !viewModel.filter.value.alphabetical)) }
        fun chips() {
            val filter = viewModel.filter.value
            binding.chipService.setText(viewModel.service.value.titleResId)
            binding.chipStatus.text = statusLabels()[statuses.indexOfFirst { it.first == filter.status }.coerceAtLeast(0)]
            binding.chipSource.text = filter.sources.label(requireContext(), viewModel.availableSources.value)
            binding.chipSource.isChecked = filter.sources.kind != SourceScopeKind.ENABLED
            binding.chipDownloaded.isChecked = filter.downloaded
            binding.chipLink.setText(when (filter.linkState) { "LINKED" -> R.string.tracker_link_filter_linked
                "UNLINKED" -> R.string.tracker_link_filter_unlinked; else -> R.string.tracker_link_filter_all })
            binding.chipSort.setText(if (filter.alphabetical) R.string.sort_title else R.string.sort_recently_updated)
        }
        viewModel.filter.observe(viewLifecycleOwner) { chips() }
        viewModel.cards.observe(viewLifecycleOwner) { chips() }
        viewModel.availableSources.observe(viewLifecycleOwner) { chips() }
        viewModel.service.observe(viewLifecycleOwner) {
            chips(); binding.buttonLogin.text = getString(R.string.tracker_connect, getString(it.titleResId))
        }
        viewModel.content.observe(viewLifecycleOwner) { cards ->
            val rows = mutableListOf<ListModel>()
            if (viewModel.filter.value.sources.kind == SourceScopeKind.SINGLE) {
                val linked = cards.filter { it.editions.isNotEmpty() }
                val unknown = cards.filter { it.editions.isEmpty() }
                if (linked.isNotEmpty()) { rows += TrackerSectionHeading(getString(R.string.tracker_source_matches), "matched"); rows += linked }
                if (unknown.isNotEmpty()) { rows += TrackerSectionHeading(getString(R.string.tracker_source_unknown), "unknown"); rows += unknown }
            } else rows += cards
            adapter.submitList(rows)
            binding.textEmpty.isVisible = rows.isEmpty() && viewModel.isConnected.value && !viewModel.isLoading.value
        }
        fun accountLine() {
            binding.textAccount.text = buildString {
                append(viewModel.userName.value.orEmpty())
                if (viewModel.syncMessage.value.first > 0) {
                    append(" · ").append(getString(R.string.tracker_sync_pending, viewModel.syncMessage.value.first))
                }
            }
        }
        viewModel.userName.observe(viewLifecycleOwner) { accountLine() }
        fun warning() { binding.buttonWarning.isVisible = viewModel.error.value != null || viewModel.syncMessage.value.second != null }
        viewModel.error.observe(viewLifecycleOwner) { warning() }
        viewModel.syncMessage.observe(viewLifecycleOwner) { warning(); accountLine() }
        binding.buttonWarning.setOnClickListener {
            showTrackerSearchError(requireContext(), viewModel.error.value ?: IllegalStateException(viewModel.syncMessage.value.second))
        }
        viewModel.isConnected.observe(viewLifecycleOwner) {
            binding.buttonLogin.isVisible = !it; binding.recyclerView.isVisible = it
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { binding.swipeRefresh.isRefreshing = it }
        viewModel.refreshIfStale()
    }

    private fun openCard(card: TrackerSearchCard) {
        showTrackerSearchCard(requireActivity(), card, viewModel.filter.value.sources)
    }
    override fun onResume() { super.onResume(); viewModel.refreshIfStale() }
    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat) = insets
}
