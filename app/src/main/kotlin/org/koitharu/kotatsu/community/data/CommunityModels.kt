package org.koitharu.kotatsu.community.data

data class CommunityIdentity(
	val userId: String,
	val displayName: String,
	val nickname: String?,
	val tier: Int,
)

data class CommunityRating(
	val workId: Long,
	val count: Int,
	val average: Float,
	val mine: Float?,
)

data class CommunityComment(
	val id: Long,
	val author: String,
	val body: String,
	val createdAt: String,
	val score: Double,
	val myVote: Int,
	val isMine: Boolean,
	val isSpoiler: Boolean,
	val deleted: Boolean,
)

data class CommunitySourceScore(
	val source: String,
	val composite: Double,
	val stability: Double,
	val popularity: Double,
	val samples: Int,
)

data class CommunityProbe(
	val source: String,
	val operation: String,
	val ok: Int,
	val fail: Int,
	val empty: Int,
	val cfBlocked: Int,
	val p50Ms: Int,
	val p90Ms: Int,
)
