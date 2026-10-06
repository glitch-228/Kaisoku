package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.parser.mihon.MihonSourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

/**
 * Keeps Mihon's named interceptor contract while routing challenges through Kaisoku's recovery UI.
 * Block pages remain readable for sources that deliberately fetch them to seed cookies.
 */
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        when (CloudFlareHelper.checkResponseForProtection(response)) {
            CloudFlareHelper.PROTECTION_CAPTCHA -> {
                val source = request.tag(MangaSource::class.java)
                    ?: MihonSourceRegistry.findSourceByHost(request.url.host)
                val url = request.toChallengeUrl()
                response.close()
                throw CloudFlareProtectedException(
                    url = url,
                    source = source,
                    headers = request.headers,
                )
            }

            else -> return response
        }
    }

    private fun Request.toChallengeUrl(): String {
        val referer = header("Referer")?.toHttpUrlOrNull()
        if (referer != null && referer.host == url.host) {
            return referer.newBuilder().query(null).fragment(null).build().toString()
        }
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }
}
