package org.koitharu.kotatsu.sync.extended.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.sync.drive.DriveAuthorization
import org.koitharu.kotatsu.sync.drive.GoogleDriveAuth
import org.koitharu.kotatsu.sync.drive.SyncBackend
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import org.koitharu.kotatsu.sync.extended.ExtendedReplicaPayloads
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtendedDriveStorage @Inject constructor(@ApplicationContext context: Context) {
    internal val store = ExtendedDriveStore(File(context.noBackupFilesDir, "sync_extended/drive"))
    fun configuration(): ExtendedDriveConfiguration = store.configuration()
    fun result(): ExtendedDriveResult = store.result()
    fun setAutomatic(enabled: Boolean) = store.configure(store.configuration().copy(automatic = enabled))
}

data class ExtendedDriveAuthorizationRequest(val revision: Long, val authorization: DriveAuthorization)

/** Reuses public Google OAuth but owns a separate account-bound in-memory authorization session. */
@Singleton
class ExtendedDriveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: GoogleDriveAuth,
    private val backend: SyncBackendSettings,
    val storage: ExtendedDriveStorage,
    payloads: ExtendedReplicaPayloads,
    @BaseHttpClient publicClient: OkHttpClient,
) {
    private val api = ExtendedDriveHttp(publicClient)
    private val epoch = AtomicLong()
    @Volatile private var authorized: Authorization? = null
    private val progressState = MutableStateFlow(ExtendedDriveResult(ExtendedDriveStatus.DISABLED))
    val progress: StateFlow<ExtendedDriveResult> get() = progressState
    private val coordinator = ExtendedDriveCoordinator(storage.store, api, object : ExtendedDrivePayloads {
        override val namespaces: Set<String> get() = payloads.namespaces
        override suspend fun export(namespace: String, checkCurrent: () -> Unit) = payloads.export(namespace, checkCurrent)
        override suspend fun merge(namespace: String, bytes: ByteArray, checkCurrent: () -> Unit) =
            payloads.merge(namespace, bytes, checkCurrent)
        override suspend fun validateSuccessor(namespace: String, predecessor: ByteArray, candidate: ByteArray) =
            payloads.validateSuccessor(namespace, predecessor, candidate)
    })

    /** Root invokes this on backend/account/disconnect changes, including legacy Drive account UI changes. */
    fun invalidateAuthorization() { epoch.incrementAndGet(); authorized = null }

    suspend fun authorize(activity: Activity? = null): ExtendedDriveAuthorizationRequest {
        withContext(Dispatchers.IO) { storage.configuration() }
        check(backend.backend == SyncBackend.GOOGLE_DRIVE) { "Select Google Drive before extended sync" }
        invalidateAuthorization()
        val revision = epoch.get()
        val result = auth.authorize(activity)
        check(revision == epoch.get()) { "Drive authorization changed" }
        return ExtendedDriveAuthorizationRequest(revision, result)
    }

    suspend fun authorizationFromIntent(intent: Intent?, revision: Long): ExtendedDriveUser {
        check(revision == epoch.get()) { "Drive authorization expired" }
        return acceptToken(auth.authorizationFromIntent(intent).value, revision)
    }

    suspend fun acceptToken(token: String, revision: Long): ExtendedDriveUser = withContext(Dispatchers.IO) {
        check(revision == epoch.get() && backend.backend == SyncBackend.GOOGLE_DRIVE)
        val accountHint = backend.accountEmail
        val guard = {
            check(revision == epoch.get() && backend.backend == SyncBackend.GOOGLE_DRIVE &&
                backend.accountEmail == accountHint) { "Drive authorization changed" }
        }
        val user = api.user(token, guard)
        guard()
        authorized = Authorization(token, user, revision, accountHint)
        user
    }

    fun checkedUser(): ExtendedDriveUser? = authorized?.user

    suspend fun enable(namespaces: Set<String>) = withContext(Dispatchers.IO) {
        require(namespaces.isNotEmpty() && namespaces.all { it in ExtendedDriveProtocol.namespaces })
        val session = checkNotNull(authorized) { "Check the Drive account first" }
        guard(session)()
        storage.store.withWriter {
            storage.store.identity()
            guard(session)()
            storage.store.configure(storage.configuration().copy(enabled = true, permissionId = session.user.permissionId,
                accountLabel = session.user.emailAddress ?: session.user.displayName, namespaces = namespaces), guard(session))
            storage.store.result(ExtendedDriveResult(ExtendedDriveStatus.READY))
        }
    }

    suspend fun disable() = withContext(Dispatchers.IO) {
        invalidateAuthorization()
        storage.store.configure(storage.configuration().copy(enabled = false))
        storage.store.result(ExtendedDriveResult(ExtendedDriveStatus.DISABLED))
        progressState.value = ExtendedDriveResult(ExtendedDriveStatus.DISABLED)
    }

    suspend fun sync(resume: Boolean = false, automatic: Boolean = false): ExtendedDriveResult {
        val configuration = withContext(Dispatchers.IO) { storage.configuration() }
        if (!configuration.enabled || automatic && !configuration.automatic) return ExtendedDriveResult(ExtendedDriveStatus.DISABLED)
        val session = checkNotNull(authorized) { "Check the Drive account first" }
        if (session.user.permissionId != configuration.permissionId) return ExtendedDriveResult(ExtendedDriveStatus.STALE_SESSION)
        progressState.value = ExtendedDriveResult(ExtendedDriveStatus.RUNNING)
        return try {
            val current = guard(session)
            val guarded = {
                current()
                if (automatic && backend.isWifiOnly) {
                    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    check(!connectivity.isActiveNetworkMetered) { "Extended Drive is waiting for an unmetered connection" }
                }
            }
            coordinator.sync(ExtendedDriveSession(session.user.permissionId, session.token, guarded), resume, automatic)
                .also { progressState.value = it }
        } catch (error: CancellationException) {
            progressState.value = ExtendedDriveResult(ExtendedDriveStatus.RECOVERY_REQUIRED)
            throw error
        } catch (_: Exception) {
            ExtendedDriveResult(ExtendedDriveStatus.STORAGE_ERROR).also { progressState.value = it }
        }
    }

    /** No UI resolution or write retry is launched by background work. */
    suspend fun syncAutomatically(): ExtendedDriveResult {
        val configuration = withContext(Dispatchers.IO) { storage.configuration() }
        if (!configuration.enabled || !configuration.automatic || backend.backend != SyncBackend.GOOGLE_DRIVE) {
            return ExtendedDriveResult(ExtendedDriveStatus.DISABLED)
        }
        val request = authorize()
        val token = request.authorization as? DriveAuthorization.Token
            ?: return ExtendedDriveResult(ExtendedDriveStatus.SIGN_IN_REQUIRED)
        acceptToken(token.value, request.revision)
        return sync(automatic = true)
    }

    fun diagnostics(): String = buildString {
        val result = progress.value.takeIf { it.status == ExtendedDriveStatus.RUNNING }
            ?: runCatching { storage.result() }.getOrElse { ExtendedDriveResult(ExtendedDriveStatus.STORAGE_ERROR) }
        append("Extended Drive: ").append(result.status.name)
        result.lanes.forEach { (namespace, status) -> append('\n').append(namespace).append(": ").append(status.name) }
        append("\nUnknown uploads require an explicit Resume action.")
    }

    private fun guard(value: Authorization): () -> Unit = {
        check(authorized === value && epoch.get() == value.revision && backend.backend == SyncBackend.GOOGLE_DRIVE &&
            backend.accountEmail == value.accountHint) { "Drive authorization changed" }
    }
    private class Authorization(val token: String, val user: ExtendedDriveUser, val revision: Long, val accountHint: String?)
}
