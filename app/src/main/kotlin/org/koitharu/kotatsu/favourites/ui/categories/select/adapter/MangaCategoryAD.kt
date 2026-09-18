package org.koitharu.kotatsu.favourites.ui.categories.select.adapter

import android.text.format.DateFormat
import android.widget.Toast
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import com.google.android.material.checkbox.MaterialCheckBox
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.appendIcon
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.databinding.ItemCategoryCheckableBinding
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.util.Date

fun mangaCategoryAD(
	clickListener: OnListItemClickListener<MangaCategoryItem>,
) = adapterDelegateViewBinding<MangaCategoryItem, ListModel, ItemCategoryCheckableBinding>(
	{ inflater, parent -> ItemCategoryCheckableBinding.inflate(inflater, parent, false) },
) {

	itemView.setOnClickListener {
		clickListener.onItemClick(item, itemView)
	}

	itemView.setOnLongClickListener {
		val addedAt = item.addedAt
		if (item.checkedState == MaterialCheckBox.STATE_CHECKED && addedAt != null) {
			val epochMs = addedAt.toEpochMilli()
			val dateStr = DateFormat.getDateFormat(context).format(Date(epochMs))
			val timeStr = DateFormat.getTimeFormat(context).format(Date(epochMs))
			val msg = context.getString(R.string.added_to_category_at, item.category.title, "$dateStr, $timeStr")
			Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
			true
		} else {
			false
		}
	}

	bind { payloads ->
		binding.checkBox.checkedState = item.checkedState
		val dateText = if (item.checkedState == MaterialCheckBox.STATE_CHECKED) {
			item.addedAt?.let { addedAt ->
				calculateTimeAgo(addedAt, showMonths = true)?.format(itemView.context)
			}
		} else {
			null
		}
		binding.textDate.isVisible = !dateText.isNullOrEmpty()
		binding.textDate.text = dateText

		if (ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED !in payloads) {
			binding.checkBox.text = buildSpannedString {
				append(item.category.title)
				if (item.isTrackerEnabled && item.category.isTrackingEnabled) {
					append(' ')
					appendIcon(binding.checkBox, R.drawable.ic_notification)
				}
				if (!item.category.isVisibleInLibrary) {
					append(' ')
					appendIcon(binding.checkBox, R.drawable.ic_eye_off)
				}
			}
			binding.checkBox.jumpDrawablesToCurrentState()
		}
	}
}
