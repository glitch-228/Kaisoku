package org.koitharu.kotatsu.core.parser.lnreader

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LNReaderHostJsonTest {
    @Test fun hostJsonEscapesSurrogatesWithoutChangingThePluginSerializer() {
        val report = runScript("""
            var value = {text: '🤖中文\n"quoted"', lone: '\ud800'};
            var encoded = __lnreaderStringifyForHost(value);
            console.log(JSON.stringify({encoded: encoded,
                roundTrip: JSON.parse(encoded).text === value.text && JSON.parse(encoded).lone === value.lone,
                ordinary: JSON.stringify({text: '🤖'}) === '{"text":"🤖"}'}));
        """.trimIndent())
        val encoded = report.getString("encoded")
        assertTrue(encoded.contains("\\ud83e\\udd16"))
        assertFalse(encoded.contains("🤖"))
        assertTrue(encoded.contains("中文"))
        assertTrue(report.getBoolean("roundTrip"))
        assertTrue(report.getBoolean("ordinary"))
    }

    @Test fun largeHostJsonIsLosslessAndRetainsNullAndUndefinedSemantics() {
        val report = runScript("""
            var value = {text: '中文🤖'.repeat(20000)};
            var encoded = __lnreaderStringifyForHost(value);
            console.log(JSON.stringify({length: JSON.parse(encoded).text.length,
                roundTrip: JSON.parse(encoded).text === value.text,
                missing: __lnreaderStringifyForHost(undefined) === undefined,
                nullValue: __lnreaderStringifyForHost(null) === 'null'}));
        """.trimIndent())
        assertEquals(80000, report.getInt("length"))
        assertTrue(report.getBoolean("roundTrip"))
        assertTrue(report.getBoolean("missing"))
        assertTrue(report.getBoolean("nullValue"))
    }

    @Test fun requestJsonAndSupplementaryUrlCharactersCrossTheHostSafely() {
        val report = runScript("""
            var captured, calls = 0;
            globalThis.__nativeFetch = function(url, init) {
                calls++; captured = {url: url, encoded: init, init: JSON.parse(init)};
                return JSON.stringify({ok: true, status: 200, text: 'ok'});
            };
            (async function() {
                await fetchApi('https://example.test/🤖?q=🤖&encoded=a%20b', {method: 'POST', body: '🤖'});
                var rejected = false;
                try { await fetchApi('https://example.test/\ud800'); } catch(e) { rejected = true; }
                console.log(JSON.stringify({captured: captured, calls: calls, rejected: rejected}));
            })();
        """.trimIndent())
        val captured = report.getJSONObject("captured")
        assertEquals("https://example.test/%F0%9F%A4%96?q=%F0%9F%A4%96&encoded=a%20b", captured.getString("url"))
        assertFalse(captured.getString("encoded").contains("🤖"))
        assertEquals("🤖", captured.getJSONObject("init").getString("body"))
        assertEquals(1, report.getInt("calls"))
        assertTrue(report.getBoolean("rejected"))
    }

    private fun runScript(script: String): JSONObject {
        val fetch = LNReaderFetchBridge(OkHttpClient(), "json-test")
        val engine = LNReaderEngine(fetch)
        val process = try {
            ProcessBuilder("node", "-e", fetch.toJavaScriptFunction() + "\n" + engine.globalPolyfillsScript() + "\n" + script).start()
        } catch (_: java.io.IOException) {
            assumeTrue("Node is required for host JSON compatibility checks", false)
            error("Node unavailable")
        }
        try {
            assertTrue("Host JSON check timed out", process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
            return JSONObject(process.inputStream.bufferedReader().readText())
        } finally {
            process.destroyForcibly()
        }
    }
}
