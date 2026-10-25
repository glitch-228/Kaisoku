package org.koitharu.kotatsu.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.domain.SourceHealthMonitor
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Rule
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Scope
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy.Snapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceSwitchPromptStore internal constructor(
    private val permanent: SharedPreferences,
    private val temporary: SharedPreferences,
    private val clock: () -> Long,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceManager.getDefaultSharedPreferences(context),
        context.getSharedPreferences(TEMPORARY_PREFS, Context.MODE_PRIVATE),
        System::currentTimeMillis,
    )

    private val policy = SourceSwitchPromptPolicy(clock)
    private val lock = Any()
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
        if (key == null || preferences === temporary || key in OBSERVED_KEYS) {
            mutableRevision.update { it + 1 }
        }
    }

    init {
        permanent.registerOnSharedPreferenceChangeListener(listener)
        temporary.registerOnSharedPreferenceChangeListener(listener)
    }

    fun allows(sourceName: String, trigger: SourceHealthMonitor.Trigger): Boolean =
        policy.allows(snapshot(), sourceName, trigger)

    fun activeRules(): List<Rule> = synchronized(lock) {
        val state = snapshot()
        val rules = policy.activeRules(state).associateByTo(LinkedHashMap()) { it.sourceName }
        for (source in state.legacyMutedSources) rules[source] = Rule(source, Scope.ALL, null)
        rules.values.sortedWith(compareBy<Rule> { it.sourceName != null }.thenBy { it.sourceName.orEmpty() })
    }

    fun mute(sourceName: String?, scope: Scope, durationMs: Long?) = synchronized(lock) {
        val rule = policy.createRule(sourceName, scope, durationMs)
        val state = snapshot()
        val active = policy.activeRules(state)
        require(active.map { it.sourceName }.toSet().size < SourceSwitchPromptRulesCodec.MAX_RULES ||
            active.any { it.sourceName == sourceName })
        val permanentRules = readPermanent().filter { it.sourceName != sourceName && it.isActive(clock()) }
        val temporaryRules = readTemporary().filter { it.sourceName != sourceName && it.isActive(clock()) }
        // Write the new suppression first. Interrupted cross-file cleanup must fail closed.
        if (durationMs == null) {
            permanent.edit { putString(KEY_PERMANENT_RULES, SourceSwitchPromptRulesCodec.encode(permanentRules + rule)) }
            temporary.edit { putString(KEY_TEMPORARY_RULES, SourceSwitchPromptRulesCodec.encode(temporaryRules)) }
        } else {
            temporary.edit { putString(KEY_TEMPORARY_RULES, SourceSwitchPromptRulesCodec.encode(temporaryRules + rule)) }
            permanent.edit { putString(KEY_PERMANENT_RULES, SourceSwitchPromptRulesCodec.encode(permanentRules)) }
        }
        removeLegacyMute(sourceName)
        mutableRevision.update { it + 1 }
    }

    fun resume(sourceName: String?) = synchronized(lock) {
        permanent.edit {
            putString(KEY_PERMANENT_RULES, SourceSwitchPromptRulesCodec.encode(readPermanent().filter { it.sourceName != sourceName }))
        }
        temporary.edit {
            putString(KEY_TEMPORARY_RULES, SourceSwitchPromptRulesCodec.encode(readTemporary().filter { it.sourceName != sourceName }))
        }
        removeLegacyMute(sourceName)
        mutableRevision.update { it + 1 }
    }

    private fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            enabled = permanent.getBoolean(AppSettings.KEY_SOURCE_SWITCH_SUGGEST, false),
            rules = readPermanent() + readTemporary(),
            legacyMutedSources = permanent.getStringSet(AppSettings.KEY_SOURCE_SWITCH_MUTED, emptySet()).orEmpty().toSet(),
        )
    }

    private fun readPermanent(): List<Rule> =
        SourceSwitchPromptRulesCodec.decode(runCatching { permanent.getString(KEY_PERMANENT_RULES, null) }.getOrNull())

    private fun readTemporary(): List<Rule> =
        SourceSwitchPromptRulesCodec.decode(runCatching { temporary.getString(KEY_TEMPORARY_RULES, null) }.getOrNull())

    private fun removeLegacyMute(sourceName: String?) {
        if (sourceName == null) return
        val old = permanent.getStringSet(AppSettings.KEY_SOURCE_SWITCH_MUTED, emptySet()).orEmpty()
        if (sourceName in old) permanent.edit { putStringSet(AppSettings.KEY_SOURCE_SWITCH_MUTED, old - sourceName) }
    }

    companion object {
        const val KEY_PERMANENT_RULES = "source_switch_prompt_mutes"
        const val TEMPORARY_PREFS = "_source_switch_prompt_snoozes"
        private const val KEY_TEMPORARY_RULES = "rules"
        private val OBSERVED_KEYS = setOf(
            KEY_PERMANENT_RULES,
            AppSettings.KEY_SOURCE_SWITCH_SUGGEST,
            AppSettings.KEY_SOURCE_SWITCH_MUTED,
        )
    }
}
