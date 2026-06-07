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
) {

	val selectedLabel: String
		get() = options.firstOrNull { it.targetName == selectedTargetName }?.label
			?: options.firstOrNull()?.label.orEmpty()

	data class Option(val targetName: String, val label: String)
}
