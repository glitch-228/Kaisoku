package org.koitharu.kotatsu.suggestions.local

internal data class RecommendationReadingState(
    val key: String,
    val lastRead: Long,
    val progress: Float,
    val chapters: Int,
    val exact: RecommendationExactReading? = null,
) {
    val started: Boolean get() = lastRead > 0 || exact?.hasReadRecords == true
}

internal data class RecommendationExactReading(
    val readCount: Int?, val totalCount: Int?, val generation: String,
    val branch: String?, val hasReadRecords: Boolean = (readCount ?: 0) > 0,
    val unreadReleaseIds: Set<Long>? = null,
) {
    val caughtUp: Boolean get() = totalCount != null && totalCount > 0 && readCount == totalCount
}

internal object RecommendationEligibility {
    fun readingAllowed(
        item: Recommendation,
        states: List<RecommendationReadingState>,
        baselines: Map<String, ChapterBaseline?>,
        threshold: Int,
        allowStarted: Boolean,
    ): Boolean {
        if (states.any { it.exact?.caughtUp == true }) return false
        val started = states.filter { it.started ||
            (it.exact != null && (baselines[it.key]?.lastReadingAt ?: 0) > 0) }
        if (started.isEmpty()) return ChapterReleaseEvidence.eligible(
            baselines[item.key], (baselines[item.key]?.lastReadingAt ?: 0) > 0,
            false, threshold, allowStarted,
        )
        // A trusted match carries reading exclusions to other editions/source copies and public cards.
        return started.all { history ->
            val baseline = baselines[history.key]
            if (baseline == null || baseline.lastReadingAt < history.lastRead) return@all false
            history.exact?.let { exact ->
                if (!baseline.confirmedReadingBoundary || baseline.releaseIdentityVersion != 1 ||
                    baseline.exactGeneration != exact.generation || baseline.branch != exact.branch ||
                    exact.totalCount != baseline.total || exact.unreadReleaseIds == null) return@all false
                return@all ChapterReleaseEvidence.eligible(
                    baseline.copy(releases = exact.unreadReleaseIds.count { it in baseline.releaseChapterIds }),
                    true, exact.caughtUp, threshold, allowStarted,
                )
            }
            ChapterReleaseEvidence.eligible(
                baseline, true,
                history.progress >= 1f && history.chapters >= (baseline?.total ?: history.chapters),
                threshold, allowStarted,
            )
        }
    }
}
