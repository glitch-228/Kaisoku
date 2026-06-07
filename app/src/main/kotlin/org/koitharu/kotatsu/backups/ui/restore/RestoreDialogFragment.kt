package org.koitharu.kotatsu.backups.ui.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.domain.SourceRemapPreference
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.AlertDialogFragment
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.DialogRestoreBinding
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

@AndroidEntryPoint
class RestoreDialogFragment : AlertDialogFragment<DialogRestoreBinding>(), OnListItemClickListener<BackupSectionModel>,
	View.OnClickListener {

	private val viewModel: RestoreViewModel by viewModels()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = DialogRestoreBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: DialogRestoreBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BackupSectionsAdapter(this)
		binding.recyclerView.adapter = adapter
		val remapAdapter = RestoreRemapAdapter(::onRemapItemClick)
		binding.recyclerViewRemap.adapter = remapAdapter
		viewModel.sourceRemapItems.observe(viewLifecycleOwner) { remapAdapter.submitList(it) }
		viewModel.perTitlePrompt.observeEvent(viewLifecycleOwner, this::onPerTitlePrompt)
		binding.buttonCancel.setOnClickListener(this)
		binding.buttonRestore.setOnClickListener(this)
		binding.checkboxMerge.setOnCheckedChangeListener { _, isChecked ->
			viewModel.onMergeToggle(isChecked)
		}
		binding.toggleGroupSourcePref.check(
			when (viewModel.sourcePreference.value) {
				SourceRemapPreference.KEEP -> R.id.button_pref_keep
				SourceRemapPreference.BUILT_IN -> R.id.button_pref_builtin
				SourceRemapPreference.EXTENSION -> R.id.button_pref_extension
			},
		)
		binding.toggleGroupSourcePref.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				viewModel.onSourcePreferenceChange(
					when (checkedId) {
						R.id.button_pref_builtin -> SourceRemapPreference.BUILT_IN
						R.id.button_pref_extension -> SourceRemapPreference.EXTENSION
						else -> SourceRemapPreference.KEEP
					},
				)
			}
		}
		viewModel.availableEntries.observe(viewLifecycleOwner, adapter)
		viewModel.onError.observeEvent(viewLifecycleOwner, this::onError)
		combine(
			viewModel.isLoading,
			viewModel.availableEntries,
			viewModel.backupDate,
			viewModel.isMergeEnabled,
			::Quadruple,
		).observe(viewLifecycleOwner, this::onLoadingChanged)
	}

	override fun onBuildDialog(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder {
		return super.onBuildDialog(builder)
			.setTitle(R.string.restore_backup)
			.setCancelable(false)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> dismiss()
			R.id.button_restore -> {
				if (startRestoreService()) {
					Toast.makeText(v.context, R.string.backup_restored_background, Toast.LENGTH_SHORT).show()
					router.closeWelcomeSheet()
					dismiss()
				} else {
					Toast.makeText(v.context, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
				}
			}
		}
	}

	override fun onItemClick(item: BackupSectionModel, view: View) {
		viewModel.onItemClick(item)
	}

	private fun onRemapItemClick(item: RestoreRemapItem) {
		val labels = item.options.map { it.label }.toTypedArray()
		val checked = item.options.indexOfFirst { it.targetName == item.selectedTargetName }.coerceAtLeast(0)
		MaterialAlertDialogBuilder(context ?: return)
			.setTitle(item.title)
			.setSingleChoiceItems(labels, checked) { dialog, which ->
				viewModel.onSourceTargetSelected(item.source, item.options[which].targetName)
				dialog.dismiss()
			}
			.setNeutralButton(R.string.backup_source_per_title) { _, _ ->
				viewModel.openPerTitle(item)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun onPerTitlePrompt(prompt: PerTitlePrompt) {
		if (prompt.titles.isEmpty()) {
			return
		}
		val titleLabels = prompt.titles.map { it.title }.toTypedArray()
		MaterialAlertDialogBuilder(context ?: return)
			.setTitle(prompt.sourceTitle)
			.setItems(titleLabels) { _, which ->
				onPerTitleChoice(prompt, prompt.titles[which])
			}
			.setNegativeButton(R.string.close, null)
			.show()
	}

	private fun onPerTitleChoice(prompt: PerTitlePrompt, choice: PerTitlePrompt.TitleChoice) {
		val labels = prompt.options.map { it.label }.toTypedArray()
		val checked = prompt.options.indexOfFirst { it.targetName == choice.currentTargetName }.coerceAtLeast(0)
		MaterialAlertDialogBuilder(context ?: return)
			.setTitle(choice.title)
			.setSingleChoiceItems(labels, checked) { dialog, which ->
				viewModel.onMangaTargetSelected(prompt.source, choice.url, prompt.options[which].targetName)
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun onLoadingChanged(value: Quadruple<Boolean, List<BackupSectionModel>, Date?, Boolean>) {
		val (isLoading, entries, backupDate, isMergeEnabled) = value
		val hasEntries = entries.isNotEmpty()
		with(requireViewBinding()) {
			progressBar.isVisible = isLoading
			recyclerView.isGone = isLoading
			textViewSubtitle.textAndVisible =
				when {
					!isLoading -> backupDate?.formatBackupDate()
					hasEntries -> getString(R.string.processing_)
					else -> getString(R.string.loading_)
				}
			checkboxMerge.isVisible = !isLoading && hasEntries
			checkboxMerge.isChecked = isMergeEnabled
			groupSourceConflict.isVisible = !isLoading && hasEntries && viewModel.hasAmbiguousSources.value
			buttonRestore.isEnabled = !isLoading && entries.any { it.isChecked }
		}
	}

	private fun startRestoreService(): Boolean {
		return RestoreService.start(
			context ?: return false,
			viewModel.uri ?: return false,
			viewModel.getCheckedSections(),
			viewModel.isMergeEnabled.value,
			viewModel.buildSourceRemap(),
		)
	}

	data class Quadruple<out A, out B, out C, out D>(
		val first: A,
		val second: B,
		val third: C,
		val fourth: D,
	)

	private fun Date.formatBackupDate(): String {
		return getString(
			R.string.backup_date_,
			SimpleDateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(this),
		)
	}

	private fun onError(e: Throwable) {
		MaterialAlertDialogBuilder(context ?: return)
			.setNegativeButton(R.string.close, null)
			.setTitle(R.string.error)
			.setMessage(e.getDisplayMessage(resources))
			.show()
		dismiss()
	}
}
