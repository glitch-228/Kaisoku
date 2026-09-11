package org.koitharu.kotatsu.settings.sources.lnreader

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemEmptyHintBinding
import org.koitharu.kotatsu.databinding.ItemSourceCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

sealed interface LnReaderPluginListItem : ListModel {

	data class Extension(
		val descriptor: LnReaderPluginDescriptor,
	) : LnReaderPluginListItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Extension && other.descriptor.plugin.id == descriptor.plugin.id
		}
	}

	data class Hint(
		@DrawableRes val icon: Int,
		@StringRes val title: Int,
		@StringRes val text: Int,
	) : LnReaderPluginListItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Hint && other.title == title
		}
	}
}

class LnReaderPluginsAdapter(
	listener: OnListItemClickListener<LnReaderPluginListItem.Extension>,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.INFO, lnReaderPluginAD(listener))
		addDelegate(ListItemType.HINT_EMPTY, lnReaderPluginHintAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return (items.getOrNull(position) as? LnReaderPluginListItem.Extension)
			?.descriptor
			?.plugin
			?.name
			?.take(1)
	}
}

private fun lnReaderPluginAD(
	listener: OnListItemClickListener<LnReaderPluginListItem.Extension>,
) = adapterDelegateViewBinding<LnReaderPluginListItem.Extension, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	bind {
		val descriptor = item.descriptor
		val plugin = descriptor.plugin
		binding.textViewTitle.text = plugin.name
		binding.textViewDescription.textAndVisible = buildPluginSummary(context, descriptor)
		binding.textViewDescription.drawableStart = when {
			descriptor.hasUpdate -> ContextCompat.getDrawable(context, R.drawable.ic_updated)
			descriptor.isInstalled -> ContextCompat.getDrawable(context, R.drawable.ic_check)
			else -> null
		}
		binding.imageViewIcon.apply {
			if (plugin.iconUrl.isBlank()) {
				setImageAsync(R.drawable.ic_sync)
			} else {
				setImageAsync(plugin.iconUrl)
			}
		}
		val action = descriptor.toAction()
		binding.imageViewAdd.setImageResource(action.icon)
		binding.imageViewAdd.contentDescription = context.getString(action.label)
		binding.imageViewAdd.tooltipText = binding.imageViewAdd.contentDescription
	}
}

private fun lnReaderPluginHintAD() =
	adapterDelegateViewBinding<LnReaderPluginListItem.Hint, ListModel, ItemEmptyHintBinding>(
		{ inflater, parent -> ItemEmptyHintBinding.inflate(inflater, parent, false) },
	) {

		binding.buttonRetry.isVisible = false

		bind {
			binding.icon.setImageAsync(item.icon)
			binding.textPrimary.setText(item.title)
			binding.textSecondary.setTextAndVisible(item.text)
		}
	}

private fun buildPluginSummary(
	context: Context,
	descriptor: LnReaderPluginDescriptor,
): String = buildList {
	descriptor.plugin.lang
		.takeIf { it.isNotBlank() }
		?.let { add(it) }
	add(descriptor.plugin.version)
	when {
		descriptor.hasUpdate -> add(context.getString(R.string.extension_update_available))
		descriptor.isInstalled -> add(context.getString(R.string.extension_installed_privately))
	}
}.joinToString(" • ")

private fun LnReaderPluginDescriptor.toAction(): PluginAction {
	return when {
		hasUpdate -> PluginAction(
			icon = R.drawable.ic_updated,
			label = R.string.update,
		)

		isInstalled -> PluginAction(
			icon = R.drawable.ic_delete,
			label = R.string.remove,
		)

		else -> PluginAction(
			icon = R.drawable.ic_add,
			label = R.string.add,
		)
	}
}

private data class PluginAction(
	@DrawableRes val icon: Int,
	@StringRes val label: Int,
)
