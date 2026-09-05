package org.koitharu.kotatsu.list.ui.adapter

import android.view.View
import org.koitharu.kotatsu.list.domain.ListFilterOption

interface QuickFilterClickListener {

	fun onFilterOptionClick(view: View, option: ListFilterOption)
}
