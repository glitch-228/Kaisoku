package org.koitharu.kotatsu.core.network.imageproxy

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.util.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealImageProxyInterceptor @Inject constructor(
	private val settings: AppSettings,
) : ImageProxyInterceptor {

	private val wsrv = WsrvNlProxyInterceptor()
	private val zeroMs = ZeroMsProxyInterceptor()

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		return currentDelegate()?.intercept(chain) ?: chain.proceed()
	}

	override suspend fun interceptPageRequest(request: Request, okHttp: OkHttpClient): Response {
		return currentDelegate()?.interceptPageRequest(request, okHttp) ?: okHttp.newCall(request).await()
	}

	private fun currentDelegate(): ImageProxyInterceptor? = when (val proxy = settings.imagesProxy) {
		-1 -> null
		0 -> wsrv
		1 -> zeroMs
		else -> error("Unsupported images proxy $proxy")
	}
}
