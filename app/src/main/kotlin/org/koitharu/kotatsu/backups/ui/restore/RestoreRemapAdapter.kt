package org.koitharu.kotatsu.backups.ui.restore

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.databinding.ItemRestoreRemapBinding

class RestoreRemapAdapter(
	private val onItemClick: (RestoreRemapItem) -> Unit,
) : RecyclerView.Adapter<RestoreRemapAdapter.ViewHolder>() {

	private var items: List<RestoreRemapItem> = emptyList()

	@SuppressLint("NotifyDataSetChanged")
	fun submitList(value: List<RestoreRemapItem>) {
		items = value
		notifyDataSetChanged()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val binding = ItemRestoreRemapBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return ViewHolder(binding)
	}

	override fun getItemCount(): Int = items.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = items[position]
		holder.binding.textViewTitle.text = item.title
		holder.binding.textViewTarget.text = item.selectedLabel
		holder.binding.root.setOnClickListener { onItemClick(item) }
	}

	class ViewHolder(val binding: ItemRestoreRemapBinding) : RecyclerView.ViewHolder(binding.root)
}
