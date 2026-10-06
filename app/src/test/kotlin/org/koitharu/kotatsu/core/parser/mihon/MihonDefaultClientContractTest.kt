package org.koitharu.kotatsu.core.parser.mihon

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource

class MihonDefaultClientContractTest {

    private val request = Request.Builder().url("https://extension.invalid/catalog").build()

    @Test
    fun defaultClientPassesKeiSourceNetworkChecks() {
        val helper = helper()
        extensionClient(helper).newCall(request).execute().use { assertEquals("catalog", it.body.string()) }
        assertEquals("UncaughtExceptionInterceptor", helper.client.interceptors.first().javaClass.simpleName)
    }

    @Test
    fun rawRequestsReceiveTheConfiguredUserAgent() {
        val helper = helper()
        extensionClient(helper).newCall(request).execute().use {
            assertEquals("Configured agent", it.request.header("User-Agent"))
            assertEquals(listOf("Configured agent"), it.request.headers.values("User-Agent"))
        }
    }

    @Test
    fun emptyUserAgentUsesTheDefault() {
        val helper = helper()
        val empty = request.newBuilder().header("User-Agent", "").build()
        extensionClient(helper).newCall(empty).execute().use {
            assertEquals(listOf("Configured agent"), it.request.headers.values("User-Agent"))
        }
    }

    @Test
    fun sourceUserAgentAndRequestIdentityArePreserved() {
        var providerCalls = 0
        val helper = MihonNetworkHelper(OkHttpClient()) { providerCalls++; "Default agent" }
        val custom = request.newBuilder()
            .header("User-Agent", "Source-specific agent")
            .header("Referer", "https://extension.invalid/manga/42")
            .tag(MangaSource::class.java, MangaParserSource.READMANGA_RU)
            .build()
        extensionClient(helper).newCall(custom).execute().use {
            assertEquals("Source-specific agent", it.request.header("User-Agent"))
            assertEquals(custom.url, it.request.url)
            assertEquals(custom.header("Referer"), it.request.header("Referer"))
            assertSame(MangaParserSource.READMANGA_RU, it.request.tag(MangaSource::class.java))
        }
        assertEquals(0, providerCalls)
    }

    @Test
    fun userAgentPreferenceChangesApplyWithoutRecreatingTheClient() {
        var agent = "First agent"
        val helper = MihonNetworkHelper(OkHttpClient()) { agent }
        val client = extensionClient(helper)
        client.newCall(request).execute().use { assertEquals("First agent", it.request.header("User-Agent")) }
        agent = "Second agent"
        client.newCall(request).execute().use { assertEquals("Second agent", it.request.header("User-Agent")) }
    }

    @Test
    fun extensionCanReplaceTheNamedUserAgentInterceptor() {
        val helper = helper()
        val builder = extensionClient(helper).newBuilder()
        val index = builder.interceptors().indexOfFirst { it.javaClass.simpleName == "UserAgentInterceptor" }
        assertTrue(index >= 0)
        builder.interceptors()[index] = Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Extension agent").build())
        }
        builder.build().newCall(request).execute().use {
            assertEquals("Extension agent", it.request.header("User-Agent"))
        }
        extensionClient(helper).newCall(request).execute().use {
            assertEquals("Configured agent", it.request.header("User-Agent"))
        }
    }

    @Test
    fun extensionCanRemoveTheNamedCloudflareInterceptor() {
        val helper = helper()
        val builder = extensionClient(helper, 403, "<form id=\"challenge-form\"></form>").newBuilder()
        assertTrue(builder.interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" })
        builder.build().newCall(request).execute().use {
            assertEquals(403, it.code)
            assertTrue(it.body.string().contains("challenge-form"))
        }
        assertTrue(helper.client.interceptors.any { it.javaClass.simpleName == "CloudflareInterceptor" })
    }

    @Test
    fun sourceInterceptorsRunBeforeCloudflareAndPreserveTheChallengeContext() {
        val helper = helper()
        val builder = helper.client.newBuilder()
        builder.addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Referer", "https://extension.invalid/manga/42?session=fixture")
                    .tag(MangaSource::class.java, MangaParserSource.READMANGA_RU)
                    .build(),
            )
        }
        moveCloudflareLast(builder)
        builder.addInterceptor { chain -> response(chain, 403, "<form id=\"challenge-form\"></form>") }
        val error = assertThrows(CloudFlareProtectedException::class.java) {
            builder.build().newCall(request).execute()
        }
        assertEquals("https://extension.invalid/manga/42", error.url)
        assertEquals("Configured agent", error.headers["User-Agent"])
        assertSame(MangaParserSource.READMANGA_RU, error.source)
    }

    @Test
    fun blockedCloudflareResponseRemainsReadableForCookieSeeding() {
        val body = "<h2 data-translate=\"blocked_why_headline\">Blocked</h2>"
        extensionClient(helper(), 403, body).newCall(request).execute().use {
            assertEquals(403, it.code)
            assertEquals(body, it.body.string())
        }
    }

    @Test
    fun ordinaryForbiddenAndUnavailableResponsesRemainReadable() {
        for (code in listOf(403, 503)) {
            extensionClient(helper(), code, "Temporary API error").newCall(request).execute().use {
                assertEquals(code, it.code)
                assertEquals("Temporary API error", it.body.string())
            }
        }
    }

    @Test
    fun rateLimitedResponseReachesExtensionRetryCodeWithoutChangingTheHostClient() {
        val base = OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor())
            .addInterceptor { chain -> response(chain, 429, "Please retry later") }
            .build()
        val helper = MihonNetworkHelper(base) { "Default agent" }
        helper.client.newCall(request).execute().use {
            assertEquals(429, it.code)
            assertEquals("17", it.header("Retry-After"))
            assertEquals("Please retry later", it.body.string())
        }
        assertFalse(helper.client.interceptors.any { it is RateLimitInterceptor })
        assertTrue(base.interceptors.any { it is RateLimitInterceptor })
        assertThrows(TooManyRequestExceptions::class.java) { base.newCall(request).execute() }
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyCloudflareClientUsesTheSameCompatibleClient() {
        val helper = helper()
        assertSame(helper.client, helper.cloudflareClient)
        extensionClient(helper).newCall(request).execute().close()
    }

    private fun helper() = MihonNetworkHelper(OkHttpClient()) { "Configured agent" }

    // Consumer contract from keiyoushi/source/KeiSource.kt; these checks run before any HTTP request.
    private fun extensionClient(helper: MihonNetworkHelper, code: Int = 200, body: String = "catalog"): OkHttpClient {
        val builder = helper.client.newBuilder()
        for (name in listOf("UncaughtExceptionInterceptor", "UserAgentInterceptor", "CloudflareInterceptor")) {
            check(builder.interceptors().any { it.javaClass.simpleName == name }) {
                "$name must be present in default client"
            }
        }
        for (name in listOf("IgnoreGzipInterceptor", "BrotliInterceptor")) {
            check(builder.networkInterceptors().none { it.javaClass.simpleName == name }) {
                "$name must not be present in default client"
            }
        }
        moveCloudflareLast(builder)
        return builder.addInterceptor { chain -> response(chain, code, body) }.build()
    }

    private fun moveCloudflareLast(builder: OkHttpClient.Builder) {
        val interceptor = builder.interceptors().first { it.javaClass.simpleName == "CloudflareInterceptor" }
        builder.interceptors().remove(interceptor)
        builder.addInterceptor(interceptor)
    }

    private fun response(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Fixture")
        .header("Retry-After", "17")
        .body(body.toResponseBody())
        .build()
}
