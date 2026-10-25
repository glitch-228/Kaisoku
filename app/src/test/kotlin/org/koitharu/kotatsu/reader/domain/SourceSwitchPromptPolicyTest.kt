package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.reader.data.SourceSwitchPromptRulesCodec
import org.koitharu.kotatsu.reader.domain.SourceHealthMonitor.Trigger
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Rule
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Scope
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Snapshot
import java.util.concurrent.TimeUnit

class SourceSwitchPromptPolicyTest {
    private var now = 1_000L
    private val policy = SourceSwitchPromptPolicy { now }

    @Test
    fun slowOnlyKeepsFailuresAndOtherSourcesAvailable() {
        val snapshot = Snapshot(true, listOf(Rule("one", Scope.SLOW_ONLY, null)))
        assertFalse(policy.allows(snapshot, "one", Trigger.SLOW))
        assertTrue(policy.allows(snapshot, "one", Trigger.ERRORS))
        assertTrue(policy.allows(snapshot, "two", Trigger.SLOW))
    }

    @Test
    fun globalAndSourceRulesCombineWithoutSourceOverride() {
        val global = Rule(null, Scope.SLOW_ONLY, null)
        val source = Rule("one", Scope.ALL, null)
        val snapshot = Snapshot(true, listOf(global, source))
        for (trigger in Trigger.entries) assertFalse(policy.allows(snapshot, "one", trigger))
        assertFalse(policy.allows(snapshot.copy(rules = listOf(global)), "one", Trigger.SLOW))
        assertTrue(policy.allows(snapshot, "two", Trigger.ERRORS))
    }

    @Test
    fun exactExpiryPermitsFutureEventsWithoutChangingMonitorState() {
        val rule = policy.createRule(null, Scope.ALL, TimeUnit.HOURS.toMillis(1))
        val snapshot = Snapshot(true, listOf(rule))
        now = rule.expiresAt!! - 1
        assertFalse(policy.allows(snapshot, "one", Trigger.SLOW))
        now++
        assertTrue(policy.allows(snapshot, "one", Trigger.SLOW))
        val monitor = SourceHealthMonitor()
        assertEquals(Trigger.SLOW, monitor.onSlowLoad(true))
        now += TimeUnit.DAYS.toMillis(2)
        assertNull(monitor.onSlowLoad(true))
    }

    @Test
    fun legacyAndMasterSwitchRemainAuthoritative() {
        val snapshot = Snapshot(true, emptyList(), setOf("one"))
        for (trigger in Trigger.entries) {
            assertFalse(policy.allows(snapshot, "one", trigger))
            assertFalse(policy.allows(snapshot.copy(enabled = false), "two", trigger))
        }
    }

    @Test
    fun permanentAndTimedRulesRoundTripAcrossStoreRecreation() {
        val original = listOf(Rule(null, Scope.SLOW_ONLY, null), policy.createRule("mihon:example/123", Scope.ALL, 3_600_000))
        val restored = SourceSwitchPromptRulesCodec.decode(SourceSwitchPromptRulesCodec.encode(original))
        assertEquals(original, restored)
        assertFalse(policy.allows(Snapshot(true, restored), "mihon:example/123", Trigger.ERRORS))
        now = restored.last().expiresAt!!
        assertTrue(policy.allows(Snapshot(true, restored), "mihon:example/123", Trigger.ERRORS))
        assertFalse(policy.allows(Snapshot(true, restored), "another", Trigger.SLOW))
    }

    @Test
    fun malformedEntriesCannotBecomePermanentMutes() {
        val value = """{"version":1,"rules":[
            {"source":null,"scope":"ALL"},
            {"source":"bad","scope":"ALL","expiresAt":"tomorrow"},
            {"source":"negative","scope":"ALL","expiresAt":-1},
            {"source":"valid","scope":"SLOW_ONLY","expiresAt":null}
        ]}"""
        assertEquals(listOf(Rule("valid", Scope.SLOW_ONLY, null)), SourceSwitchPromptRulesCodec.decode(value))
        assertTrue(SourceSwitchPromptRulesCodec.decode("not json").isEmpty())
        assertTrue(SourceSwitchPromptRulesCodec.decode("{\"version\":2,\"rules\":[]}").isEmpty())
        assertTrue(SourceSwitchPromptRulesCodec.decode("x".repeat(256 * 1024 + 1)).isEmpty())
    }

    @Test
    fun customDurationsRejectOverflowAndOutOfRange() {
        assertEquals(TimeUnit.DAYS.toMillis(30), SourceSwitchPromptPolicy.customDuration(30, true))
        assertEquals(TimeUnit.HOURS.toMillis(720), SourceSwitchPromptPolicy.customDuration(720, false))
        for (n in listOf(-1L, 0L, Long.MAX_VALUE)) {
            assertNull(SourceSwitchPromptPolicy.customDuration(n, true))
            assertNull(SourceSwitchPromptPolicy.customDuration(n, false))
        }
        assertNull(SourceSwitchPromptPolicy.customDuration(31, true))
        assertNull(SourceSwitchPromptPolicy.customDuration(721, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRuleDurationIsNotPersistable() {
        policy.createRule("one", Scope.ALL, Long.MAX_VALUE)
    }

    @Test
    fun asynchronousDiscoveryMustRecheckChangedPolicyAndTitle() {
        val started = Snapshot(true, emptyList())
        assertTrue(policy.allows(started, "one", Trigger.SLOW))
        val changed = started.copy(rules = listOf(Rule(null, Scope.ALL, null)))
        assertFalse(policy.allows(changed, "one", Trigger.SLOW))
        assertTrue(policy.allows(started, "two", Trigger.SLOW))
    }
}
