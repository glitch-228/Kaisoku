package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.databinding.ItemPopupFilterStateBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.model.MangaState

fun showStateFilterPopupMenu(
	anchor: View,
	currentOption: ListFilterOption.State,
	onSelectState: (MangaState?) -> Unit,
) {
	val context = anchor.context
	val currentState = currentOption.state
	val density = context.resources.displayMetrics.density

	val items = mutableListOf<StateMenuItem>()
	items.add(
		StateMenuItem(
			state = null,
			title = context.getString(R.string.publication_status),
			isSelected = currentState == null,
		),
	)
	for (state in MangaState.entries) {
		items.add(
			StateMenuItem(
				state = state,
				title = context.getString(state.titleResId),
				isSelected = currentState == state,
			),
		)
	}

	val adapter = StateFilterAdapter(context, items)
	val popup = ListPopupWindow(context, null, androidx.appcompat.R.attr.listPopupWindowStyle)
	popup.anchorView = anchor
	popup.setAdapter(adapter)
	popup.isModal = true
	popup.verticalOffset = (8 * density).toInt()
	popup.horizontalOffset = (-16 * density).toInt()
	popup.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_popup_menu_rounded))
	popup.setContentWidth((240 * density).toInt())

	popup.setOnItemClickListener { _, _, position, _ ->
		val selectedItem = items.getOrNull(position)
		onSelectState(selectedItem?.state)
		popup.dismiss()
	}
	popup.show()
}

private data class StateMenuItem(
	val state: MangaState?,
	val title: String,
	val isSelected: Boolean,
)

private class StateFilterAdapter(
	context: Context,
	items: List<StateMenuItem>,
) : ArrayAdapter<StateMenuItem>(context, 0, items) {

	override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
		val binding = if (convertView != null) {
			ItemPopupFilterStateBinding.bind(convertView)
		} else {
			ItemPopupFilterStateBinding.inflate(LayoutInflater.from(context), parent, false)
		}
		val item = getItem(position)
		binding.textViewTitle.text = item?.title
		binding.radioButton.isChecked = item?.isSelected == true
		return binding.root
	}
}
