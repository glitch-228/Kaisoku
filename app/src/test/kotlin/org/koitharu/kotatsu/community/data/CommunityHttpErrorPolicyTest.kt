package org.koitharu.kotatsu.community.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityHttpErrorPolicyTest {
	@Test fun `rate limit retains server retry and bucket`() {
		val error = CommunityHttpErrorPolicy.typedError(429, "rate_limited", 37L, "identity")
		assertTrue(error is CommunityApiException.RateLimited)
		assertEquals(37L, (error as CommunityApiException.RateLimited).retryAfterSeconds)
		assertEquals("identity", error.bucket)
	}

	@Test fun `missing cooldown defaults to one minute`() {
		val error = CommunityHttpErrorPolicy.typedError(429, "rate_limited", null, null)
		assertEquals(60L, (error as CommunityApiException.RateLimited).retryAfterSeconds)
	}

	@Test fun `server overload remains a distinct error`() {
		assertTrue(CommunityHttpErrorPolicy.typedError(429, "server_overloaded", 4L, null) is CommunityApiException.Overloaded)
	}

	@Test fun `authentication and ban responses are distinct`() {
		assertTrue(CommunityHttpErrorPolicy.typedError(401, null, null, null) is CommunityApiException.Unauthorized)
		assertTrue(CommunityHttpErrorPolicy.typedError(403, null, null, null) is CommunityApiException.Banned)
	}
}
