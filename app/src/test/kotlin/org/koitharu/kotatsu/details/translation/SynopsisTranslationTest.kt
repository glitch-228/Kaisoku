package org.koitharu.kotatsu.details.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koitharu.kotatsu.reader.translate.TextTranslationConfig
import org.koitharu.kotatsu.reader.translate.TranslateProvider
import java.io.IOException

class SynopsisTranslationTest {

    @Test
    fun cacheIdentityIncludesExactUnicodeInputAndEveryOutputAffectingSetting() {
        val text = "我的狐仙老婆\n\nمرحبا 😀"
        val base = SynopsisTranslationRequest(text, config())
        assertEquals(base.cacheKey(), SynopsisTranslationRequest(text, config()).cacheKey())
        assertTrue(base.cacheKey().matches(Regex("[a-f0-9]{64}")))
        val variants = listOf(
            SynopsisTranslationRequest(text + " ", config()),
            SynopsisTranslationRequest(text, config(provider = TranslateProvider.GEMINI)),
            SynopsisTranslationRequest(text, config(model = "another-model")),
            SynopsisTranslationRequest(text, config(source = "zh")),
            SynopsisTranslationRequest(text, config(target = "ar")),
            SynopsisTranslationRequest(text, config(endpoint = "https://other.example/v1/chat/completions")),
            SynopsisTranslationRequest(text, config(key = "other-account")),
            SynopsisTranslationRequest(text, config(headers = "X-Account: another")),
        )
        assertTrue(variants.all { it.cacheKey() != base.cacheKey() })
        assertEquals(base.cacheKey(), base.copy(allowPersistentCache = false).cacheKey())
        // Length prefixes make field boundaries unambiguous even when users include delimiters.
        assertNotEquals(
            SynopsisTranslationRequest("x", config(model = "a|b", source = "c")).cacheKey(),
            SynopsisTranslationRequest("x", config(model = "a", source = "b|c")).cacheKey(),
        )
        assertFalse(config().toString().contains("secret-api-key"))
        assertFalse(config().toString().contains("private-header-value"))
    }

    @Test
    fun cachedReadsAndRestoredControllerNeverInitiateProviderRequests() = runTest {
        val cache = MemoryCache()
        var calls = 0
        val service = SynopsisTranslationService(cache, { _, _, _ -> calls++; "Перевод" }, StandardTestDispatcher(testScheduler))
        val request = SynopsisTranslationRequest("原文", config())
        assertNull(service.cached(request))
        assertEquals(0, calls)
        assertEquals(SynopsisTranslationResult("Перевод", false), service.translate(request))
        assertEquals(SynopsisTranslationResult("Перевод", true), service.cached(request))
        val controller = SynopsisTranslationController("原文", true, config(), service, backgroundScope)
        runCurrent()
        assertEquals("Перевод", controller.state.value.result)
        assertTrue(controller.state.value.cached)
        assertEquals(1, calls)
    }

    @Test
    fun identicalConcurrentRequestsCoalesceButExplicitRetranslationCanRunAgain() = runTest {
        val cache = MemoryCache()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val service = SynopsisTranslationService(cache, { _, _, _ ->
            calls++
            started.complete(Unit)
            release.await()
            "Translated $calls"
        }, StandardTestDispatcher(testScheduler))
        val request = SynopsisTranslationRequest("原文", config())
        val first = async { service.translate(request) }
        started.await()
        val second = async { service.translate(request) }
        release.complete(Unit)
        assertEquals("Translated 1", first.await().text)
        assertEquals("Translated 1", second.await().text)
        assertEquals(1, calls)
        assertEquals("Translated 2", service.translate(request, force = true).text)
        assertEquals(2, calls)
    }

    @Test
    fun sensitiveContentBypassesEveryPersistentCacheOperation() = runTest {
        val cache = object : SynopsisTranslationCache {
            override fun read(key: String): String? = error("Sensitive cache read")
            override fun write(key: String, text: String) = error("Sensitive cache write")
        }
        var calls = 0
        val service = SynopsisTranslationService(cache, { _, _, _ -> calls++; "private translation" },
            StandardTestDispatcher(testScheduler))
        val request = SynopsisTranslationRequest("private synopsis", config(), allowPersistentCache = false)
        assertNull(service.cached(request))
        assertEquals("private translation", service.translate(request).text)
        assertEquals(1, calls)
        val controller = SynopsisTranslationController(request.text, false, config(), service, backgroundScope)
        runCurrent()
        controller.translate(force = false)
        runCurrent()
        assertEquals("private translation", controller.state.value.result)
        controller.clearSensitiveContent()
        assertEquals("", controller.original)
        assertNull(controller.state.value.result)
    }

    @Test
    fun cancellationNeverCachesPartialOutputAndTheNextExplicitRequestCanProceed() = runTest {
        val cache = MemoryCache()
        var cancelled = false
        var finish = false
        val started = CompletableDeferred<Unit>()
        val service = SynopsisTranslationService(cache, { _, _, progress ->
            if (finish) return@SynopsisTranslationService "complete second request"
            started.complete(Unit)
            progress(1, 3)
            try { awaitCancellation() } finally { cancelled = true }
        }, StandardTestDispatcher(testScheduler))
        val request = SynopsisTranslationRequest("原文", config())
        val job = launch { service.translate(request) }
        started.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(0, cache.writes)
        assertNull(service.cached(request))
        finish = true
        assertEquals("complete second request", service.translate(request).text)
        assertEquals(1, cache.writes)
    }

    @Test
    fun inputAndOutputLimitsRejectWithoutTruncatingOrCaching() = runTest {
        val cache = MemoryCache()
        var calls = 0
        var response = " "
        val service = SynopsisTranslationService(cache, { _, _, _ -> calls++; response }, StandardTestDispatcher(testScheduler))
        val oversized = "a".repeat(SynopsisTranslationService.MAX_INPUT_CHARS + 1)
        assertRejected(SynopsisTranslationRejected.Reason.INPUT_TOO_LONG) {
            service.translate(SynopsisTranslationRequest(oversized, config()))
        }
        assertRejected(SynopsisTranslationRejected.Reason.EMPTY_INPUT) {
            service.translate(SynopsisTranslationRequest(" \n ", config()))
        }
        assertEquals(0, calls)
        val request = SynopsisTranslationRequest("原文", config())
        assertRejected(SynopsisTranslationRejected.Reason.EMPTY_RESPONSE) { service.translate(request) }
        response = "中".repeat(SynopsisTranslationService.MAX_RESULT_BYTES / 3 + 1)
        assertRejected(SynopsisTranslationRejected.Reason.RESPONSE_TOO_LONG) { service.translate(request) }
        assertEquals(0, cache.writes)
    }

    @Test
    fun brokenCacheDoesNotDiscardSuccessfulProviderOutput() = runTest {
        val service = SynopsisTranslationService(object : SynopsisTranslationCache {
            override fun read(key: String): String? = throw IOException("cache read failure")
            override fun write(key: String, text: String) = throw IOException("cache write failure")
        }, { _, _, _ -> "complete result" }, StandardTestDispatcher(testScheduler))
        val request = SynopsisTranslationRequest("original", config())
        assertNull(service.cached(request))
        assertEquals("complete result", service.translate(request).text)
    }

    @Test
    fun configuredCredentialsAreRemovedEvenWhenProviderEchoesThemWithoutLabels() {
        val request = SynopsisTranslationRequest("original", config())
        val error = IOException(
            "failure secret-api-key private-header-value https://private.example/v1/chat/completions " +
                "?key=another-secret Authorization: Bearer third-secret",
        )
        for (safe in listOf(request.safeErrorSummary(error), request.safeErrorDetails(error))) {
            assertFalse(safe.contains("secret-api-key"))
            assertFalse(safe.contains("private-header-value"))
            assertFalse(safe.contains("private.example"))
            assertFalse(safe.contains("another-secret"))
            assertFalse(safe.contains("third-secret"))
            assertTrue(safe.contains("[REDACTED]"))
        }
        assertTrue(request.safeErrorDetails(IOException("x".repeat(100_000))).length <= 64 * 1024)
    }

    @Test
    fun controllerRetainsMatchingResultAfterFailureAndSettingsChangeCancelsStaleWork() = runTest {
        val cache = MemoryCache()
        var failRequest = false
        var calls = 0
        val service = SynopsisTranslationService(cache, { _, config, progress ->
            calls++
            if (config.targetLanguage == "ar") awaitCancellation()
            progress(1, 1)
            if (failRequest) throw IOException("temporary error secret-api-key")
            "translated"
        }, StandardTestDispatcher(testScheduler))
        val controller = SynopsisTranslationController("original", true, config(), service, backgroundScope)
        runCurrent()
        assertEquals(0, calls)
        controller.translate(force = false)
        runCurrent()
        assertEquals("translated", controller.state.value.result)
        assertFalse(controller.state.value.loading)
        failRequest = true
        controller.translate(force = true)
        runCurrent()
        assertEquals("translated", controller.state.value.result)
        assertTrue(controller.state.value.errorDetails.orEmpty().contains("temporary error"))
        assertFalse(controller.state.value.errorDetails.orEmpty().contains("secret-api-key"))
        failRequest = false
        controller.updateConfiguration(config(target = "ar"))
        runCurrent()
        assertNull(controller.state.value.result)
        assertEquals(2, calls)
        controller.translate(force = false)
        runCurrent()
        assertTrue(controller.state.value.loading)
        controller.updateConfiguration(config(target = "ru"))
        runCurrent()
        assertFalse(controller.state.value.loading)
        assertEquals("translated", controller.state.value.result)
        assertTrue(controller.state.value.cached)
        assertEquals(3, calls)
    }

    private suspend fun assertRejected(reason: SynopsisTranslationRejected.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected rejection $reason")
        } catch (e: SynopsisTranslationRejected) {
            assertEquals(reason, e.reason)
        }
    }

    private class MemoryCache : SynopsisTranslationCache {
        private val values = mutableMapOf<String, String>()
        var writes = 0
        override fun read(key: String): String? = values[key]
        override fun write(key: String, text: String) { values[key] = text; writes++ }
    }

    private fun config(
        provider: TranslateProvider = TranslateProvider.OPENAI_COMPATIBLE,
        model: String = "test-model",
        source: String = "auto",
        target: String = "ru",
        endpoint: String = "https://private.example/v1/chat/completions",
        key: String = "secret-api-key",
        headers: String = """{"X-Account":"private-header-value"}""",
    ) = TextTranslationConfig(provider, model, source, target, endpoint, key, headers, 2)
}
