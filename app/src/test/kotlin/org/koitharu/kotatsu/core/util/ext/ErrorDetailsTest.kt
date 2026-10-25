package org.koitharu.kotatsu.core.util.ext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorDetailsTest {
    @org.junit.Test fun basicAuthorizationPayloadIsRedactedInCopiedHeadersAndJson() {
        val credential = "Zml4dHVyZS1hY2NvdW50OnNlY3JldA=="
        val raw = "Authorization: Basic $credential\n{\"authorization\":\"Basic $credential\"}\nrequest failed"
        val safe = raw.redactSecrets()
        org.junit.Assert.assertFalse(safe.contains(credential))
        org.junit.Assert.assertTrue(safe.contains("request failed"))
        org.junit.Assert.assertTrue(safe.contains("[REDACTED]"))
    }
    @Test fun quotedBearerAndKeysAreFullyRemoved() {
        val input = """{"authorization":"Bearer private-token", "api_key":"secret with spaces", "x-api-key":"custom-secret", "message":"upstream unavailable"}"""
        val safe = input.redactSecrets()
        listOf("private-token", "secret with spaces", "custom-secret").forEach { assertFalse(safe.contains(it)) }
        assertTrue(safe.contains("upstream unavailable"))
    }

    @Test fun plainHeaderAndQueryTokensAreRemoved() {
        val safe = "Authorization: Bearer private-token\nhttps://example.test/?key=query-secret&model=test".redactSecrets()
        assertFalse(safe.contains("private-token"))
        assertFalse(safe.contains("query-secret"))
        assertTrue(safe.contains("model=test"))
    }
}
