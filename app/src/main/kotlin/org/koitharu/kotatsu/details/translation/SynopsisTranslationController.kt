package org.koitharu.kotatsu.details.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.reader.translate.TextTranslationConfig
import java.util.concurrent.atomic.AtomicInteger

internal data class SynopsisTranslationState(
    val configuration: TextTranslationConfig,
    val result: String? = null,
    val cached: Boolean = false,
    val loading: Boolean = false,
    val completedParts: Int = 0,
    val totalParts: Int = 0,
    val errorSummary: String? = null,
    val errorDetails: String? = null,
    val rejection: SynopsisTranslationRejected.Reason? = null,
    val showOriginal: Boolean = false,
)

/** UI-lifetime state only: recreation never restarts a provider request. */
internal class SynopsisTranslationController(
    original: String,
    val allowPersistentCache: Boolean,
    configuration: TextTranslationConfig,
    private val service: SynopsisTranslationService,
    private val scope: CoroutineScope,
) {
    var original: String = original
        private set
    private val mutableState = MutableStateFlow(SynopsisTranslationState(configuration))
    val state = mutableState.asStateFlow()
    private val generation = AtomicInteger()
    private var work: Job? = null

    init {
        readCached()
    }

    fun updateConfiguration(configuration: TextTranslationConfig) {
        if (request(configuration).cacheKey() == request(state.value.configuration).cacheKey()) return
        cancel()
        mutableState.value = SynopsisTranslationState(configuration)
        readCached()
    }

    fun translate(force: Boolean) {
        if (state.value.loading) return
        work?.cancel()
        val token = generation.incrementAndGet()
        val request = request(state.value.configuration)
        mutableState.update {
            it.copy(loading = true, completedParts = 0, totalParts = 0, errorSummary = null, errorDetails = null,
                rejection = null, showOriginal = false)
        }
        work = scope.launch {
            try {
                val result = service.translate(request, force) { done, total ->
                    if (generation.get() == token) mutableState.update {
                        it.copy(completedParts = done, totalParts = total)
                    }
                }
                if (generation.get() == token) mutableState.update {
                    it.copy(result = result.text, cached = result.cached, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation.get() == token) mutableState.update {
                    it.copy(loading = false, errorSummary = request.safeErrorSummary(e),
                        errorDetails = request.safeErrorDetails(e),
                        rejection = (e as? SynopsisTranslationRejected)?.reason)
                }
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        work?.cancel()
        work = null
        mutableState.update { it.copy(loading = false) }
    }

    fun toggleOriginal() {
        if (state.value.result != null) mutableState.update { it.copy(showOriginal = !it.showOriginal) }
    }

    fun clearSensitiveContent() {
        if (allowPersistentCache) return
        cancel()
        original = ""
        mutableState.value = SynopsisTranslationState(state.value.configuration)
    }

    private fun readCached() {
        val token = generation.incrementAndGet()
        val request = request(state.value.configuration)
        work = scope.launch {
            try {
                val result = service.cached(request) ?: return@launch
                if (generation.get() == token) mutableState.update {
                    it.copy(result = result.text, cached = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SynopsisTranslationRejected) {
                if (generation.get() == token) mutableState.update { it.copy(rejection = e.reason) }
            }
        }
    }

    private fun request(configuration: TextTranslationConfig) =
        SynopsisTranslationRequest(original, configuration, allowPersistentCache)
}
