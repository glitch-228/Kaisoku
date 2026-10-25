package org.koitharu.kotatsu.remote.library

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.SourceContentClass
import org.koitharu.kotatsu.core.model.SourceContentPolicy
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails

class LibraryPrivacyPolicyTest {
    @org.junit.Test fun copiedReaderWrappersSanitizeNestedLibraryFailuresAndJsonSecrets() {
        val raw = java.io.IOException("Failed https://reader:secret-password@library.invalid/books/page?signed=secret-capability#secret-fragment\n" +
            "{\"password\":\"secret pass with spaces\",\"refresh_token\":\"refresh-secret\",\"Authorization\":\"Basic basic-secret\"}\n" +
            "Cookie: session=cookie-secret\ntrace detail")
        val privateFailure = RemoteLibraryException("The library request could not be completed", raw)
        val wrapper = IllegalStateException("Reader failed https://library.invalid/page?auth=wrapper-secret", privateFailure)
        val safe = wrapper.getCopyableErrorDetails()
        for (secret in listOf("secret-password", "secret-capability", "secret-fragment", "secret pass with spaces",
            "refresh-secret", "basic-secret", "cookie-secret", "wrapper-secret")) assertFalse(secret, safe.contains(secret))
        assertTrue(safe.contains("IllegalStateException"))
        assertTrue(safe.contains("RemoteLibraryException"))
        assertTrue(safe.contains("IOException"))
        assertTrue(safe.contains("trace detail"))
    }

    @org.junit.Test fun copiedEscapedJsonUrlsAndQuotedFieldsStayPrivateAndBounded() {
        val text = "{\"url\":\"https:\\/\\/user:escaped-password@library.invalid\\/path?signature=escaped-capability\",\"token\":\"token with \\\"quotes\\\" secret\"}"
        val error = RemoteLibraryException("Failure", java.io.IOException(text + "x".repeat(100_000)))
        val safe = error.getCopyableErrorDetails()
        assertFalse(safe.contains("escaped-password"))
        assertFalse(safe.contains("escaped-capability"))
        assertFalse(safe.contains("quotes"))
        assertTrue(safe.length <= 64 * 1024 + 100)
    }
    @Test fun sourceSafeOverrideCannotEraseAnExplicitAdultPublicationRating() {
        for (policy in listOf(SourceContentPolicy(false, setOf(SourceContentClass.SAFE), true),
            SourceContentPolicy(true, setOf(SourceContentClass.SAFE), true))) {
            assertTrue(allowsLibraryContent(policy, RemoteLibraryContent.ADULT, false, true))
            assertFalse(allowsLibraryContent(policy, RemoteLibraryContent.ADULT, false, false))
            assertTrue(allowsLibraryContent(policy, RemoteLibraryContent.UNKNOWN, false, false))
            assertFalse(allowsLibraryContent(policy, RemoteLibraryContent.UNKNOWN, null, false))
            assertFalse(allowsLibraryContent(policy, RemoteLibraryContent.SAFE, true, false))
        }
    }

    @Test fun diagnosticDetailsKeepCausesAndStacksButRemoveCredentialAndCapabilityValues() {
        val error = RemoteLibraryException("Failed https://reader:password123@server.invalid/books/one?token=capability123&key=secret123",
            java.io.IOException("Authorization: Basic encodedCredentials\npassword=privatePassword refresh_token=rotatingSecret\n" +
                "https://server.invalid/books/two?download=capability456#token"))
        val text = LibraryDiagnostics.text(error)
        for (secret in listOf("password123", "capability123", "secret123", "encodedCredentials", "privatePassword", "rotatingSecret", "capability456")) {
            assertFalse(secret, text.contains(secret))
        }
        assertTrue(text.contains("server.invalid/books/one"))
        assertTrue(text.contains("Caused by: java.io.IOException"))
        assertTrue(text.contains("LibraryPrivacyPolicyTest"))
        assertTrue(text.contains("[redacted]"))
    }
}
