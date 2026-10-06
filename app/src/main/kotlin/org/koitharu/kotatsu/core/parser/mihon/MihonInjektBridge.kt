package org.koitharu.kotatsu.core.parser.mihon

import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import javax.inject.Inject
import javax.inject.Singleton
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.TypeReference
import java.lang.reflect.Type

@Singleton
class MihonInjektBridge @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val cookieJar: CookieJar,
	private val mangaLoaderContextLazy: dagger.Lazy<MangaLoaderContextImpl>,
) {

	@Volatile
	private var initialized = false

	@Synchronized
	fun initialize() {
		if (initialized) {
			return
		}
		val application = context.applicationContext as Application
		val applicationContext = context.applicationContext
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		val extensionClient = httpClient.newBuilder()
			// Cloudflare handling inside the hosted extension source is android-managed solely by the
			// dedicated Mihon-side interceptor installed right after `Kagane`-style WebView bridge-
			// candidate URLs. The host's `CloudFlareInterceptor` throws `CloudFlareBlockedException`
			// on 403 "Sorry, you have been blocked" pages, which would abort extension code that
			// deliberately issues the fetch and ignores the response (Kagane's getIntegrityToken et
			// al.). Match Mihon's loader semantics: skip `CloudFlareInterceptor` + the synthetic-
			// header-rich `CommonHeadersInterceptor` (X-Requested-With/Origin, both Cloudflare trip
			// signals). MihonNetworkHelper also leaves HTTP 429 readable for extension retry logic.
			.apply {
				val filtered = httpClient.interceptors.filterNot {
					val name = it.javaClass.simpleName
					name == "CloudFlareInterceptor" ||
						name == "CommonHeadersInterceptor"
				}
				interceptors().clear()
				filtered.forEach(::addInterceptor)
			}
			.build()
		val networkHelper = MihonNetworkHelper(extensionClient) {
			mangaLoaderContextLazy.get().getDefaultUserAgent()
		}
		Injekt.importModule(object : InjektModule {
			override fun InjektRegistrar.registerInjectables() {
				addSingleton(typeReference(Application::class.java), application)
				addSingleton(typeReference(Context::class.java), applicationContext)
				addSingleton(typeReference(NetworkHelper::class.java), networkHelper)
				addSingleton(typeReference(OkHttpClient::class.java), networkHelper.client)
				addSingleton(typeReference(CookieJar::class.java), cookieJar)
				addSingleton(typeReference(Json::class.java), json)
				addSingleton(typeReference(StringFormat::class.java), json)
				addSingleton(typeReference(SerialFormat::class.java), json)
			}
		})
		initialized = true
	}

	private fun <T> typeReference(type: Type): TypeReference<T> = object : TypeReference<T> {
		override val type: Type = type
	}
}
