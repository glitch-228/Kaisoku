package org.koitharu.kotatsu.reader.ui

import org.koitharu.kotatsu.alternatives.domain.SourceSwitchCandidate
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.domain.SourceHealthMonitor

data class SourceSwitchSuggestion(
	val manga: Manga,
	val trigger: SourceHealthMonitor.Trigger,
	val candidates: List<SourceSwitchCandidate>,
	val offerAlternatives: Boolean,
	val policyRevision: Long,
)
