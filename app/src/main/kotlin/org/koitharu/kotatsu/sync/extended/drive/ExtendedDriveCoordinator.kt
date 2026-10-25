package org.koitharu.kotatsu.sync.extended.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Every create is immutable and journaled; only explicit Resume attempts an unknown publication/cleanup. */
internal class ExtendedDriveCoordinator(
    private val store: ExtendedDriveStore,
    private val api: ExtendedDriveTransport,
    private val payloads: ExtendedDrivePayloads,
) {
    suspend fun sync(session: ExtendedDriveSession, resume: Boolean = false,
        automatic: Boolean = false): ExtendedDriveResult = store.withWriter {
        val configuration = store.configuration()
        if (!configuration.enabled || automatic && !configuration.automatic) {
            return@withWriter ExtendedDriveResult(ExtendedDriveStatus.DISABLED)
        }
        if (configuration.permissionId != session.permissionId) {
            return@withWriter ExtendedDriveResult(ExtendedDriveStatus.STALE_SESSION)
        }
        val context = currentCoroutineContext()
        val guard = {
            context.ensureActive()
            session.checkCurrent()
            val now = store.configuration()
            check(now.enabled && now.generation == configuration.generation && now.permissionId == session.permissionId) {
                "Drive account or consent changed"
            }
            context.ensureActive()
        }
        val scoped = ExtendedDriveSession(session.permissionId, session.token, guard)
        val statuses = linkedMapOf<String, ExtendedDriveStatus>()
        try {
            guard()
            check(api.user(session.token, guard).permissionId == session.permissionId) { "Drive account changed" }
            val identity = store.identity()
            for (namespace in configuration.namespaces.sorted()) {
                guard()
                val pending = store.publication(session.permissionId, namespace) ?: continue
                if (!resume) statuses[namespace] = ExtendedDriveStatus.RECOVERY_REQUIRED
                else try {
                    recover(scoped, pending, identity, guard)
                    statuses[namespace] = ExtendedDriveStatus.SUCCESS
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { guard(); statuses[namespace] = classify(error) }
            }
            if (!resume) {
                val files = api.list(scoped)
                validateLanes(files)
                store.pruneApplied(files.mapTo(hashSetOf()) { it.id }, guard)
                for (namespace in configuration.namespaces.sorted()) {
                    if (namespace in statuses) continue
                    if (namespace !in payloads.namespaces) {
                        statuses[namespace] = ExtendedDriveStatus.REVIEW_REQUIRED
                        continue
                    }
                    try {
                        val selected = files.filter { it.descriptor.namespace == namespace }
                        val own = store.owned(session.permissionId, namespace)
                        val visibleOwn = selected.filter { it.descriptor.deviceId == identity.deviceId }
                        check(visibleOwn.all { it.descriptor.writer == identity.writer && it.id == own?.id }) {
                            "Unexpected own-device Drive snapshot requires review"
                        }
                        check(own == null || visibleOwn.singleOrNull() == own) { "Own Drive snapshot is missing or changed" }
                        for (lane in selected.groupBy { it.descriptor.deviceId }.values) mergeLane(scoped, lane, guard)
                        val previousBytes = own?.let { api.download(scoped, it) }
                        if (previousBytes != null) payloads.merge(namespace, previousBytes, guard)
                        val candidate = payloads.export(namespace, guard)
                        if (own?.descriptor?.sha256 == ExtendedDriveProtocol.hash(candidate)) {
                            statuses[namespace] = ExtendedDriveStatus.SUCCESS
                            continue
                        }
                        if (previousBytes != null) {
                            payloads.validateSuccessor(namespace, previousBytes, candidate)
                            payloads.merge(namespace, previousBytes, guard)
                            val repeated = payloads.export(namespace, guard)
                            check(repeated.contentEquals(candidate)) { "Drive successor is not a stable causal fixed point" }
                        }
                        guard()
                        val id = api.generateId(scoped)
                        val generation = own?.descriptor?.generation?.let { Math.addExact(it, 1L) } ?: 1L
                        val descriptor = ExtendedDriveDescriptor(session.permissionId, identity.deviceId, identity.writer,
                            namespace, generation, ExtendedDriveProtocol.hash(candidate), candidate.size,
                            own?.id, own?.descriptor?.sha256)
                        val pending = ExtendedDrivePublication(id, descriptor, candidate, own)
                        store.prepare(pending, guard)
                        try {
                            publish(scoped, pending, identity, guard)
                            statuses[namespace] = ExtendedDriveStatus.SUCCESS
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            guard()
                            statuses[namespace] = if (error is ReviewRequired) ExtendedDriveStatus.REVIEW_REQUIRED
                                else ExtendedDriveStatus.RECOVERY_REQUIRED
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { guard(); statuses[namespace] = classify(error) }
                }
            }
            guard()
            val result = ExtendedDriveResult(if (statuses.values.all { it == ExtendedDriveStatus.SUCCESS }) {
                ExtendedDriveStatus.SUCCESS
            } else ExtendedDriveStatus.PARTIAL, statuses)
            store.result(result, guard)
            result
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            try { guard() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return@withWriter ExtendedDriveResult(ExtendedDriveStatus.STALE_SESSION) }
            ExtendedDriveResult(classify(error), statuses).also { store.result(it, guard) }
        }
    }

    private fun validateLanes(files: List<ExtendedDriveFile>) {
        require(files.size <= ExtendedDriveProtocol.MAX_FILES && files.map { it.id }.distinct().size == files.size)
        val lanes = files.groupBy { it.descriptor.deviceId to it.descriptor.namespace }
        require(lanes.size <= 64)
        for (lane in lanes.values) {
            require(lane.size in 1..2 && lane.map { it.descriptor.writer }.distinct().size == 1 &&
                lane.map { it.descriptor.generation }.distinct().size == lane.size) { "Forked Drive replica lane" }
            if (lane.size == 2) {
                val ordered = lane.sortedBy { it.descriptor.generation }
                require(ordered[1].descriptor.generation == Math.addExact(ordered[0].descriptor.generation, 1L) &&
                    ordered[1].descriptor.previousId == ordered[0].id &&
                    ordered[1].descriptor.previousHash == ordered[0].descriptor.sha256) { "Unrelated Drive successors" }
            }
        }
        require(lanes.values.sumOf { it.maxBy { file -> file.descriptor.generation }.descriptor.bytes.toLong() } <=
            ExtendedDriveProtocol.MAX_ACCOUNT_BYTES)
    }

    private suspend fun mergeLane(session: ExtendedDriveSession, lane: List<ExtendedDriveFile>, guard: () -> Unit) {
        val ordered = lane.sortedBy { it.descriptor.generation }
        val latest = ordered.last()
        if (store.applied(latest)) return
        val bytes = api.download(session, latest)
        if (ordered.size == 2) payloads.validateSuccessor(latest.descriptor.namespace,
            api.download(session, ordered.first()), bytes)
        guard()
        try { payloads.merge(latest.descriptor.namespace, bytes, guard) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { guard(); throw ReviewRequired() }
        guard()
        store.markApplied(latest, guard)
    }

    private suspend fun recover(session: ExtendedDriveSession, pending: ExtendedDrivePublication,
        identity: ExtendedDriveIdentity, guard: () -> Unit) {
        require(pending.descriptor.deviceId == identity.deviceId && pending.descriptor.writer == identity.writer)
        val existing = api.metadata(session, pending.id)
        if (existing != null) {
            verify(pending, existing, api.download(session, existing))
            payloads.merge(pending.descriptor.namespace, pending.bytes(), guard)
            store.accept(pending, existing, guard)
            cleanup(session, pending.accepted(existing), guard)
        } else {
            check(pending.accepted == null) { "Accepted Drive successor disappeared" }
            publish(session, pending, identity, guard)
        }
    }

    private suspend fun publish(session: ExtendedDriveSession, pending: ExtendedDrivePublication,
        identity: ExtendedDriveIdentity, guard: () -> Unit) {
        require(pending.descriptor.deviceId == identity.deviceId && pending.descriptor.writer == identity.writer)
        // Revalidate the stored bytes against current privacy/causal state before an explicit replay can transmit them.
        try { payloads.merge(pending.descriptor.namespace, pending.bytes(), guard) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { guard(); throw ReviewRequired() }
        if (pending.previous != null) {
            val old = checkNotNull(api.metadata(session, pending.previous.id)) { "Missing predecessor requires review" }
            check(old == pending.previous)
            payloads.validateSuccessor(pending.descriptor.namespace, api.download(session, old), pending.bytes())
        }
        guard()
        try { api.create(session, pending.id, pending.descriptor, pending.bytes()) }
        catch (error: ExtendedDriveHttpException) { if (error.status != 409) throw error }
        val file = checkNotNull(api.metadata(session, pending.id)) { "Drive create outcome is unknown" }
        verify(pending, file, api.download(session, file))
        guard()
        store.accept(pending, file, guard)
        cleanup(session, pending.accepted(file), guard)
    }

    private fun verify(pending: ExtendedDrivePublication, file: ExtendedDriveFile, bytes: ByteArray) {
        check(file.id == pending.id && file.descriptor == pending.descriptor && bytes.contentEquals(pending.bytes())) {
            "Drive generated ID contains conflicting content"
        }
    }

    private suspend fun cleanup(session: ExtendedDriveSession, pending: ExtendedDrivePublication, guard: () -> Unit) {
        val successor = checkNotNull(pending.accepted)
        check(api.metadata(session, successor.id) == successor) { "Drive successor changed before cleanup" }
        val candidate = api.download(session, successor)
        verify(pending, successor, candidate)
        val previous = pending.previous
        if (previous != null) {
            val current = api.metadata(session, previous.id)
            if (current != null) {
                check(current == previous) { "Changed predecessor cannot be compacted" }
                val before = api.download(session, current)
                payloads.validateSuccessor(pending.descriptor.namespace, before, candidate)
                guard()
                check(api.metadata(session, successor.id) == successor) { "Drive successor vanished before compaction" }
                api.delete(session, current)
                check(api.metadata(session, current.id) == null) { "Drive cleanup outcome is unknown" }
            }
        }
        guard()
        store.own(session.permissionId, successor, guard)
        store.markApplied(successor, guard)
        store.finish(pending, guard)
    }

    private fun classify(error: Exception): ExtendedDriveStatus = when (error) {
        is ExtendedDriveHttpException -> when (error.status) {
            401, 403 -> ExtendedDriveStatus.SIGN_IN_REQUIRED
            413, 507 -> ExtendedDriveStatus.QUOTA
            else -> ExtendedDriveStatus.NETWORK_ERROR
        }
        is ReviewRequired, is IllegalArgumentException, is IllegalStateException -> ExtendedDriveStatus.REVIEW_REQUIRED
        is IOException -> ExtendedDriveStatus.NETWORK_ERROR
        else -> ExtendedDriveStatus.STORAGE_ERROR
    }
    private class ReviewRequired : IllegalStateException("Drive namespace requires identity or privacy review")
}
