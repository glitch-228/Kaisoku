package org.koitharu.kotatsu.remote.library

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LibraryUrlPolicyTest {
    private val policy = LibraryUrlPolicy("https://library.invalid/komga/".toHttpUrl())

    @Test fun credentialsAreScopedToExactOriginAndPathSegments() {
        assertTrue(policy.contains("https://library.invalid/komga/api/book".toHttpUrl()))
        assertTrue(policy.contains("https://library.invalid/komga".toHttpUrl()))
        for (url in listOf("https://library.invalid/komga-evil/file", "https://other.invalid/komga/file", "https://library.invalid:8443/komga/file")) {
            assertThrows(LibraryScopeException::class.java) { policy.validate(url.toHttpUrl()) }
        }
        assertEquals("https://library.invalid/komga/book", policy.resolve("https://library.invalid/komga/opds/catalog", "../book").toString())
    }

    @Test fun ambiguousEncodedPathsAndCredentialsAreRejected() {
        for (url in listOf("https://library.invalid/komga/x%2fy", "https://library.invalid/komga/x%5cy",
            "https://library.invalid/komga/%252e%252e/secret", "https://library.invalid/komga/x%00y")) {
            assertThrows(LibraryScopeException::class.java) { policy.validate(url.toHttpUrl()) }
        }
        assertThrows(IllegalArgumentException::class.java) { policy.validate("https://user:secret@library.invalid/komga/a".toHttpUrl()) }
        assertThrows(IllegalArgumentException::class.java) { policy.validate("http://library.invalid/komga/a".toHttpUrl()) }
        assertThrows(RemoteLibraryException::class.java) { policy.resolve("https://library.invalid/komga/", "file:///tmp/book") }
    }

    @Test fun foreignAcquisitionRequiresExplicitAnonymousConsentAndStillRequiresHttps() {
        assertThrows(LibraryScopeException::class.java) { policy.validate("https://cdn.invalid/book.cbz".toHttpUrl()) }
        assertEquals("cdn.invalid", policy.validate("https://cdn.invalid/book.cbz".toHttpUrl(), true).host)
        assertThrows(IllegalArgumentException::class.java) { policy.validate("http://cdn.invalid/book.cbz".toHttpUrl(), true) }
    }

    @Test fun komgaRootsRetainConfiguredBasePathsAndNeverDuplicateCatalogSuffixes() {
        for (url in listOf("https://library.invalid/base", "https://library.invalid/base/", "https://library.invalid/base/opds/v2/catalog",
            "https://library.invalid/base/opds/v1.2/catalog")) {
            assertEquals("https://library.invalid/base/", komgaBaseUrl(url.toHttpUrl()).toString())
            assertEquals("https://library.invalid/base/opds/v2/catalog", komgaCatalogUrl(url.toHttpUrl(), 2).toString())
            assertEquals("https://library.invalid/base/opds/v1.2/catalog", komgaCatalogUrl(url.toHttpUrl(), 1).toString())
        }
    }

    @Test fun renamingPreservesSourceCacheIdentityWhileAccountChangesDoNot() {
        val original = RemoteLibraryConnection.create("Home", "https://library.invalid/base", RemoteLibraryKind.KOMGA, "reader")
        val renamed = original.copy(name = "New display name")
        assertEquals(original.sourceName, renamed.sourceName)
        assertEquals(original.cacheScope, renamed.cacheScope)
        assertEquals(RemoteLibrarySource(original.id), RemoteLibrarySource.fromName(original.sourceName))
        assertNotEquals(original.cacheScope, original.copy(account = "different").cacheScope)
        assertFalse(RemoteLibraryStore.sameScope(original, original.copy(account = "different")))
        assertTrue(RemoteLibraryCredentials("reader", "secret").toString().contains("redacted"))
        assertFalse(RemoteLibraryCredentials("reader", "secret").toString().contains("secret"))
    }

    @Test fun cleartextRequiresSavedConsentAndLiteralPrivateAddressesWithoutPublicOrTlsBypasses() {
        for (host in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.2.3", "169.254.1.2", "[::1]", "[fd00::1234]")) {
            val url = "http://$host:25600/base"
            assertThrows(IllegalArgumentException::class.java) { RemoteLibraryConnection.create("Home", url, RemoteLibraryKind.KOMGA) }
            val connection = RemoteLibraryConnection.create("Home", url, RemoteLibraryKind.KOMGA, allowCleartextLan = true)
            assertTrue(LibraryUrlPolicy.forConnection(connection).contains(connection.catalogUrl.toHttpUrl()))
            assertTrue(connection.allowCleartextLan)
        }
        for (host in listOf("8.8.8.8", "172.15.1.2", "192.169.1.2", "example.invalid", "mykomga.local", "0.0.0.0", "[2001:4860::1]")) {
            assertThrows(IllegalArgumentException::class.java) {
                RemoteLibraryConnection.create("Home", "http://$host/base", RemoteLibraryKind.KOMGA, allowCleartextLan = true)
            }
        }
    }
}
