package org.koitharu.kotatsu.reader.ui

import android.content.Context
import androidx.annotation.UiContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.SourceSwitchCandidate
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.reader.domain.SourceHealthMonitor

/** Offers other implementations of the failing site, plus a search on other sites. */
object SourceSwitchDialog {

	fun show(
		@UiContext context: Context,
		suggestion: SourceSwitchSuggestion,
		onSwitch: (SourceSwitchCandidate) -> Unit,
		onAlternatives: () -> Unit,
		onMute: () -> Unit,
	): AlertDialog {
		val actions = ArrayList<Pair<CharSequence, () -> Unit>>()
		suggestion.candidates.forEach { candidate ->
			actions += context.candidateLabel(candidate) to { onSwitch(candidate) }
		}
		if (suggestion.offerAlternatives) {
			actions += context.getString(R.string.manga_unavailable_search_alternatives) to onAlternatives
		}
		actions += context.getString(R.string.source_prompt_mute) to onMute
		val sourceTitle = suggestion.manga.source.getTitle(context)
		return MaterialAlertDialogBuilder(context)
			.setTitle(
				context.getString(
					if (suggestion.trigger == SourceHealthMonitor.Trigger.SLOW) {
						R.string.source_switch_title_slow
					} else {
						R.string.source_switch_title_errors
					},
					sourceTitle,
				),
			)
			.setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
			.setNegativeButton(R.string.close, null)
			.show()
	}

	private fun Context.candidateLabel(candidate: SourceSwitchCandidate): String {
		val kind = when (candidate.kind) {
			SourceSwitchCandidate.Kind.PLUGIN -> getString(
				R.string.source_switch_kind_plugin,
				candidate.origin?.removeSuffix(".jar").orEmpty(),
			).trim()

			SourceSwitchCandidate.Kind.BUILT_IN -> getString(R.string.source_switch_kind_builtin)
			SourceSwitchCandidate.Kind.EXTENSION -> candidate.origin?.takeIf { candidate.needsInstall }
				?: getString(R.string.source_switch_kind_extension)
		}
		return getString(
			if (candidate.needsInstall) R.string.source_switch_install else R.string.source_switch_to,
			candidate.title,
			kind,
		)
	}
}
