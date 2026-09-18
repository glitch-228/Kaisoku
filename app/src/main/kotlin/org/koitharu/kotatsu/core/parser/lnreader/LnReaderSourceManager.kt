package org.koitharu.kotatsu.core.parser.lnreader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.LnReaderSourceEntity
import org.koitharu.kotatsu.core.model.MangaSourceRegistry
import org.koitharu.kotatsu.core.network.MangaHttpClient
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Loads installed LNReader plugins from the DB into [LnReaderSourceRegistry],
 * and installs/uninstalls plugin sources. The plugin fetch bridge shares the
 * manga HTTP stack (Cloudflare/cookies/DoH/rate limits) with hosted extensions.
 */
@Singleton
class LnReaderSourceManager @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val baseHttpClient: OkHttpClient,
	private val db: MangaDatabase,
) {

	private val dao = db.getLnReaderSourcesDao()

	private val clientLock = ReentrantReadWriteLock()
	private var cachedClient: OkHttpClient? = null

	@Volatile
	private var entitiesById: Map<String, LnReaderSourceEntity> = emptyMap()

	/** Fingerprint of the last reload's registry content; `null` until the first reload ran. */
	@Volatile
	private var lastRegistryFingerprint: String? = null

	/** Synchronous entity lookup for already-loaded plugin sources (registry-backed). */
	fun peekEntity(pluginId: String): LnReaderSourceEntity? = entitiesById[pluginId]

	/**
	 * HTTP client for plugin fetches: the manga stack minus the interceptors whose
	 * behaviour the plugin bridge handles itself (the bridge's cause-chain matcher
	 * converts `CloudFlareProtectedException` into the native captcha flow). The
	 * rate limiter is also dropped: a browser `fetch` resolves on 429 and lets the
	 * plugin decide, whereas the app-wide limiter throws and would abort plugins
	 * that legitimately hit a 429-happy site.
	 */
	val httpClient: OkHttpClient
		get() = clientLock.read {
			cachedClient ?: clientLock.write {
				cachedClient ?: baseHttpClient.newBuilder()
					.apply {
						val filtered = baseHttpClient.interceptors.filterNot {
							val name = it.javaClass.simpleName
							name == "CloudFlareInterceptor" ||
								name == "CommonHeadersInterceptor" ||
								name == "RateLimitInterceptor"
						}
						interceptors().clear()
						filtered.forEach(::addInterceptor)
					}
					.build()
					.also { cachedClient = it }
			}
		}

	/**
	 * Reloads all installed plugin sources into the registry.
	 *
	 * The global [MangaSourceRegistry] update signal is re-emitted only when the installed set
	 * actually changed. Observers like `observeEnabledSources()` re-run assimilation on every
	 * registry update, and assimilation calls this method — an unconditional emit here would put
	 * those two into an endless ping-pong that starves Explore and the sources catalog.
	 */
	suspend fun reload() = withContext(Dispatchers.IO) {
		val entities = dao.findAll()
		entitiesById = entities.associateBy { it.pluginId }
		LnReaderSourceRegistry.replaceAll(entities.map { it.toMangaSource() })
		val fingerprint = registryFingerprint(entities)
		if (fingerprint != lastRegistryFingerprint) {
			lastRegistryFingerprint = fingerprint
			MangaSourceRegistry.updates.tryEmit(Unit)
		}
	}

	suspend fun getInstalledSources(): List<LnReaderMangaSource> = withContext(Dispatchers.IO) {
		dao.findAll().map { it.toMangaSource() }
	}

	suspend fun findByPluginId(pluginId: String): LnReaderSourceEntity? =
		withContext(Dispatchers.IO) { dao.findById(pluginId) }

	/** Installs or replaces a plugin source; returns the row id. */
	suspend fun install(jsContent: String, metadata: LNReaderPluginMetadata): Result<Long> =
		withContext(Dispatchers.IO) {
			try {
				val now = System.currentTimeMillis()
				val existing = dao.findById(metadata.id)
				val entity = LnReaderSourceEntity(
					pluginId = metadata.id,
					name = metadata.name,
					lang = metadata.lang.takeIf { it.isNotBlank() },
					site = metadata.site.takeIf { it.isNotBlank() },
					version = metadata.version,
					iconUrl = metadata.icon.takeIf { it.isNotBlank() },
					jsCode = jsContent,
					createdAt = existing?.createdAt ?: now,
					updatedAt = now,
				)
				dao.upsert(entity)
				reload()
				Result.success(entity.pluginId.hashCode().toLong())
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Result.failure(e)
			}
		}

	suspend fun uninstall(pluginId: String): Boolean = withContext(Dispatchers.IO) {
		val removed = dao.delete(pluginId) > 0
		if (removed) {
			reload()
		}
		removed
	}
}

/**
 * Content fingerprint of an installed-plugin set: identity + everything the registry exposes to
 * observers. Order-insensitive; `js_code` deliberately excluded (it is not part of the source
 * identity and comparing megabyte strings would be wasteful).
 */
internal fun registryFingerprint(entities: List<LnReaderSourceEntity>): String =
	entities.sortedBy(LnReaderSourceEntity::pluginId)
		.joinToString("|") { "${it.pluginId}:${it.name}:${it.lang}:${it.site}:${it.version}" }

fun LnReaderSourceEntity.toMangaSource(): LnReaderMangaSource = LnReaderMangaSource(
	pluginId = pluginId,
	displayName = name,
	lang = lang,
	site = site,
)
