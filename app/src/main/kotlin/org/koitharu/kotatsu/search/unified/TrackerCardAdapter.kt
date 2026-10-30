package org.koitharu.kotatsu.search.unified

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemAnilistLibraryEntryBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

data class TrackerSectionHeading(val text: String, val key: String) : ListModel {
    override fun areItemsTheSame(other: ListModel) = other is TrackerSectionHeading && key == other.key
}

class TrackerCardAdapter(
    private val sizeResolver: ItemSizeResolver,
    private val onClick: (TrackerSearchCard) -> Unit,
) : ListAdapter<ListModel, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<ListModel>() {
    override fun areItemsTheSame(a: ListModel, b: ListModel) = a.areItemsTheSame(b)
    override fun areContentsTheSame(a: ListModel, b: ListModel) = a == b
}) {
    override fun getItemViewType(position: Int) = if (getItem(position) is TrackerSectionHeading) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = if (viewType == 1) {
        Heading(TextView(parent.context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            val padding = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
            setPadding(padding, padding * 2, padding, padding)
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
    } else Holder(ItemAnilistLibraryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also {
        sizeResolver.attachToView(it.itemView, it.binding.textTitle, null)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is Heading) (holder.itemView as TextView).text = (item as TrackerSectionHeading).text
        else (holder as Holder).bind(item as TrackerSearchCard)
    }

    private class Heading(view: TextView) : RecyclerView.ViewHolder(view)
    private inner class Holder(val binding: ItemAnilistLibraryEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TrackerSearchCard) {
            binding.imageCover.setImageAsync(item.work.cover, null)
            binding.textTitle.text = item.work.title
            binding.textProgress.text = item.saved?.let { saved -> buildString {
                append(if (saved.progressUnit == "chapter") binding.root.context.getString(R.string.tracker_remote_progress,
                    saved.progress ?: "—", item.work.chapters?.takeUnless { it == "0" } ?: "—")
                    else binding.root.context.getString(R.string.tracker_remote_progress_unit,
                        saved.progress ?: "—", saved.progressUnit))
                saved.score?.let { append(" · ").append(binding.root.context.getString(R.string.tracker_score_raw, it)) }
            } }.orEmpty()
            binding.textLinkState.setText(if (item.editions.isNotEmpty()) R.string.open_in_library else R.string.search_sources_for_title)
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
