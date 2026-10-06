package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/** Supplies the current default without replacing a source's own User-Agent. */
class UserAgentInterceptor(private val defaultUserAgentProvider: () -> String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return if (request.header("User-Agent").isNullOrEmpty()) {
            chain.proceed(
                request.newBuilder()
                    .removeHeader("User-Agent")
                    .addHeader("User-Agent", defaultUserAgentProvider())
                    .build(),
            )
        } else {
            chain.proceed(request)
        }
    }
}
