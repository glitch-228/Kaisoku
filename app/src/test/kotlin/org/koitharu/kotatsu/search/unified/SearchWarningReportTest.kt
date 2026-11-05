package org.koitharu.kotatsu.search.unified

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.HttpStatusException
import java.net.SocketException

class SearchWarningReportTest {
    @Test fun resetAnd502WarningsCopyOriginalFailuresWithoutAFakeButtonException() {
        val failures = reportedFailures()
        val report = searchWarningReport(failures)
        assertTrue(report.summary.contains("SocketException: Connection reset"))
        assertTrue(report.summary.contains("502"))
        assertTrue(report.summary.contains("yaoi-chan.me"))
        assertTrue(report.summary.contains("manga-chan.me"))
        assertTrue(report.details.contains("java.net.SocketException: Connection reset"))
        assertFalse(report.details.contains("java.lang.IllegalStateException:"))
        assertFalse(report.details.contains("SearchActivity"))
        assertFalse(report.summary.contains("at reader.Network"))
    }

    @Test fun existingSingleApplicationErrorsKeepTheirRealTypeAndOriginalStack() {
        val failure = IllegalStateException("Real source failure").apply {
            stackTrace = arrayOf(StackTraceElement("source.Parser", "parse", "Parser.kt", 42))
        }
        val report = searchWarningReport(listOf(failure))
        assertTrue(report.details.contains("java.lang.IllegalStateException: Real source failure"))
        assertTrue(report.details.contains("source.Parser.parse(Parser.kt:42)"))
    }

    @Test fun privateCredentialsQueriesFragmentsAndHeadersAreRedactedInBothViews() {
        val failure = SocketException("https://reader:password-secret@source.test/path?token=query-secret#fragment-secret\n" +
            "Authorization: Bearer header-secret\nCookie: session=cookie-secret\n" +
            "{\"api_key\":\"json-secret\"}")
        val report = searchWarningReport(listOf(failure))
        for (text in listOf(report.summary, report.details)) {
            for (secret in listOf("reader:password-secret", "query-secret", "fragment-secret", "header-secret",
                "cookie-secret", "json-secret")) assertFalse(secret, text.contains(secret))
            assertTrue(text.contains("source.test/path"))
        }
    }

    @Test fun longFailuresHaveFairBoundedSpaceIncludingTheLastSource() {
        val failures = List(16) { index -> SocketException("source-$index " + "x".repeat(100_000)) }
        val report = searchWarningReport(failures)
        assertTrue(report.summary.length <= 4096)
        assertTrue(report.details.length <= 64 * 1024)
        for (index in failures.indices) {
            assertTrue(report.summary.contains("source-$index "))
            assertTrue(report.details.contains("source-$index "))
        }
        assertTrue(report.details.contains("details truncated"))
    }

    @Test fun renderingDoesNotReplaceOrMutateTheSavedFailures() {
        val failures = reportedFailures()
        val stacks = failures.map { it.stackTrace.copyOf() }
        val messages = failures.map { it.message }
        repeat(2) { searchWarningReport(failures) }
        for (index in failures.indices) {
            assertArrayEquals(stacks[index], failures[index].stackTrace)
            assertEquals(messages[index], failures[index].message)
        }
    }

    @Test fun emptyAndExcessWarningsRespectTheExistingRecentFailureLimit() {
        assertEquals(SearchWarningReport("", ""), searchWarningReport(emptyList()))
        val report = searchWarningReport(List(20) { SocketException("warning-$it.") })
        assertFalse(report.details.contains("warning-0."))
        assertFalse(report.details.contains("warning-3."))
        assertTrue(report.details.contains("warning-4."))
        assertTrue(report.details.contains("warning-19."))
    }

    private fun reportedFailures(): List<Throwable> = listOf(
        SocketException("Connection reset").apply {
            stackTrace = arrayOf(StackTraceElement("reader.Network", "connectTls", "Network.kt", 10))
            addSuppressed(SocketException("Connection reset"))
        },
        HttpStatusException("", 502, "https://v18.yaoi-chan.me/?query=private-query"),
        HttpStatusException("", 502, "https://manga-chan.me/?query=private-query"),
    )
}
