package org.koitharu.kotatsu.history.exact

/** Query projections deliberately omit chapter JSON and explicit-record text. */
data class RecommendationReadCount(
    val mangaId: Long,
    val branchKey: String,
    val readCount: Int,
    val totalCount: Int,
    val listRevision: String,
    val manifestBytes: Long,
)

data class RecommendationReadFootprint(val rowCount: Long, val textBytes: Long)
