package org.koitharu.kotatsu.sync.extended.appserver

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koitharu.kotatsu.core.util.BoundedJson
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaEndpoint
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaProtocol
import org.koitharu.kotatsu.sync.extended.server.ServerReplicaScope
import java.util.Base64

object ServerExtendedNamespaces {
    val all: Set<String> = ServerReplicaProtocol.namespaces
}

/** Token and account-session stamp exist only in memory. Server account IDs never come from email. */
class ServerExtendedAccount(
    val name: String,
    val type: String,
    val endpoint: ServerReplicaEndpoint,
    val accountId: String,
    val installationNonce: String,
    internal val token: String,
    internal val revision: String,
) {
    val scope: ServerReplicaScope get() = ServerReplicaScope(endpoint, accountId)
}

interface ServerExtendedAccounts {
    /** Explicit binding may initialize an account-local nonce; background callers must never do so. */
    fun capture(allowCleartext: Boolean, explicitBinding: Boolean = false): ServerExtendedAccount
    fun checkCurrent(account: ServerExtendedAccount)
}

interface ServerExtendedPayloads {
    val namespaces: Set<String>
    suspend fun export(namespace: String, checkCurrent: () -> Unit): ByteArray
    suspend fun merge(namespace: String, bytes: ByteArray, checkCurrent: () -> Unit)
}

/** This claim is an untrusted expectation, verified by authenticated capabilities before use. */
object ServerExtendedIdentity {
    fun expectedAccountId(token: String): String {
        require(token.length <= 131_072) { "Invalid sync token" }
        val parts = token.split('.')
        require(parts.size == 3 && parts[1].length in 1..65_536 &&
            parts[1].all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
            "Invalid sync token"
        }
        val bytes = try { Base64.getUrlDecoder().decode(parts[1]) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid sync token") }
        val root = kotlinx.serialization.json.Json.parseToJsonElement(BoundedJson.validateObject(bytes, 49_152))
            as? JsonObject ?: error("Invalid sync token")
        val value = root["user_id"] as? JsonPrimitive ?: error("Sync account identity is unavailable")
        require(!value.isString && value.content.matches(Regex("[1-9][0-9]{0,18}")) &&
            value.content.toLongOrNull() != null) { "Sync account identity is unavailable" }
        return value.content
    }
}

data class ServerExtendedBinding(
    val accountName: String,
    val accountType: String,
    val endpoint: String,
    val accountId: String,
    val installationNonce: String,
) {
    fun matches(account: ServerExtendedAccount): Boolean = accountName == account.name && accountType == account.type &&
        endpoint == account.endpoint.canonicalUrl && accountId == account.accountId &&
        installationNonce == account.installationNonce

    val key: String get() = ServerReplicaProtocol.sha256("$endpoint\n$accountId".toByteArray(Charsets.UTF_8))

    companion object {
        fun from(account: ServerExtendedAccount) = ServerExtendedBinding(account.name, account.type,
            account.endpoint.canonicalUrl, account.accountId, account.installationNonce)
    }
}

data class ServerExtendedConfiguration(
    val generation: String,
    val enabled: Boolean = false,
    val binding: ServerExtendedBinding? = null,
    val namespaces: Set<String> = emptySet(),
    val allowCleartext: Boolean = false,
    val automatic: Boolean = false,
    val syncOnStart: Boolean = false,
    val wifiOnly: Boolean = false,
    val intervalMinutes: Int = 360,
)

enum class ServerExtendedRunMode { MANUAL, SCHEDULED, RESUME_PENDING }

enum class ServerExtendedStatus {
    DISABLED, READY, RUNNING, SUCCESS, PARTIAL, UNSUPPORTED, SIGN_IN_REQUIRED, STALE_SESSION,
    RECOVERY_REQUIRED, CONFLICT, REVIEW_REQUIRED, QUOTA, STORAGE_ERROR, NETWORK_ERROR,
}

data class ServerExtendedResult(val status: ServerExtendedStatus,
    val lanes: Map<String, ServerExtendedStatus> = emptyMap())

internal suspend fun currentGuard(account: ServerExtendedAccount, accounts: ServerExtendedAccounts,
    store: ServerExtendedStore, expected: ServerExtendedConfiguration): () -> Unit {
    val context = currentCoroutineContext()
    return {
        context.ensureActive()
        accounts.checkCurrent(account)
        val now = store.configuration()
        check(now.generation == expected.generation && now.enabled && now.binding?.matches(account) == true) {
            "Extended sync session changed"
        }
        context.ensureActive()
    }
}
