package org.koitharu.kotatsu.search.unified

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemButtonFooterBinding
import org.koitharu.kotatsu.databinding.ItemListGroupBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

fun trackerSearchSectionAD(sizes: ItemSizeResolver, onClick: (TrackerSearchCard) -> Unit) =
    adapterDelegateViewBinding<TrackerSearchSection, ListModel, ItemListGroupBinding>(
        { inflater, parent -> ItemListGroupBinding.inflate(inflater, parent, false) },
    ) {
        val adapter = TrackerCardAdapter(sizes, onClick)
        binding.recyclerView.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        binding.recyclerView.adapter = adapter
        binding.buttonMore.visibility = View.GONE
        binding.textViewError.visibility = View.GONE
        bind { binding.textViewTitle.setText(item.title); adapter.submitList(item.cards) }
    }

fun catalogueMoreAD(onClick: () -> Unit) = adapterDelegateViewBinding<CatalogueMore, ListModel, ItemButtonFooterBinding>(
    { inflater, parent -> ItemButtonFooterBinding.inflate(inflater, parent, false) },
) {
    binding.button.setOnClickListener { onClick() }
    bind { binding.button.setText(R.string.tracker_catalogue_more) }
}
