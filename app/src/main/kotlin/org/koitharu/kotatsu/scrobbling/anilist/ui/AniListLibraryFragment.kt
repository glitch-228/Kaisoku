package org.koitharu.kotatsu.scrobbling.anilist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentAnilistLibraryBinding
import org.koitharu.kotatsu.databinding.ItemAnilistLibraryEntryBinding
import org.koitharu.kotatsu.scrobbling.common.domain.model.AniListLibraryFilter
import org.koitharu.kotatsu.scrobbling.common.domain.model.AniListLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

@AndroidEntryPoint
class AniListLibraryFragment : BaseFragment<FragmentAnilistLibraryBinding>() {

	private val viewModel by viewModels<AniListLibraryViewModel>()
	private val listAdapter = AniListLibraryAdapter(::onEntryClicked)
	private val statusChips = ArrayList<Pair<Chip, Pair<String?, Int>>>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		FragmentAnilistLibraryBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentAnilistLibraryBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
		binding.recyclerView.adapter = listAdapter
		binding.swipeRefresh.setOnRefreshListener(viewModel::refresh)
		binding.buttonLogin.setOnClickListener { router.openScrobblerSettings(ScrobblerService.ANILIST) }
		binding.buttonSort.setOnClickListener {
			viewModel.toggleSort()
		}
		binding.editSearch.addTextChangedListener { text -> viewModel.setSearch(text?.toString().orEmpty()) }
		buildStatusChips(binding)
		viewModel.content.observe(viewLifecycleOwner) { listAdapter.submitList(it) }
		viewModel.entries.observe(viewLifecycleOwner) { entries ->
			statusChips.forEach { (chip, filter) ->
				val count = AniListLibraryFilter.count(entries, filter.first)
				chip.text = getString(R.string.anilist_status_count, getString(filter.second), count)
			}
		}
		viewModel.sortByTitle.observe(viewLifecycleOwner) { alphabetical ->
			binding.buttonSort.tag = alphabetical
			binding.buttonSort.setText(if (alphabetical) R.string.sort_recently_updated else R.string.sort_title)
		}
		viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
			binding.textError.isVisible = !message.isNullOrBlank()
			binding.textError.text = message?.let { getString(R.string.anilist_refresh_error, it) }.orEmpty()
		}
		viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
			binding.buttonLogin.isVisible = !connected
			binding.recyclerView.isVisible = connected
		}
		viewModel.isLoading.observe(viewLifecycleOwner) { binding.swipeRefresh.isRefreshing = it }
		viewModel.refreshIfStale()
	}

	override fun onResume() {
		super.onResume()
		viewModel.refreshIfStale()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	private fun buildStatusChips(binding: FragmentAnilistLibraryBinding) {
		statusChips.clear()
		val statuses = listOf(
			null to R.string.anilist_all,
			"CURRENT" to R.string.status_reading,
			"COMPLETED" to R.string.status_completed,
			"PLANNING" to R.string.status_planned,
			"PAUSED" to R.string.status_on_hold,
			"DROPPED" to R.string.status_dropped,
			"REPEATING" to R.string.status_re_reading,
		)
		statuses.forEach { (value, title) ->
			val chip = Chip(requireContext()).apply {
				id = View.generateViewId()
				text = getString(title)
				isCheckable = true
				isChecked = value == viewModel.selectedStatus.value
				setOnClickListener { viewModel.setStatus(value) }
			}
			statusChips += chip to (value to title)
			binding.chipGroupStatuses.addView(chip)
		}
	}

	private fun onEntryClicked(entry: AniListLibraryEntry) {
		entry.localMangaId?.let(router::openDetails) ?: router.openSearch(entry.title)
	}
}

private class AniListLibraryAdapter(
	private val onClick: (AniListLibraryEntry) -> Unit,
) : RecyclerView.Adapter<AniListLibraryAdapter.Holder>() {

	private var items: List<AniListLibraryEntry> = emptyList()

	fun submitList(value: List<AniListLibraryEntry>) {
		val previous = items
		val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
			override fun getOldListSize() = previous.size
			override fun getNewListSize() = value.size
			override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
				previous[oldItemPosition].mediaId == value[newItemPosition].mediaId
			override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
				previous[oldItemPosition] == value[newItemPosition]
		})
		items = value
		diff.dispatchUpdatesTo(this)
	}

	init { setHasStableIds(true) }
	override fun getItemId(position: Int): Long = items[position].mediaId
	override fun getItemCount(): Int = items.size

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
		val binding = ItemAnilistLibraryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return Holder(binding, onClick)
	}

	override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

	class Holder(
		private val binding: ItemAnilistLibraryEntryBinding,
		private val onClick: (AniListLibraryEntry) -> Unit,
	) : RecyclerView.ViewHolder(binding.root) {
		fun bind(item: AniListLibraryEntry) {
			binding.imageCover.setImageAsync(item.coverUrl.takeIf(String::isNotBlank), null)
			binding.textTitle.text = item.title
			binding.textProgress.text = buildString {
				append(item.progress)
				item.chapters?.let { append(" / ").append(it) }
				append(" • ").append(binding.root.context.getString(R.string.anilist_score, item.score))
			}
			binding.textLinkState.text = binding.root.context.getString(
				if (item.localMangaId != null) R.string.open_in_library else R.string.search_sources_for_title,
			)
			binding.root.setOnClickListener { onClick(item) }
		}
	}
}
