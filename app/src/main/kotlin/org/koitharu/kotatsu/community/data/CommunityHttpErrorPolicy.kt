package org.koitharu.kotatsu.community.data

/** Pure HTTP error mapping so community cooldown semantics can be regression-tested locally. */
object CommunityHttpErrorPolicy {
	fun typedError(status: Int, kind: String?, retryAfterSeconds: Long?, limit: String?): CommunityApiException? {
		val retryAfter = retryAfterSeconds?.takeIf { it >= 0L } ?: 60L
		return when (status) {
			401 -> CommunityApiException.Unauthorized
			403 -> CommunityApiException.Banned
			429 -> if (kind.orEmpty().contains("overload", ignoreCase = true) || kind.orEmpty().contains("busy", ignoreCase = true)) {
				CommunityApiException.Overloaded(retryAfter)
			} else {
				CommunityApiException.RateLimited(retryAfter, limit?.takeIf { it.isNotBlank() })
			}
			else -> null
		}
	}
}
