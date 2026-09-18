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
	val workId: Long = 0L,
	val chapterId: Long? = null,
	val parentId: Long? = null,
	val depth: Int = 0,
	val author: String,
	val body: String,
	val createdAt: String,
	val score: Double,
	val up: Int = 0,
	val down: Int = 0,
	val myVote: Int,
	val isMine: Boolean,
	val isSpoiler: Boolean,
	val lang: String? = null,
	val deleted: Boolean,
)

data class CommunityCommentPage(
	val comments: List<CommunityComment>,
	val total: Int,
	val byLanguage: Map<String, Int> = emptyMap(),
	val minimumLength: Int = 20,
)

data class CommunityNotification(
	val commentId: Long,
	val workId: Long,
	val chapterId: Long?,
	val parentId: Long?,
	val author: String,
	val preview: String,
	val createdAt: String,
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
