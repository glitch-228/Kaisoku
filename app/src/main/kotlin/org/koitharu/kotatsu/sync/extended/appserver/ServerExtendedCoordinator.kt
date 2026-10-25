package org.koitharu.kotatsu.sync.extended.appserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaClient
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaCondition
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaConnection
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaDownload
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaHttpException
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaMetadata
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaProtocol
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaProtocolException
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaSession
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaUpload
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaUploadOutcome
import java.io.IOException

/** All entrypoints share a mutex. Unknown PUTs are resumed only by the explicit RESUME_PENDING action. */
class ServerExtendedCoordinator(
    private val accounts: ServerExtendedAccounts,
    private val store: ServerExtendedStore,
    private val payloads: ServerExtendedPayloads,
    private val client: ServerReplicaClient,
) {
    private val mutex = Mutex()
    @Volatile private var verified: ServerExtendedAccount? = null
    private var supported: Set<String> = emptySet()

    fun checkedBinding(): ServerExtendedBinding? = verified?.let(ServerExtendedBinding::from)

    /** Explicit read-only action, never called by fragment creation, preference binding or a worker. */
    suspend fun checkConnection(allowCleartext: Boolean): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            verified = null
            val account = accounts.capture(allowCleartext, explicitBinding = true)
            val context = currentCoroutineContext()
            val session = ServerReplicaSession(account.scope, account.token, account.revision) {
                context.ensureActive()
                accounts.checkCurrent(account)
            }
            try {
                val connection = client.negotiate(session)
                session.ensureCurrent()
                supported = connection.capabilities.namespaces intersect payloads.namespaces
                verified = account
                supported.toSet()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                session.ensureCurrent()
                store.saveResult(ServerExtendedResult(classify(error))) { accounts.checkCurrent(account) }
                throw error
            }
        }
    }

    /** Enable consumes only a fresh, current successful check; enabling itself performs no request. */
    suspend fun enable(namespaces: Set<String>, allowCleartext: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(namespaces.isNotEmpty() && namespaces.all { it in supported }) { "Check supported content first" }
            val account = checkNotNull(verified) { "Check this connection before enabling extended sync" }
            accounts.checkCurrent(account)
            require(account.endpoint.url.isHttps || allowCleartext) { "Explicit private HTTP consent is required" }
            store.deviceId()
            val old = store.configuration()
            store.configure(old.copy(enabled = true, binding = ServerExtendedBinding.from(account),
                namespaces = namespaces, allowCleartext = allowCleartext)) { accounts.checkCurrent(account) }
            store.saveResult(ServerExtendedResult(ServerExtendedStatus.READY))
            verified = null
        }
    }

    suspend fun disable() = withContext(Dispatchers.IO) {
        store.configure(store.configuration().copy(enabled = false))
        store.saveResult(ServerExtendedResult(ServerExtendedStatus.DISABLED))
        verified = null
    }

    suspend fun sync(mode: ServerExtendedRunMode): ServerExtendedResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val configuration = try { store.configuration() } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withLock ServerExtendedResult(ServerExtendedStatus.STORAGE_ERROR)
            }
            if (!configuration.enabled || (mode == ServerExtendedRunMode.SCHEDULED && !configuration.automatic)) {
                return@withLock ServerExtendedResult(ServerExtendedStatus.DISABLED)
            }
            val account = try { accounts.capture(configuration.allowCleartext) } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withLock record(ServerExtendedResult(ServerExtendedStatus.SIGN_IN_REQUIRED))
            }
            if (configuration.binding?.matches(account) != true) {
                return@withLock record(ServerExtendedResult(ServerExtendedStatus.STALE_SESSION))
            }
            val guard = currentGuard(account, accounts, store, configuration)
            try {
                guard()
                val device = store.deviceId()
                val session = ServerReplicaSession(account.scope, account.token, account.revision, guard)
                val connection = client.negotiate(session)
                val statuses = linkedMapOf<String, ServerExtendedStatus>()
                val selected = configuration.namespaces.sorted()
                for (namespace in selected) {
                    val paused = store.paused(account.scope, namespace)
                    if (paused != null && mode == ServerExtendedRunMode.SCHEDULED) {
                        statuses[namespace] = paused
                        continue
                    }
                    if (mode == ServerExtendedRunMode.MANUAL && paused != null) {
                        store.pause(account.scope, namespace, null, guard)
                    }
                    if (namespace !in connection.capabilities.namespaces || namespace !in payloads.namespaces) {
                        statuses[namespace] = ServerExtendedStatus.UNSUPPORTED
                        continue
                    }
                    val upload = connection.pending(namespace, device) ?: continue
                    if (upload.outcome == null && mode != ServerExtendedRunMode.RESUME_PENDING) {
                        statuses[namespace] = ServerExtendedStatus.RECOVERY_REQUIRED
                        continue
                    }
                    statuses[namespace] = try {
                        val pending = if (upload.outcome == null) {
                            connection.uploadPending(namespace, device)
                        } else upload
                        consume(connection, pending, mode, guard)
                    } catch (error: Exception) {
                        guard()
                        if (error is CancellationException) throw error
                        if (connection.pending(namespace, device)?.outcome == null) {
                            ServerExtendedStatus.RECOVERY_REQUIRED
                        }
                        else classify(error)
                    }
                }
                if (mode != ServerExtendedRunMode.RESUME_PENDING) {
                    val manifest = connection.manifest()
                    for (namespace in selected) {
                        if (namespace in statuses && statuses[namespace] != ServerExtendedStatus.SUCCESS) continue
                        if (namespace !in connection.capabilities.namespaces || namespace !in payloads.namespaces) {
                            statuses[namespace] = ServerExtendedStatus.UNSUPPORTED
                            continue
                        }
                        try {
                            val lanes = manifest.filter { it.namespace == namespace }.sortedBy { it.deviceId }
                            for (metadata in lanes) readMerge(connection, metadata, guard)
                            guard()
                            val bytes = payloads.export(namespace, guard)
                            guard()
                            val hash = ServerReplicaProtocol.sha256(bytes)
                            val own = lanes.singleOrNull { it.deviceId == device }
                            if (own?.sha256 != hash) {
                                val condition = own?.let { ServerReplicaCondition.UpdateIfMatch(it.etag) }
                                    ?: ServerReplicaCondition.CreateIfNoneMatch
                                connection.prepareUpload(namespace, device, bytes, condition)
                                val accepted = try {
                                    connection.uploadPending(namespace, device)
                                } catch (error: Exception) {
                                    guard()
                                    if (error is CancellationException) throw error
                                    statuses[namespace] = ServerExtendedStatus.RECOVERY_REQUIRED
                                    continue
                                }
                                statuses[namespace] = consume(connection, accepted, mode, guard)
                            } else statuses[namespace] = ServerExtendedStatus.SUCCESS
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            guard()
                            statuses[namespace] = classify(error)
                        }
                    }
                }
                guard()
                for ((namespace, status) in statuses) {
                    if (status in setOf(ServerExtendedStatus.QUOTA, ServerExtendedStatus.CONFLICT,
                            ServerExtendedStatus.REVIEW_REQUIRED, ServerExtendedStatus.SIGN_IN_REQUIRED)) {
                        store.pause(account.scope, namespace, status, guard)
                    }
                }
                val result = ServerExtendedResult(if (statuses.values.all { it == ServerExtendedStatus.SUCCESS }) {
                    ServerExtendedStatus.SUCCESS
                } else ServerExtendedStatus.PARTIAL, statuses)
                store.saveResult(result, guard)
                result
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Recheck before storing any status under the captured scope. Stale work has no final side effects.
                try { guard() } catch (stale: CancellationException) { throw stale }
                catch (_: Exception) { return@withLock ServerExtendedResult(ServerExtendedStatus.STALE_SESSION) }
                record(ServerExtendedResult(classify(error)), guard)
            }
        }
    }

    private suspend fun readMerge(connection: ServerReplicaConnection, metadata: ServerReplicaMetadata,
        guard: () -> Unit) {
        val baseline = store.applied(connection.session.scope, metadata.namespace, metadata.deviceId)
        if (baseline?.etag == metadata.etag) return
        when (val downloaded = connection.download(metadata.namespace, metadata.deviceId, baseline?.etag)) {
            is ServerReplicaDownload.Downloaded -> {
                val bytes = downloaded.bytes()
                if (downloaded.etag != metadata.etag || downloaded.sha256 != metadata.sha256 ||
                    bytes.size != metadata.bytes) throw IOException("Replica changed during this run")
                guard()
                try { payloads.merge(metadata.namespace, bytes, guard) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    guard()
                    store.retainConflict(connection.session.scope, metadata, bytes, guard)
                    throw MergeNeedsReview()
                }
                guard()
                store.markApplied(connection.session.scope, metadata, guard)
            }
            is ServerReplicaDownload.NotModified -> check(baseline?.etag == metadata.etag) {
                "Replica manifest changed during this run"
            }
            ServerReplicaDownload.Missing -> throw IOException("Replica disappeared during this run")
        }
    }

    private suspend fun consume(connection: ServerReplicaConnection, upload: ServerReplicaUpload,
        mode: ServerExtendedRunMode, guard: () -> Unit): ServerExtendedStatus {
        val outcome = checkNotNull(upload.outcome)
        if (store.consumed(upload.requestId)) {
            connection.acknowledge(upload.lane.namespace, upload.lane.deviceId, upload.requestId)
            store.forgetConsumed(upload.requestId, guard)
            return outcomeStatus(outcome)
        }
        when (outcome) {
            is ServerReplicaUploadOutcome.Accepted -> {
                guard()
                // The journaled export may predate a local restore/reset; causal merge must succeed again before ack.
                try { payloads.merge(upload.lane.namespace, upload.bytes(), guard) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    guard()
                    store.retainConflict(upload.lane.scope, outcome.receipt.accepted, upload.bytes(), guard)
                    return ServerExtendedStatus.REVIEW_REQUIRED
                }
                guard()
                store.markApplied(upload.lane.scope, outcome.receipt.accepted, guard)
                if (outcome.receipt.current != outcome.receipt.accepted) {
                    readMerge(connection, outcome.receipt.current, guard)
                }
            }
            is ServerReplicaUploadOutcome.Conflict -> {
                if (outcome.status != 412 || mode != ServerExtendedRunMode.RESUME_PENDING) {
                    return ServerExtendedStatus.CONFLICT
                }
                val current = connection.manifest().singleOrNull {
                    it.namespace == upload.lane.namespace && it.deviceId == upload.lane.deviceId
                }
                if (current != null) readMerge(connection, current, guard)
            }
            is ServerReplicaUploadOutcome.Rejected -> Unit
        }
        guard()
        store.consume(upload.requestId, guard)
        connection.acknowledge(upload.lane.namespace, upload.lane.deviceId, upload.requestId)
        store.forgetConsumed(upload.requestId, guard)
        if (outcome is ServerReplicaUploadOutcome.Accepted) {
            store.pause(upload.lane.scope, upload.lane.namespace, null, guard)
        }
        return outcomeStatus(outcome)
    }

    private fun outcomeStatus(outcome: ServerReplicaUploadOutcome): ServerExtendedStatus = when (outcome) {
        is ServerReplicaUploadOutcome.Accepted -> ServerExtendedStatus.SUCCESS
        is ServerReplicaUploadOutcome.Conflict -> ServerExtendedStatus.CONFLICT
        is ServerReplicaUploadOutcome.Rejected -> when (outcome.status) {
            401 -> ServerExtendedStatus.SIGN_IN_REQUIRED
            413, 507 -> ServerExtendedStatus.QUOTA
            else -> ServerExtendedStatus.REVIEW_REQUIRED
        }
    }

    private fun classify(error: Exception): ServerExtendedStatus = when (error) {
        is MergeNeedsReview -> ServerExtendedStatus.REVIEW_REQUIRED
        is ServerReplicaHttpException -> when (error.status) {
            401 -> ServerExtendedStatus.SIGN_IN_REQUIRED
            413, 507 -> ServerExtendedStatus.QUOTA
            404, 405, 415 -> ServerExtendedStatus.UNSUPPORTED
            else -> ServerExtendedStatus.NETWORK_ERROR
        }
        is ServerReplicaProtocolException -> ServerExtendedStatus.UNSUPPORTED
        is IOException -> ServerExtendedStatus.NETWORK_ERROR
        else -> ServerExtendedStatus.REVIEW_REQUIRED
    }

    private fun record(result: ServerExtendedResult, guard: () -> Unit = {}): ServerExtendedResult = result.also {
        store.saveResult(it, guard)
    }

    private class MergeNeedsReview : IllegalStateException("Replica requires local identity review")
}
