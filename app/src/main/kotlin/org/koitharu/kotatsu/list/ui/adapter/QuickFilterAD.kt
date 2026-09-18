package org.koitharu.kotatsu.list.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.databinding.ItemQuickFilterBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import java.lang.ref.WeakReference

fun quickFilterAD(
	listener: QuickFilterClickListener,
) = adapterDelegateViewBinding<QuickFilter, ListModel, ItemQuickFilterBinding>(
	{ layoutInflater, parent -> ItemQuickFilterBinding.inflate(layoutInflater, parent, false) }
) {

	binding.chipsTags.onChipClickListener = WeakQuickFilterChipClickListener(listener)

	bind {
		binding.chipsTags.setChips(item.items)
	}
}

private class WeakQuickFilterChipClickListener(
	listener: QuickFilterClickListener,
) : ChipsView.OnChipClickListener {

	private val listenerRef = WeakReference(listener)

	override fun onChipClick(chip: com.google.android.material.chip.Chip, data: Any?) {
		if (data is ListFilterOption) {
			listenerRef.get()?.onFilterOptionClick(chip, data)
		}
	}
}
