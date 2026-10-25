package org.koitharu.kotatsu.reader.domain

import java.util.concurrent.TimeUnit

/** Notification decisions only; never changes reader loading, retry or progress state. */
class SourceSwitchPromptPolicy(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Scope { SLOW_ONLY, ALL }

    data class Rule(
        val sourceName: String?,
        val scope: Scope,
        val expiresAt: Long?,
    ) {
        fun isActive(now: Long): Boolean = expiresAt == null || expiresAt > now

        fun appliesTo(source: String, trigger: SourceHealthMonitor.Trigger): Boolean =
            (sourceName == null || sourceName == source) &&
                (scope == Scope.ALL || trigger == SourceHealthMonitor.Trigger.SLOW)
    }

    data class Snapshot(
        val enabled: Boolean,
        val rules: List<Rule>,
        val legacyMutedSources: Set<String> = emptySet(),
    )

    fun allows(snapshot: Snapshot, sourceName: String, trigger: SourceHealthMonitor.Trigger): Boolean {
        if (!snapshot.enabled || sourceName in snapshot.legacyMutedSources) return false
        val now = clock()
        return snapshot.rules.none { it.isActive(now) && it.appliesTo(sourceName, trigger) }
    }

    fun activeRules(snapshot: Snapshot): List<Rule> = snapshot.rules.filter { it.isActive(clock()) }

    fun createRule(sourceName: String?, scope: Scope, durationMs: Long?): Rule {
        require(sourceName == null || sourceName.length in 1..MAX_SOURCE_LENGTH)
        require(durationMs == null || durationMs in MIN_DURATION_MS..MAX_DURATION_MS)
        val now = clock().coerceAtLeast(0L)
        val expiresAt = durationMs?.let {
            require(now <= Long.MAX_VALUE - it)
            now + it
        }
        return Rule(sourceName, scope, expiresAt)
    }

    companion object {
        const val MAX_SOURCE_LENGTH = 1024
        val MIN_DURATION_MS: Long = TimeUnit.HOURS.toMillis(1)
        val MAX_DURATION_MS: Long = TimeUnit.DAYS.toMillis(30)

        fun customDuration(amount: Long, days: Boolean): Long? {
            val maximum = if (days) 30L else 720L
            if (amount !in 1..maximum) return null
            return if (days) TimeUnit.DAYS.toMillis(amount) else TimeUnit.HOURS.toMillis(amount)
        }
    }
}
