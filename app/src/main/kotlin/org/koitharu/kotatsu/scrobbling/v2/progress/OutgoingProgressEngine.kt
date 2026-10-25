package org.koitharu.kotatsu.scrobbling.v2.progress

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.scrobbling.common.data.TrackerHttpException
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerProviderError
import org.koitharu.kotatsu.scrobbling.v2.TrackerRawProgress
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Only confirmed reader events enqueue automatically. Dispatch never retransmits an uncertain body. */
@Singleton
class OutgoingProgressEngine internal constructor(
    private val store: OutgoingProgressStore,
    private val access: OutgoingProgressAccess,
    private val now: () -> Long,
) {
    @Inject constructor(store: OutgoingProgressStore, access: OutgoingProgressAccess) :
        this(store, access, System::currentTimeMillis)

    val changes get() = store.changes
    private val dispatches = Mutex()
    private val previews = linkedMapOf<String, OutgoingProgressPreview>()

    suspend fun view(): OutgoingProgressView = withContext(Dispatchers.IO) {
        store.current().let { OutgoingProgressView(it.consents, it.lanes, it.cooldowns) }
    }

    suspend fun accounts(): List<OutgoingProgressAccount> = access.accounts().also {
        require(it.size <= 32 && it.map { account -> account.connection.owner() }.distinct().size == it.size)
    }

    suspend fun setAutomatic(connection: OutgoingProgressConnection, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            if (enabled) check(requireAccount(connection).writable) { "Verify progress write access first" }
            store.mutate { before ->
                val revision = Math.addExact(before.clock, 1)
                val consent = OutgoingProgressConsent(connection, revision, enabled)
                before.copy(clock = revision, consents = before.consents.filterNot {
                    it.connection.owner() == connection.owner()
                } + consent, lanes = before.lanes.map { lane ->
                    if (lane.owner() != connection.owner()) lane else lane.copy(
                        primary = lane.primary?.let { action ->
                            if (action.origin == OutgoingProgressOrigin.AUTOMATIC && !action.uncertain) action.pause(
                                "Automatic consent changed; review this unsent progress before continuing",
                            ) else action
                        },
                        later = lane.later?.pause("Automatic consent changed; review this later completion"),
                    )
                })
            }
            Unit
        }

    /** NOT_ENABLED permits root's existing legacy workflow; every other result is handled without a legacy echo. */
    suspend fun enqueueCompletion(connection: OutgoingProgressConnection, mangaId: Long,
        completion: OutgoingProgressCompletion): OutgoingProgressEnqueue = withContext(Dispatchers.IO) {
        val consent = store.current().consents.singleOrNull { it.connection.owner() == connection.owner() }
        if (consent?.automatic != true || !consent.connection.sameSession(connection)) {
            return@withContext OutgoingProgressEnqueue.NOT_ENABLED
        }
        val snapshot = access.snapshot(connection, mangaId) ?: return@withContext OutgoingProgressEnqueue.REVIEW_REQUIRED
        val guard = OutgoingProgressGuard(connection, snapshot, completion)
        var action = OutgoingProgressAction(uuid(), guard, OutgoingProgressFields(number = completion.number),
            OutgoingProgressOrigin.AUTOMATIC, consent.revision)
        try {
            requireAccount(connection).check(action.fields)
            access.validate(guard)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { action = action.pause(diagnostic(e)) }
        var result = if (action.state == OutgoingProgressState.PAUSED) OutgoingProgressEnqueue.REVIEW_REQUIRED else
            OutgoingProgressEnqueue.ENQUEUED
        store.mutate { before ->
            check(before.consents.any { it == consent }) { "Automatic consent changed during enqueue" }
            val existing = before.lanes.singleOrNull { it.matches(guard) }
            val lane = existing ?: OutgoingProgressLane(connection.provider, connection.accountId, mangaId)
            val acknowledged = lane.acknowledgment
            if (lane.primary == null && acknowledged != null &&
                acknowledged.action.guard.snapshot.sameEvidence(snapshot, ignorePosition = true) &&
                acknowledged.action.guard.connection.sameSession(connection) &&
                known(acknowledged.observed?.number ?: acknowledged.action.fields.number)?.let {
                    it >= decimal(completion.number)
                } == true) {
                result = OutgoingProgressEnqueue.ALREADY_OBSERVED
                return@mutate before
            }
            val primary = lane.primary
            val next = when {
                primary == null -> lane.copy(primary = action)
                primary.origin == OutgoingProgressOrigin.AUTOMATIC && primary.state == OutgoingProgressState.QUEUED &&
                    !primary.uncertain -> lane.copy(primary = coalesce(primary, action))
                else -> lane.copy(later = lane.later?.let { coalesce(it, action) } ?: action)
            }
            if (next == lane) result = OutgoingProgressEnqueue.ALREADY_OBSERVED
            before.withLane(next)
        }
        result
    }

    /** Explicit manual preparation performs a fresh read, but persists no consent or pending mutation. */
    suspend fun preview(connection: OutgoingProgressConnection, mangaId: Long,
        fields: OutgoingProgressFields): OutgoingProgressPreview = withContext(Dispatchers.IO) {
        requireAccount(connection).check(fields)
        checkCooldown(connection.owner())
        val guard = OutgoingProgressGuard(connection,
            requireNotNull(access.snapshot(connection, mangaId)) { "Choose a confirmed source edition and tracker link" })
        access.validate(guard)
        val remote = access.readback(guard) { access.validate(guard); requireAccount(connection).check(fields) }
        require(matches(guard, remote)) { "The remote list entry is missing or changed; progress cannot create it" }
        val value = OutgoingProgressRemote.from(remote?.progress)
        val requirements = reviewRequirements(fields, value)
        val result = OutgoingProgressPreview(uuid(), guard, fields, value, requirements.first, requirements.second,
            Math.addExact(now(), 10 * 60_000L))
        synchronized(previews) {
            previews.entries.removeAll { it.value.expiresAt < now() }
            check(previews.size < 32) { "Close another progress review first" }
            previews[result.id] = result
        }
        result
    }

    fun cancelPreview(id: String) { synchronized(previews) { previews.remove(id) } }

    suspend fun confirm(previewId: String, allowLower: Boolean = false, allowUnknown: Boolean = false): Unit =
        withContext(Dispatchers.IO) {
            val preview = synchronized(previews) { requireNotNull(previews[previewId]) { "Prepare this progress review again" } }
            check(preview.expiresAt >= now()) { "Progress review expired" }
            require(!preview.requiresLowerConsent || allowLower) { "Confirm the lower remote value explicitly" }
            require(!preview.requiresUnknownConsent || allowUnknown) { "Confirm replacing unknown progress explicitly" }
            requireAccount(preview.guard.connection).check(preview.fields)
            access.validate(preview.guard)
            val action = OutgoingProgressAction(uuid(), preview.guard, preview.fields, OutgoingProgressOrigin.MANUAL,
                null, reviewedRemote = preview.remote, allowLower = allowLower, allowUnknown = allowUnknown)
            store.mutate { before ->
                val lane = before.lanes.singleOrNull { it.matches(preview.guard) } ?: OutgoingProgressLane(
                    preview.guard.connection.provider, preview.guard.connection.accountId, preview.guard.snapshot.mangaId,
                )
                check(lane.primary == null && lane.later == null) { "Check or discard this title's pending progress first" }
                before.withLane(lane.copy(primary = action))
            }
            synchronized(previews) { previews.remove(previewId) }
            Unit
        }

    /** A pass performs at most eight queued bodies; uncertain actions require the separate explicit Check API. */
    suspend fun dispatch(owner: TrackerOwner? = null): Int = withContext(Dispatchers.IO) {
        dispatches.withLock {
            val pending = store.current().lanes.mapNotNull { it.primary }.filter {
                it.state == OutgoingProgressState.QUEUED && (owner == null || it.guard.connection.owner() == owner)
            }
            val stopped = hashSetOf<TrackerOwner>()
            var processed = 0
            for (action in pending) {
                if (processed >= 8) break
                if (action.guard.connection.owner() in stopped || cooldown(action.guard.connection.owner()) > now()) continue
                if (blockedByUncertainty(action)) continue
                val failure = send(action)
                processed++
                if (failure != null && stopsAccount(failure)) stopped += action.guard.connection.owner()
            }
            processed
        }
    }

    /** Reconnection may update only the same raw owner and only for this explicit readback operation. */
    suspend fun check(actionId: String, reconnected: OutgoingProgressConnection? = null): Unit =
        withContext(Dispatchers.IO) {
            dispatches.withLock {
                var action = find(actionId)
                require(action.uncertain || action.requiresReview) { "This operation does not need recovery readback" }
                if (reconnected != null) {
                    require(reconnected.owner() == action.guard.connection.owner()) { "A recovery cannot move to another account" }
                    requireAccount(reconnected, writable = false)
                    val guard = action.guard.copy(connection = reconnected)
                    action = replace(action) { it.copy(guard = guard) } ?: return@withLock
                }
                checkCooldown(action.guard.connection.owner())
                try {
                    validateRecovery(action)
                    val remote = access.recoveryReadback(action.guard) { validateRecovery(action) }
                    if (matches(action.guard, remote) && satisfied(action, OutgoingProgressRemote.from(remote?.progress))) {
                        acknowledge(action, OutgoingProgressRemote.from(remote?.progress), armed = false, recovery = true)
                    } else replace(action) { it.copy(state = OutgoingProgressState.PAUSED, uncertain = false,
                        requiresReview = true, reviewedRemote = OutgoingProgressRemote.from(remote?.progress),
                        error = "Remote progress differs or is missing. Prepare a fresh manual review before any resend") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    replace(action) { it.copy(state = if (it.uncertain) OutgoingProgressState.READBACK_REQUIRED else
                        OutgoingProgressState.PAUSED, error = diagnostic(e)) }
                    failAccount(action.guard.connection.owner(), e)
                }
            }
        }

    suspend fun retry(actionId: String): Unit = withContext(Dispatchers.IO) {
        val action = find(actionId)
        require(action.state == OutgoingProgressState.PAUSED && !action.uncertain && !action.requiresReview) {
            "Check the uncertain request or prepare a fresh manual review"
        }
        checkCooldown(action.guard.connection.owner())
        validate(action, armed = true)
        replace(action) { it.copy(state = OutgoingProgressState.QUEUED, error = null) }
        Unit
    }

    suspend fun discard(actionId: String): Unit = withContext(Dispatchers.IO) {
        val action = find(actionId)
        check(!action.uncertain && action.state != OutgoingProgressState.SENDING) {
            "Check the possible remote send before discarding it"
        }
        store.mutate { before ->
            val lane = before.lanes.single { it.matches(action.guard) }
            check(lane.primary?.id == action.id) { "The pending request changed" }
            before.withLane(lane.copy(primary = lane.later, later = null))
        }
        Unit
    }

    private suspend fun send(action: OutgoingProgressAction): Exception? {
        var mutationEntered = false
        var current = action
        try {
            validate(current, armed = true)
            val remote = access.readback(current.guard) { validate(current, armed = true) }
            if (!matches(current.guard, remote)) {
                replace(current) { it.pause("Remote list entry is missing or changed; progress never creates an entry") }
                return null
            }
            val observed = OutgoingProgressRemote.from(remote?.progress)
            when (decision(current, observed)) {
                Decision.SATISFIED -> { acknowledge(current, observed, armed = true); return null }
                Decision.REVIEW -> {
                    replace(current) { it.pause("Unknown, lower or changed remote progress requires a fresh manual review") }
                    return null
                }
                Decision.SEND -> Unit
            }
            current = replace(current) { it.copy(state = OutgoingProgressState.SENDING, uncertain = true) } ?: return null
            mutationEntered = true
            access.updateProgressOnly(current.guard, current.fields, observed, current.allowLower, current.allowUnknown) {
                validate(current, armed = true)
            }
            current = replace(current) { it.copy(state = OutgoingProgressState.READBACK_REQUIRED, uncertain = true) } ?: return null
            validateRecovery(current)
            val verified = access.recoveryReadback(current.guard) { validateRecovery(current) }
            val progress = OutgoingProgressRemote.from(verified?.progress)
            if (matches(current.guard, verified) && satisfied(current, progress)) {
                acknowledge(current, progress, armed = false, recovery = true)
            }
            else replace(current) { it.copy(error = "Progress was sent; explicit readback review is required") }
            return null
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { failed(current, e, mutationEntered) }
            throw e
        } catch (e: Exception) {
            failed(current, e, mutationEntered)
            return e
        }
    }

    private fun failed(action: OutgoingProgressAction, error: Exception, mutationEntered: Boolean) {
        replace(action) { current ->
            if (mutationEntered || current.uncertain) current.copy(state = OutgoingProgressState.READBACK_REQUIRED,
                uncertain = true, error = diagnostic(error))
            else current.copy(state = OutgoingProgressState.PAUSED, error = diagnostic(error))
        }
        failAccount(action.guard.connection.owner(), error)
    }

    private suspend fun validate(action: OutgoingProgressAction, armed: Boolean) {
        val journal = store.current()
        val current = journal.lanes.singleOrNull { it.matches(action.guard) }?.primary
        check(current == action) { "Pending progress changed" }
        requireAccount(action.guard.connection, writable = armed).let { if (armed) it.check(action.fields) }
        if (armed && action.origin == OutgoingProgressOrigin.AUTOMATIC) {
            check(journal.consents.any { it.automatic && it.revision == action.consentRevision &&
                it.connection.sameSession(action.guard.connection) }) { "Automatic progress consent is disabled or changed" }
        }
        if (armed) check(!blockedByUncertainty(action)) { "Check the uncertain request for this remote work first" }
        access.validate(action.guard)
    }

    private suspend fun requireAccount(connection: OutgoingProgressConnection, writable: Boolean = true): OutgoingProgressAccount {
        val account = requireNotNull(accounts().singleOrNull { it.connection.sameSession(connection) }) {
            "The verified tracker account or session changed"
        }
        require(!writable || account.writable) { "This account is read-only; verify write access" }
        return account
    }

    private suspend fun validateRecovery(action: OutgoingProgressAction) {
        check(store.current().lanes.singleOrNull { it.matches(action.guard) }?.primary == action) {
            "Pending recovery request changed"
        }
        requireAccount(action.guard.connection, writable = false)
    }

    private suspend fun acknowledge(action: OutgoingProgressAction, remote: OutgoingProgressRemote?, armed: Boolean,
        recovery: Boolean = false) {
        if (recovery) validateRecovery(action) else validate(action, armed)
        store.mutate { before ->
            val lane = before.lanes.single { it.matches(action.guard) }
            if (lane.primary != action) return@mutate before
            before.withLane(lane.copy(primary = lane.later, later = null, acknowledgment = OutgoingProgressAcknowledgment(
                action.copy(state = OutgoingProgressState.ACKNOWLEDGED, uncertain = false, error = null), remote, now(),
            )))
        }
    }

    private fun find(id: String) = requireNotNull(store.current().lanes.mapNotNull { it.primary }.singleOrNull { it.id == id }) {
        "This pending progress operation changed"
    }

    private fun replace(action: OutgoingProgressAction, transform: (OutgoingProgressAction) -> OutgoingProgressAction):
        OutgoingProgressAction? {
        var result: OutgoingProgressAction? = null
        store.mutate { before ->
            val lane = before.lanes.singleOrNull { it.matches(action.guard) } ?: return@mutate before
            val current = lane.primary ?: return@mutate before
            if (current.id != action.id) return@mutate before
            result = transform(current)
            before.withLane(lane.copy(primary = result))
        }
        return result
    }

    private fun coalesce(previous: OutgoingProgressAction, next: OutgoingProgressAction): OutgoingProgressAction {
        check(previous.origin == OutgoingProgressOrigin.AUTOMATIC && !previous.uncertain)
        if (previous.guard.connection.sameSession(next.guard.connection) &&
            previous.guard.snapshot.sameEvidence(next.guard.snapshot, ignorePosition = true) &&
            previous.consentRevision == next.consentRevision &&
            decimal(requireNotNull(previous.fields.number)) >= decimal(requireNotNull(next.fields.number))) return previous
        return next
    }

    private enum class Decision { SATISFIED, SEND, REVIEW }

    private fun reviewRequirements(fields: OutgoingProgressFields, remote: OutgoingProgressRemote?): Pair<Boolean, Boolean> {
        var lower = false
        var unknown = remote?.unit != "chapter"
        for ((desired, observed) in listOf(fields.number to remote?.number, fields.volume to remote?.volume)) {
            if (desired == null) continue
            val current = known(observed)
            if (current == null) unknown = true else if (current > decimal(desired)) lower = true
        }
        if (fields.entryId != null && fields.entryId != remote?.entryId) unknown = true
        return lower to unknown
    }

    private fun decision(action: OutgoingProgressAction, remote: OutgoingProgressRemote?): Decision {
        val fields = action.fields
        var satisfied = true
        var retainedHigher = false
        val requirements = reviewRequirements(fields, remote)
        if (requirements.second && (!action.allowUnknown || !sameSelected(fields, action.reviewedRemote, remote))) {
            return Decision.REVIEW
        }
        for ((desired, observed) in listOf(fields.number to remote?.number, fields.volume to remote?.volume)) {
            if (desired == null) continue
            val current = known(observed)
            val target = decimal(desired)
            when {
                current == null -> satisfied = false
                current == target -> Unit
                current > target && (action.origin == OutgoingProgressOrigin.AUTOMATIC || !action.allowLower) ->
                    retainedHigher = true
                current > target -> {
                    if (!sameSelected(fields, action.reviewedRemote, remote)) return Decision.REVIEW
                    satisfied = false
                }
                else -> satisfied = false
            }
        }
        if (fields.entryId != null && fields.entryId != remote?.entryId) satisfied = false
        if (satisfied) return Decision.SATISFIED
        return if (retainedHigher) Decision.REVIEW else Decision.SEND
    }

    private fun satisfied(action: OutgoingProgressAction, remote: OutgoingProgressRemote?): Boolean =
        decision(action, remote) == Decision.SATISFIED

    private fun sameSelected(fields: OutgoingProgressFields, before: OutgoingProgressRemote?,
        after: OutgoingProgressRemote?): Boolean = before?.unit == after?.unit &&
        (fields.number == null || before?.number == after?.number) &&
        (fields.volume == null || before?.volume == after?.volume) &&
        (fields.entryId == null || before?.entryId == after?.entryId)

    private fun matches(guard: OutgoingProgressGuard, remote: TrackerRemoteEntry?) = remote?.reference == guard.reference()
    private fun known(value: String?): BigDecimal? = value?.let { TrackerRawProgress(it).numericNumber() }
        ?.takeIf { it.signum() >= 0 }
    private fun cooldown(owner: TrackerOwner) = store.current().cooldowns.singleOrNull {
        it.provider == owner.provider && it.accountId == owner.accountId
    }?.until ?: 0
    private fun blockedByUncertainty(action: OutgoingProgressAction): Boolean = store.current().lanes.any { lane ->
        lane.primary?.let { other -> other.id != action.id && other.uncertain &&
            other.guard.connection.owner() == action.guard.connection.owner() &&
            other.guard.snapshot.mediaId == action.guard.snapshot.mediaId } == true
    }
    private fun checkCooldown(owner: TrackerOwner) { check(cooldown(owner) <= now()) { "Provider cooldown is active" } }

    private fun failAccount(owner: TrackerOwner, error: Exception) {
        val delay = when (error) {
            is TrackerProviderError -> error.cooldownMillis.takeIf { it > 0 } ?: if (error.status == 429) 60_000L else 0L
            is TrackerHttpException -> error.retryAfterMillis.takeIf { it > 0 } ?: if (error.code == 429) 60_000L else 0L
            else -> 0L
        }.coerceIn(0, 7L * 24 * 60 * 60 * 1000)
        val value = OutgoingProgressCooldown(owner.provider, owner.accountId, if (delay == 0L) 0 else now() + delay,
            diagnostic(error))
        store.mutate { before -> before.copy(cooldowns = before.cooldowns.filterNot {
            it.provider == owner.provider && it.accountId == owner.accountId
        } + value) }
    }

    private fun stopsAccount(error: Exception) = error is TrackerProviderError && error.status in setOf(401, 403, 429) ||
        error is TrackerHttpException && error.code in setOf(401, 403, 429)
    private fun diagnostic(error: Exception): String = when (error) {
        is TrackerProviderError -> error.provider + ": HTTP " + error.status + "; verify access or wait for cooldown"
        is TrackerHttpException -> "Tracker HTTP " + error.code + "; verify access or wait for cooldown"
        is CancellationException -> "Progress request interrupted; check its remote outcome"
        else -> error.javaClass.simpleName + ": progress could not finish; review the preserved request"
    }.take(2048)
    private fun OutgoingProgressAction.pause(reason: String) = copy(state = OutgoingProgressState.PAUSED,
        requiresReview = true, error = reason)
    private fun OutgoingProgressLane.owner() = TrackerOwner(provider, accountId)
    private fun OutgoingProgressLane.matches(guard: OutgoingProgressGuard) =
        owner() == guard.connection.owner() && mangaId == guard.snapshot.mangaId
    private fun OutgoingProgressJournal.withLane(lane: OutgoingProgressLane) =
        copy(lanes = lanes.filterNot { it.provider == lane.provider && it.accountId == lane.accountId &&
            it.mangaId == lane.mangaId } + lane)
    private fun uuid() = UUID.randomUUID().toString()
}
