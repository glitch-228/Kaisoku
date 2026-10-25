package org.koitharu.kotatsu.scrobbling.v2.categories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.redactSecrets
import org.koitharu.kotatsu.scrobbling.common.data.TrackerHttpException
import org.koitharu.kotatsu.scrobbling.v2.CategoryStatusDecision
import org.koitharu.kotatsu.scrobbling.v2.CategoryStatusPolicy
import org.koitharu.kotatsu.scrobbling.v2.TrackerConnection
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerProviderError
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Durable status-only decisions. Library reads finish before any provider request begins. */
@Singleton
class CategoryStatusEngine internal constructor(
    private val store: CategoryStatusStore,
    private val access: CategoryStatusAccess,
    private val nowMillis: () -> Long,
) {
    @Inject constructor(store: CategoryStatusStore, access: CategoryStatusAccess) :
        this(store, access, System::currentTimeMillis)

    val changes get() = store.changes
    private val failures = MutableStateFlow<String?>(null)
    val error = failures.asStateFlow()
    private val passes = Mutex()
    private val previews = linkedMapOf<String, CategoryStatusPreview>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var runner: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (runner?.isActive == true) return
        runner = scope.launch(Dispatchers.IO) {
            val observer = launch {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    try { access.changes.collect { wake.trySend(Unit) }; break }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { failures.value = diagnostic(error); delay(30_000) }
                }
            }
            val journalObserver = launch { store.changes.collect { wake.trySend(Unit) } }
            wake.trySend(Unit)
            try {
                for (ignored in wake) {
                    delay(300)
                    try {
                        reconcile()
                        dispatch()
                        failures.value = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures.value = diagnostic(e)
                    }
                }
            } finally {
                observer.cancel()
                journalObserver.cancel()
            }
        }
    }

    suspend fun accounts(): List<CategoryStatusAccount> = access.accounts().also { accounts ->
        require(accounts.size <= CategoryStatusLimits.ACCOUNTS &&
            accounts.map { it.connection.owner }.distinct().size == accounts.size)
    }

    suspend fun categories(): List<CategoryStatusBinding> = access.categories().also { categories ->
        require(categories.size <= CategoryStatusLimits.MAPPINGS &&
            categories.map { it.categoryId }.distinct().size == categories.size)
    }

    suspend fun view(): CategoryStatusView = withContext(Dispatchers.IO) { store.view() }

    /** Preparing/canceling a review has no durable side effects and performs no network requests. */
    suspend fun preview(
        connection: TrackerConnection,
        enabled: Boolean,
        mappings: List<CategoryStatusMapping>,
    ): CategoryStatusPreview = withContext(Dispatchers.IO) {
        val account = requireAccount(connection)
        validateMappings(account, mappings)
        val current = store.current()
        val revision = CategoryStatusRevision(Math.addExact(current.clock, 1), current.replicaId)
        val titles = snapshot(connection)
        val result = CategoryStatusPreview(UUID.randomUUID().toString(), account,
            current.configurations[connection.owner]?.revision, enabled, mappings.toList(), titles.map { title ->
                val decision = decision(enabled, mappings, title)
                CategoryStatusPreviewItem(title, CategoryStatusGuard.create(connection, title, revision),
                    (decision as? CategoryStatusDecision.Status)?.value, conflicts(decision, title))
            }, Math.addExact(nowMillis(), REVIEW_LIFETIME))
        synchronized(previews) {
            previews.entries.removeAll { it.value.expiresAtMillis < nowMillis() }
            check(previews.size < CategoryStatusLimits.ACCOUNTS) { "Close another category mapping review first" }
            previews[result.id] = result
        }
        result
    }

    fun cancelPreview(id: String) { synchronized(previews) { previews.remove(id) } }

    suspend fun confirm(id: String, selectedMangaIds: Set<Long>) = withContext(Dispatchers.IO) {
        val preview = synchronized(previews) { requireNotNull(previews[id]) { "Review expired; prepare it again" } }
        check(preview.expiresAtMillis >= nowMillis()) { "Review expired; prepare it again" }
        val account = requireAccount(preview.account.connection)
        validateMappings(account, preview.mappings)
        val titles = snapshot(account.connection)
        check(titles.associate { it.mangaId to (it.identityFingerprint() to it.membershipFingerprint()) } ==
            preview.items.associate { it.title.mangaId to
                (it.title.identityFingerprint() to it.title.membershipFingerprint()) }) {
            "Linked titles or categories changed; review the changes again"
        }
        require(selectedMangaIds.all { selected -> preview.enabled && preview.items.any {
            it.title.mangaId == selected && (it.status != null || it.conflicts.isNotEmpty())
        } })
        store.mutate { old ->
            check(old.configurations[account.connection.owner]?.revision == preview.expectedRevision) {
                "Mappings changed; review the changes again"
            }
            val owner = account.connection.owner
            val revision = CategoryStatusRevision(Math.addExact(old.clock, 1), old.replicaId)
            val config = CategoryStatusConfiguration(owner, revision, preview.enabled, preview.mappings)
            val baselines = old.baselines.filterKeys { it.owner != owner }.toMutableMap()
            val pending = retainUncertain(old.pending, owner).toMutableMap()
            for (title in titles) {
                val key = CategoryStatusKey(owner, title.mangaId)
                baselines[key] = baseline(key, title, revision)
                if (title.mangaId in selectedMangaIds) {
                    check(key !in pending) { "Check the previous uncertain send for this title first" }
                    proposal(account.connection, config, title)?.let { pending[key] = it }
                }
            }
            old.copy(clock = revision.counter, configurations = old.configurations + (owner to config),
                armed = if (preview.enabled) old.armed + (owner to revision) else old.armed - owner,
                baselines = baselines, pending = pending)
        }
        synchronized(previews) { previews.remove(id) }
        wake.trySend(Unit)
    }

    suspend fun disable(owner: TrackerOwner) = changeConfiguration(owner, deleted = false)
    suspend fun delete(owner: TrackerOwner) = changeConfiguration(owner, deleted = true)

    private suspend fun changeConfiguration(owner: TrackerOwner, deleted: Boolean) = withContext(Dispatchers.IO) {
        store.mutate { old ->
            val previous = requireNotNull(old.configurations[owner])
            val revision = CategoryStatusRevision(Math.addExact(old.clock, 1), old.replicaId)
            old.copy(clock = revision.counter, configurations = old.configurations + (owner to
                CategoryStatusConfiguration(owner, revision, false, if (deleted) emptyList() else previous.mappings,
                    deleted)), armed = old.armed - owner, pending = retainUncertain(old.pending, owner))
        }
        Unit
    }

    /** Committed invalidations only advance observed baselines; repeated additions cannot create another send. */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val accounts = accounts().associateBy { it.connection.owner }
        val initial = store.current()
        for (config in initial.configurations.values.filter(initial::isArmed)) {
            val account = accounts[config.owner] ?: continue
            val titles = snapshot(account.connection)
            store.mutate { old ->
                if (old.configurations[config.owner] != config || !old.isArmed(config)) return@mutate old
                val baselines = old.baselines.toMutableMap()
                val pending = old.pending.toMutableMap()
                val present = titles.mapTo(hashSetOf()) { it.mangaId }
                for (title in titles) {
                    val key = CategoryStatusKey(config.owner, title.mangaId)
                    val before = old.baselines[key]
                    val after = baseline(key, title, config.revision)
                    if (before == after) continue
                    baselines[key] = after
                    val prior = pending[key]
                    if (prior != null && prior.isUncertain()) {
                        pending[key] = prior.copy(state = CategoryStatusActionState.READBACK_REQUIRED,
                            error = "Local state changed after a possible send. Check before retrying.")
                        continue
                    }
                    val proposed = proposal(account.connection, config, title)
                    if (proposed == null) pending.remove(key)
                    else pending[key] = if (before == null || before.identity != after.identity ||
                        before.configuration != after.configuration) proposed.review("Review this new or changed tracker link")
                    else proposed
                }
                for ((key, action) in pending.toMap()) {
                    if (key.owner == config.owner && key.mangaId !in present && !action.isUncertain()) {
                        pending[key] = if (action.state == CategoryStatusActionState.CONFLICT) action else
                            action.review("The confirmed local tracker link is missing")
                    }
                }
                old.copy(baselines = baselines, pending = pending)
            }
        }
    }

    suspend fun resolve(id: String, status: String) = withContext(Dispatchers.IO) {
        val action = find(id)
        require(action.state == CategoryStatusActionState.CONFLICT && status in action.conflicts)
        validate(action, armed = true)
        replace(action) { it.copy(state = CategoryStatusActionState.READY, status = status,
            error = null, retryAtMillis = 0) }
        wake.trySend(Unit)
    }

    /** A manual retry revalidates the exact reviewed state; uncertain writes must first be read back. */
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        val action = find(id)
        require(action.state in setOf(CategoryStatusActionState.PAUSED, CategoryStatusActionState.REVIEW_REQUIRED))
        check(action.retryAtMillis <= nowMillis()) { "The provider cooldown has not elapsed" }
        validate(action, armed = true)
        replace(action) { it.copy(state = CategoryStatusActionState.READY, error = null) }
        wake.trySend(Unit)
    }

    suspend fun check(id: String) = withContext(Dispatchers.IO) {
        passes.withLock {
            var action = find(id)
            require(action.state == CategoryStatusActionState.READBACK_REQUIRED)
            val connection = requireNotNull(access.readbackConnection(action.guard.owner)) {
                "Verify this same tracker account before checking its previous send"
            }
            check(connection.owner == action.guard.owner)
            val title = requireNotNull(access.title(connection, action.guard.mangaId)) { "Tracker link is missing" }
            check(action.guard.matches(title)) { "Tracker link, source edition or category memberships changed" }
            if (!sameConnection(connection, action.guard.connection)) {
                action = requireNotNull(replace(action) { it.copy(guard = it.guard.copy(connection = connection)) })
            }
            readback(action, armed = false)
        }
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) {
        val action = find(id)
        check(action.state != CategoryStatusActionState.SENDING) { "Wait for the current request to finish" }
        store.mutate { old ->
            val key = action.key()
            if (old.pending[key]?.id == id) old.copy(pending = old.pending - key) else old
        }
        Unit
    }

    /** At most eight serialized actions per pass. Only READY can enter a mutating provider method. */
    suspend fun dispatch() = withContext(Dispatchers.IO) {
        passes.withLock {
            val actions = store.current().pending.values.filter {
                it.state in setOf(CategoryStatusActionState.READY, CategoryStatusActionState.READBACK_REQUIRED) &&
                    it.retryAtMillis <= nowMillis()
            }.take(CategoryStatusLimits.ACTIONS_PER_PASS)
            for (action in actions) {
                if (action.state == CategoryStatusActionState.READBACK_REQUIRED) readback(action, armed = false)
                else send(action)
            }
        }
    }

    private suspend fun send(action: CategoryStatusPending) {
        var attempted = false
        try {
            validate(action, armed = true)
            val existing = access.readback(action.guard) { validate(action, armed = true) }
            if (!matches(existing, action)) {
                replace(action) { it.review("Remote entry is missing or has changed; relink before continuing") }
                return
            }
            if (existing?.status == action.status) {
                acknowledge(action, armed = true)
                return
            }
            val sending = replace(action) { it.copy(state = CategoryStatusActionState.SENDING,
                error = null, attempts = (it.attempts + 1).coerceAtMost(10000)) } ?: return
            access.updateStatusOnly(sending.guard, requireNotNull(sending.status)) {
                validate(sending, armed = true)
                attempted = true
            }
            val uncertain = replace(sending) { it.copy(state = CategoryStatusActionState.READBACK_REQUIRED,
                error = "The status was sent. Readback is required before acknowledging it.") } ?: return
            readback(uncertain, armed = false)
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { failed(action, e, attempted) }
            throw e
        } catch (e: Exception) {
            failed(action, e, attempted)
        }
    }

    private suspend fun readback(action: CategoryStatusPending, armed: Boolean) {
        try {
            validate(action, armed)
            val entry = access.readback(action.guard) { validate(action, armed) }
            if (matches(entry, action) && entry?.status == action.status) acknowledge(action, armed)
            else replace(action) { it.copy(state = CategoryStatusActionState.REVIEW_REQUIRED,
                error = if (entry == null) "Remote entry is missing; automatic creation is disabled" else
                    "Remote status differs. Review before explicitly retrying.") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Readback failures can never turn an uncertain outcome into a mutating retry.
            replace(action) { it.copy(state = CategoryStatusActionState.READBACK_REQUIRED,
                error = diagnostic(e), retryAtMillis = cooldown(e)) }
        }
    }

    private fun failed(action: CategoryStatusPending, error: Exception, attempted: Boolean) {
        val definiteRejection = when (error) {
            is TrackerProviderError -> error.status in 400..499
            is TrackerHttpException -> error.code in 400..499
            else -> false
        }
        replace(action) { it.copy(state = if (attempted && !definiteRejection)
            CategoryStatusActionState.READBACK_REQUIRED else CategoryStatusActionState.PAUSED,
            error = diagnostic(error), retryAtMillis = cooldown(error)) }
    }

    private suspend fun validate(action: CategoryStatusPending, armed: Boolean) {
        val journal = store.current()
        val current = journal.pending[action.key()]
        check(current?.id == action.id && current.guard == action.guard && current.status == action.status &&
            current.state == action.state) { "Pending category decision changed" }
        if (armed) {
            val config = requireNotNull(journal.configurations[action.guard.owner])
            check(journal.isArmed(config) && config.revision == action.guard.configRevision) { "Mappings are disabled or changed" }
        }
        val account = if (armed) requireAccount(action.guard.connection) else {
            val readConnection = access.readbackConnection(action.guard.owner)
            check(readConnection != null && sameConnection(readConnection, action.guard.connection)) {
                "Tracker account changed; verify it and explicitly check the previous send"
            }
            null
        }
        if (armed) {
            val statuses = if (action.state == CategoryStatusActionState.CONFLICT) action.conflicts.keys else setOfNotNull(action.status)
            check(statuses.isNotEmpty() && statuses.all { status -> requireNotNull(account).statuses.any { it.value == status } }) {
                "This status is no longer supported"
            }
        }
        val title = requireNotNull(access.title(action.guard.connection, action.guard.mangaId)) { "Tracker link is missing" }
        check(action.guard.matches(title)) { "Tracker link, source edition or category memberships changed" }
    }

    private suspend fun acknowledge(action: CategoryStatusPending, armed: Boolean) {
        validate(action, armed)
        store.mutate { old ->
            if (old.pending[action.key()]?.id == action.id) old.copy(pending = old.pending - action.key()) else old
        }
    }

    private suspend fun requireAccount(connection: TrackerConnection): CategoryStatusAccount =
        requireNotNull(accounts().singleOrNull { sameConnection(it.connection, connection) }) {
            "The reviewed tracker account is disconnected or changed"
        }

    private suspend fun validateMappings(account: CategoryStatusAccount, mappings: List<CategoryStatusMapping>) {
        require(mappings.size <= CategoryStatusLimits.MAPPINGS && mappings.map { it.categoryId }.distinct().size == mappings.size)
        val categories = categories()
        require(mappings.all { mapping -> account.statuses.any { it.value == mapping.status } &&
            categories.any(mapping::matches) }) { "A category or supported tracker status changed" }
    }

    private suspend fun snapshot(connection: TrackerConnection): List<CategoryStatusTitle> =
        access.titles(connection, CategoryStatusLimits.BASELINES + 1).also { titles ->
            check(titles.size <= CategoryStatusLimits.BASELINES) { "Too many linked titles for category status review" }
            require(titles.map { it.mangaId }.distinct().size == titles.size &&
                titles.all { it.reference.owner == connection.owner })
        }

    private fun proposal(connection: TrackerConnection, config: CategoryStatusConfiguration,
        title: CategoryStatusTitle): CategoryStatusPending? {
        val decision = decision(config.requestedEnabled, config.mappings, title)
        if (decision == CategoryStatusDecision.Unmapped) return null
        return CategoryStatusPending(UUID.randomUUID().toString(), title.title,
            CategoryStatusGuard.create(connection, title, config.revision),
            if (decision is CategoryStatusDecision.Conflict) CategoryStatusActionState.CONFLICT else CategoryStatusActionState.READY,
            (decision as? CategoryStatusDecision.Status)?.value, conflicts(decision, title))
    }

    private fun decision(enabled: Boolean, mappings: List<CategoryStatusMapping>, title: CategoryStatusTitle) =
        CategoryStatusPolicy.evaluate(enabled, title.memberships.mapTo(hashSetOf()) { it.categoryId },
            mappings.filter { mapping -> title.memberships.any(mapping::matches) }.associate { it.categoryId to it.status })

    private fun conflicts(decision: CategoryStatusDecision, title: CategoryStatusTitle): Map<String, List<CategoryStatusBinding>> =
        (decision as? CategoryStatusDecision.Conflict)?.categoriesByStatus?.mapValues { (_, ids) ->
            title.memberships.filter { it.categoryId in ids }
        }.orEmpty()

    private fun baseline(key: CategoryStatusKey, title: CategoryStatusTitle, revision: CategoryStatusRevision) =
        CategoryStatusBaseline(key, title.identityFingerprint(), title.membershipFingerprint(), revision)

    private fun retainUncertain(pending: Map<CategoryStatusKey, CategoryStatusPending>, owner: TrackerOwner) =
        pending.mapNotNull { (key, action) -> when {
            key.owner != owner -> key to action
            action.isUncertain() -> key to action.copy(state = CategoryStatusActionState.READBACK_REQUIRED,
                error = "Mappings changed after a possible send. Check before retrying.")
            else -> null
        } }.toMap()

    private fun find(id: String): CategoryStatusPending = requireNotNull(store.current().pending.values.singleOrNull { it.id == id }) {
        "This pending category decision has already changed"
    }

    private fun replace(action: CategoryStatusPending, transform: (CategoryStatusPending) -> CategoryStatusPending): CategoryStatusPending? {
        var result: CategoryStatusPending? = null
        store.mutate { old ->
            val current = old.pending[action.key()]
            if (current?.id != action.id) return@mutate old
            result = transform(current)
            old.copy(pending = old.pending + (action.key() to requireNotNull(result)))
        }
        return result
    }

    private fun matches(entry: TrackerRemoteEntry?, action: CategoryStatusPending): Boolean =
        entry != null && entry.reference == action.guard.reference

    private fun cooldown(error: Exception): Long {
        val delay = when (error) {
            is TrackerProviderError -> error.cooldownMillis
            is TrackerHttpException -> error.retryAfterMillis.takeIf { it > 0 } ?: if (error.code == 429) 60_000 else 0
            else -> 0
        }.coerceIn(0, 7L * 24 * 60 * 60 * 1000)
        return if (delay == 0L) 0 else nowMillis() + delay
    }

    private fun diagnostic(error: Exception): String = when (error) {
        is TrackerProviderError -> "${error.provider}: HTTP ${error.status}; review this operation before retrying"
        is TrackerHttpException -> "Tracker HTTP ${error.code}; review this operation before retrying"
        is CancellationException -> "Request interrupted. Check the remote status before retrying."
        else -> "${error.javaClass.simpleName}: ${error.message.orEmpty().redactSecrets()}"
    }.take(2048)

    private fun CategoryStatusPending.key() = CategoryStatusKey(guard.owner, guard.mangaId)
    private fun CategoryStatusPending.isUncertain() = state in setOf(CategoryStatusActionState.SENDING,
        CategoryStatusActionState.READBACK_REQUIRED)
    private fun CategoryStatusPending.review(reason: String) = if (state == CategoryStatusActionState.CONFLICT) this else
        copy(state = CategoryStatusActionState.REVIEW_REQUIRED, error = reason)

    private fun sameConnection(a: TrackerConnection, b: TrackerConnection): Boolean =
        a.owner == b.owner && a.revision == b.revision && a.scopes == b.scopes

    private companion object {
        const val REVIEW_LIFETIME = 10 * 60 * 1000L
    }
}
