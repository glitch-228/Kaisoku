package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.RateLimitInterceptor

class MihonNetworkHelper(
    private val httpClient: OkHttpClient,
    private val defaultUserAgent: () -> String,
) : NetworkHelper() {

    override val client: OkHttpClient = httpClient.newBuilder()
        .apply {
            // Extensions inspect HTTP 429 themselves and may implement their own bounded retries.
            interceptors().removeAll { it is RateLimitInterceptor }
            interceptors().add(0, UncaughtExceptionInterceptor())
            interceptors().add(1, UserAgentInterceptor(defaultUserAgent))
        }
        .addInterceptor(CloudflareInterceptor())
        .build()

    override fun defaultUserAgentProvider(): String = defaultUserAgent()
}
