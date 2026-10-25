package org.koitharu.kotatsu.details.translation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.redactSecrets
import org.koitharu.kotatsu.reader.translate.TextTranslationConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.net.URI
import java.security.MessageDigest

internal data class SynopsisTranslationRequest(
    val text: String,
    val configuration: TextTranslationConfig,
    /** False for decrypted archive content; neither cache reads nor writes are then permitted. */
    val allowPersistentCache: Boolean = true,
) {
    fun cacheKey(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(
            "synopsis-text-v1", text, configuration.provider.name, configuration.model,
            configuration.sourceLanguage, configuration.targetLanguage, configuration.endpoint,
            configuration.apiKey, configuration.customHeaders,
        )) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun safeErrorDetails(error: Throwable): String = redactConfiguredSecrets(error.getCopyableErrorDetails())

    fun safeErrorSummary(error: Throwable): String =
        redactConfiguredSecrets(error.localizedMessage.orEmpty()).take(300)

    private fun redactConfiguredSecrets(value: String): String {
        var safe = value.redactSecrets()
        val secrets = buildList {
            add(configuration.apiKey)
            add(configuration.endpoint)
            runCatching { URI(configuration.endpoint).userInfo }.getOrNull()?.let { userInfo ->
                add(userInfo)
                add(userInfo.substringBefore(':'))
                add(userInfo.substringAfter(':', ""))
            }
            if (configuration.customHeaders.startsWith('{')) {
                runCatching {
                    val headers = JSONObject(configuration.customHeaders)
                    headers.keys().forEach { name -> add(headers.optString(name)) }
                }
            } else {
                configuration.customHeaders.lineSequence().forEach { header ->
                    add(header.substringAfter(':', "").trim())
                }
            }
        }.filter { it.isNotBlank() }.sortedByDescending { it.length }
        for (secret in secrets) safe = safe.replace(secret, "[REDACTED]")
        return safe.take(64 * 1024)
    }
}

internal data class SynopsisTranslationResult(val text: String, val cached: Boolean)

internal interface SynopsisTranslationCache {
    fun read(key: String): String?
    fun write(key: String, text: String)
}

internal class SynopsisTranslationRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason { EMPTY_INPUT, INPUT_TOO_LONG, EMPTY_RESPONSE, RESPONSE_TOO_LONG }
}

/** Only [translate] invokes a provider; restoring a sheet uses [cached] exclusively. */
internal class SynopsisTranslationService(
    private val cache: SynopsisTranslationCache,
    private val translateText: suspend (String, TextTranslationConfig, (Int, Int) -> Unit) -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val requestMutex = Mutex()

    suspend fun cached(request: SynopsisTranslationRequest): SynopsisTranslationResult? = withContext(dispatcher) {
        validateInput(request.text)
        readCache(request)?.let { SynopsisTranslationResult(it, cached = true) }
    }

    suspend fun translate(
        request: SynopsisTranslationRequest,
        force: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): SynopsisTranslationResult = withContext(dispatcher) {
        validateInput(request.text)
        // The second identical caller observes the completed cache rather than submitting twice.
        requestMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!force) readCache(request)?.let { return@withLock SynopsisTranslationResult(it, cached = true) }
            val text = translateText(request.text, request.configuration, onProgress)
            currentCoroutineContext().ensureActive()
            validateOutput(text)
            if (request.allowPersistentCache) {
                try {
                    cache.write(request.cacheKey(), text)
                } catch (_: IOException) {
                    // Disposable cache failures cannot discard a successful, paid response.
                } catch (_: SecurityException) {
                    // Treat unavailable private cache storage like a normal cache miss.
                }
            }
            SynopsisTranslationResult(text, cached = false)
        }
    }

    private fun readCache(request: SynopsisTranslationRequest): String? {
        if (!request.allowPersistentCache) return null
        return try {
            cache.read(request.cacheKey())?.takeIf {
                it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun validateInput(text: String) {
        if (text.isBlank()) throw SynopsisTranslationRejected(SynopsisTranslationRejected.Reason.EMPTY_INPUT)
        if (text.length > MAX_INPUT_CHARS) {
            throw SynopsisTranslationRejected(SynopsisTranslationRejected.Reason.INPUT_TOO_LONG)
        }
    }

    private fun validateOutput(text: String) {
        if (text.isBlank()) throw SynopsisTranslationRejected(SynopsisTranslationRejected.Reason.EMPTY_RESPONSE)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
            throw SynopsisTranslationRejected(SynopsisTranslationRejected.Reason.RESPONSE_TOO_LONG)
        }
    }

    companion object {
        const val MAX_INPUT_CHARS = 100_000
        const val MAX_RESULT_BYTES = 512 * 1024
    }
}
