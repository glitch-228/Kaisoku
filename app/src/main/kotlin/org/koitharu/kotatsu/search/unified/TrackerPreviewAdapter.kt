package org.koitharu.kotatsu.search.unified

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemListGroupBinding
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

/** Cached account-list preview above ordinary search suggestions. */
class TrackerPreviewAdapter(private val sizes: ItemSizeResolver, private val onClick: (TrackerSearchCard) -> Unit) :
    RecyclerView.Adapter<TrackerPreviewAdapter.Holder>() {
    private var cards = emptyList<TrackerSearchCard>()
    fun submit(value: List<TrackerSearchCard>) {
        val old = itemCount
        cards = value
        when {
            old == 0 && cards.isNotEmpty() -> notifyItemInserted(0)
            old > 0 && cards.isEmpty() -> notifyItemRemoved(0)
            old > 0 -> notifyItemChanged(0)
        }
    }
    override fun getItemCount() = if (cards.isEmpty()) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemListGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(cards)
    inner class Holder(private val binding: ItemListGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        private val adapter = TrackerCardAdapter(sizes, onClick)
        init {
            binding.recyclerView.layoutManager = LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.buttonMore.visibility = android.view.View.GONE
            binding.textViewError.visibility = android.view.View.GONE
            binding.textViewTitle.setText(R.string.tracker_search_saved)
        }
        fun bind(value: List<TrackerSearchCard>) { adapter.submitList(value) }
    }
}
