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

	/** Synchronous entity lookup for already-loaded plugin sources (registry-backed). */
	fun peekEntity(pluginId: String): LnReaderSourceEntity? = entitiesById[pluginId]

	/**
	 * HTTP client for plugin fetches: the manga stack minus the interceptors whose
	 * behaviour the plugin bridge handles itself (the bridge's cause-chain matcher
	 * converts `CloudFlareProtectedException` into the native captcha flow).
	 */
	val httpClient: OkHttpClient
		get() = clientLock.read {
			cachedClient ?: clientLock.write {
				cachedClient ?: baseHttpClient.newBuilder()
					.apply {
						val filtered = baseHttpClient.interceptors.filterNot {
							val name = it.javaClass.simpleName
							name == "CloudFlareInterceptor" ||
								name == "CommonHeadersInterceptor"
						}
						interceptors().clear()
						filtered.forEach(::addInterceptor)
					}
					.build()
					.also { cachedClient = it }
			}
		}

	/**
	 * Reloads all installed plugin sources into the registry and refreshes
	 * the MangaSourceRegistry update signal.
	 */
	suspend fun reload() = withContext(Dispatchers.IO) {
		val entities = dao.findAll()
		entitiesById = entities.associateBy { it.pluginId }
		LnReaderSourceRegistry.replaceAll(entities.map { it.toMangaSource() })
		MangaSourceRegistry.updates.tryEmit(Unit)
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

fun LnReaderSourceEntity.toMangaSource(): LnReaderMangaSource = LnReaderMangaSource(
	pluginId = pluginId,
	displayName = name,
	lang = lang,
	site = site,
)
