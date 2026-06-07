package org.koitharu.kotatsu.backups.ui.restore

/**
 * One ambiguous backup source shown in the restore "resolve sources" picker: its display title and
 * the list of targets the user can send its titles to. [selectedTargetName] is the currently chosen
 * target (a source name, or the original string for "keep as saved").
 */
data class RestoreRemapItem(
	val source: String,
	val title: String,
	val options: List<Option>,
	val selectedTargetName: String,
	val customTitleCount: Int = 0,
) {

	val selectedLabel: String
		get() = options.firstOrNull { it.targetName == selectedTargetName }?.label
			?: options.firstOrNull()?.label.orEmpty()

	data class Option(val targetName: String, val label: String)
}

/** Data for the per-title resolution dialog of one ambiguous source. */
data class PerTitlePrompt(
	val source: String,
	val sourceTitle: String,
	val options: List<RestoreRemapItem.Option>,
	val titles: List<TitleChoice>,
) {

	data class TitleChoice(
		val url: String,
		val title: String,
		val currentTargetName: String,
	)
}
