package org.koitharu.kotatsu.reader.data

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.domain.SourceHealthMonitor.Trigger
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Scope
import org.koitharu.kotatsu.scrobbling.common.data.MemoryPreferences

class SourceSwitchPromptStoreTest {
    @Test
    fun temporaryRulesSurviveRecreationButAreAbsentFromSettingsBackup() {
        val permanent = MemoryPreferences().apply { edit().putBoolean(AppSettings.KEY_SOURCE_SWITCH_SUGGEST, true).apply() }
        val temporary = MemoryPreferences()
        var now = 1_000L
        SourceSwitchPromptStore(permanent, temporary) { now }.mute(null, Scope.SLOW_ONLY, 3_600_000)
        val recreated = SourceSwitchPromptStore(permanent, temporary) { now }
        assertFalse(recreated.allows("source", Trigger.SLOW))
        assertTrue(recreated.allows("source", Trigger.ERRORS))
        assertTrue(permanent.getString(SourceSwitchPromptStore.KEY_PERMANENT_RULES, null)?.contains("source") != true)
        val restored = SourceSwitchPromptStore(permanent, MemoryPreferences()) { now }
        assertTrue(restored.allows("source", Trigger.SLOW))
        now += 3_600_000
        assertTrue(recreated.allows("source", Trigger.SLOW))
        assertTrue(recreated.activeRules().isEmpty())
    }

    @Test
    fun permanentRulesRestoreAndSourceResumeDoesNotClearGlobalMute() {
        val permanent = MemoryPreferences().apply { edit().putBoolean(AppSettings.KEY_SOURCE_SWITCH_SUGGEST, true).apply() }
        val temporary = MemoryPreferences()
        val store = SourceSwitchPromptStore(permanent, temporary) { 1_000 }
        store.mute(null, Scope.SLOW_ONLY, null)
        store.mute("one", Scope.ALL, null)
        store.resume("one")
        val restored = SourceSwitchPromptStore(permanent, MemoryPreferences()) { 2_000 }
        assertFalse(restored.allows("one", Trigger.SLOW))
        assertTrue(restored.allows("one", Trigger.ERRORS))
        restored.resume(null)
        assertTrue(restored.allows("one", Trigger.SLOW))
    }

    @Test
    fun replacingLegacyMutePreservesOtherSourcesAndUsesNewScope() {
        val permanent = MemoryPreferences().apply {
            edit().putBoolean(AppSettings.KEY_SOURCE_SWITCH_SUGGEST, true)
                .putStringSet(AppSettings.KEY_SOURCE_SWITCH_MUTED, setOf("one", "two")).apply()
        }
        val store = SourceSwitchPromptStore(permanent, MemoryPreferences()) { 1_000 }
        assertEquals(2, store.activeRules().size)
        store.mute("one", Scope.SLOW_ONLY, 3_600_000)
        assertTrue(store.allows("one", Trigger.ERRORS))
        assertFalse(store.allows("one", Trigger.SLOW))
        assertFalse(store.allows("two", Trigger.ERRORS))
        assertEquals(setOf("two"), permanent.getStringSet(AppSettings.KEY_SOURCE_SWITCH_MUTED, emptySet()))
        store.resume("two")
        assertTrue(store.allows("two", Trigger.ERRORS))
    }

    @Test
    fun malformedStoredRuleTypeCannotCrashReader() {
        val permanent = MemoryPreferences().apply {
            edit().putBoolean(AppSettings.KEY_SOURCE_SWITCH_SUGGEST, true)
                .putInt(SourceSwitchPromptStore.KEY_PERMANENT_RULES, 5).apply()
        }
        val store = SourceSwitchPromptStore(permanent, MemoryPreferences()) { 1_000 }
        assertTrue(store.allows("one", Trigger.SLOW))
        store.mute("one", Scope.ALL, null)
        assertFalse(store.allows("one", Trigger.ERRORS))
    }
}
