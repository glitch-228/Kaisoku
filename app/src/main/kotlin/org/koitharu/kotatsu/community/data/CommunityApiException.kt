package org.koitharu.kotatsu.community.data

import java.io.IOException

sealed class CommunityApiException(message: String, cause: Throwable? = null) : IOException(message, cause) {
	data class RateLimited(val retryAfterSeconds: Long, val bucket: String?) :
		CommunityApiException("Community requests are limited. Try again in ${retryAfterSeconds}s")
	data class Overloaded(val retryAfterSeconds: Long) :
		CommunityApiException("Community server is busy. Try again in ${retryAfterSeconds}s")
	data object Unauthorized : CommunityApiException("Community identity is no longer authorized")
	data object Banned : CommunityApiException("Community access is unavailable for this account")
}
