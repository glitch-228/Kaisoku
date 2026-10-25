package org.koitharu.kotatsu.sync.extended.appserver

import android.accounts.AccountManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.drive.SyncBackend
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import org.koitharu.kotatsu.sync.extended.ExtendedReplicaPayloads
import org.koitharu.kotatsu.sync.extended.server.FileServerReplicaJournal
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaClient
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaEndpoint
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaProtocol
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerExtendedStorage @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.noBackupFilesDir, "sync_extended/server")
    val store = ServerExtendedStore(directory)
    val journal = FileServerReplicaJournal(File(directory, "uploads"))
}

@Singleton
class AndroidServerExtendedAccounts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: SyncBackendSettings,
) : ServerExtendedAccounts {
    private val manager = AccountManager.get(context)
    private val accountType = context.getString(R.string.account_type_sync)

    @Synchronized override fun capture(allowCleartext: Boolean, explicitBinding: Boolean): ServerExtendedAccount {
        check(backend.backend == SyncBackend.KAISOKU_SERVER) { "Select the server sync provider first" }
        val accounts = manager.getAccountsByType(accountType)
        check(accounts.size == 1) { "Select one signed-in sync account" }
        val account = accounts.single()
        val rawUrl = manager.getUserData(account, SyncSettings.KEY_SYNC_URL)
            ?: SyncSettings(context, account).syncUrl
        val endpoint = ServerReplicaEndpoint.parse(rawUrl, allowCleartextPrivateAddress = allowCleartext)
        val token = checkNotNull(manager.peekAuthToken(account, accountType)) { "Sign in to the sync account" }
        var nonce = manager.getUserData(account, NONCE)
        if (nonce == null && explicitBinding) {
            nonce = UUID.randomUUID().toString()
            manager.setUserData(account, NONCE, nonce)
        }
        val bound = checkNotNull(nonce) { "Check this sync connection before enabling extended sync" }
        ServerReplicaProtocol.requireUuid(bound)
        val accountId = ServerExtendedIdentity.expectedAccountId(token)
        val identity = "${account.name}\n${account.type}\n${endpoint.canonicalUrl}\n$bound\n$token"
        val revision = ServerReplicaProtocol.sha256(identity.toByteArray(Charsets.UTF_8))
        return ServerExtendedAccount(account.name, account.type, endpoint, accountId, bound, token, revision)
    }

    override fun checkCurrent(account: ServerExtendedAccount) {
        val now = capture(allowCleartext = true, explicitBinding = false)
        check(now.name == account.name && now.type == account.type && now.endpoint == account.endpoint &&
            now.accountId == account.accountId && now.installationNonce == account.installationNonce &&
            now.token == account.token && now.revision == account.revision) { "Sync account session changed" }
    }

    companion object { private const val NONCE = "extended_replica_account_nonce" }
}

/** Android boundary; the coordinator remains injectable with fake accounts/payloads and real loopback transport. */
@Singleton
class ServerExtendedSyncRepository @Inject constructor(
    accounts: AndroidServerExtendedAccounts,
    val storage: ServerExtendedStorage,
    payloads: ExtendedReplicaPayloads,
) {
    private val coordinator = ServerExtendedCoordinator(accounts, storage.store, object : ServerExtendedPayloads {
        override val namespaces: Set<String> get() = payloads.namespaces
        override suspend fun export(namespace: String, checkCurrent: () -> Unit): ByteArray =
            payloads.export(namespace, checkCurrent)
        override suspend fun merge(namespace: String, bytes: ByteArray, checkCurrent: () -> Unit) =
            payloads.merge(namespace, bytes, checkCurrent)
    }, ServerReplicaClient(storage.journal))
    private val mutableProgress = MutableStateFlow(ServerExtendedResult(ServerExtendedStatus.DISABLED))
    val progress: StateFlow<ServerExtendedResult> get() = mutableProgress

    suspend fun checkConnection(allowCleartext: Boolean): Set<String> = coordinator.checkConnection(allowCleartext)
    fun checkedBinding(): ServerExtendedBinding? = coordinator.checkedBinding()
    suspend fun enable(namespaces: Set<String>, allowCleartext: Boolean) =
        coordinator.enable(namespaces, allowCleartext)
    suspend fun disable() {
        coordinator.disable()
        mutableProgress.value = ServerExtendedResult(ServerExtendedStatus.DISABLED)
    }
    suspend fun sync(mode: ServerExtendedRunMode): ServerExtendedResult {
        mutableProgress.value = ServerExtendedResult(ServerExtendedStatus.RUNNING)
        return try { coordinator.sync(mode).also { mutableProgress.value = it } }
        catch (error: CancellationException) {
            mutableProgress.value = ServerExtendedResult(ServerExtendedStatus.STALE_SESSION)
            throw error
        } catch (error: Exception) {
            mutableProgress.value = ServerExtendedResult(ServerExtendedStatus.STORAGE_ERROR)
            throw error
        }
    }
}
