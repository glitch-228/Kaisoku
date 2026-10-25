package org.koitharu.kotatsu.sync.extended.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext

object ServerReplicaProtocol {
    const val VERSION = 1
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_LANES = 64
    val namespaces = setOf("chapter_reads_v1", "tracker_links_v1", "metadata_overrides_v1",
        "remote_libraries_v1", "foreign_import_state_v1")
    internal val hashPattern = Regex("[a-f0-9]{64}")
    internal val revisionPattern = Regex("[1-9][0-9]{0,18}")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun requireUuid(value: String) {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
            "Replica device and request IDs must be canonical UUIDs"
        }
    }

    fun requireEtag(value: String) {
        require(value.matches(Regex("\"r[1-9][0-9]{0,18}-[a-f0-9]{64}\""))) { "Invalid strong replica ETag" }
        require(value.substring(2, value.indexOf('-')).toLongOrNull() != null) { "Invalid replica revision" }
    }
}

/** HTTP is an explicit private-address opt-in and never permits hostnames or public addresses. */
class ServerReplicaEndpoint private constructor(val url: HttpUrl) {
    val canonicalUrl: String get() = url.toString()

    internal fun resource(vararg segments: String): HttpUrl = url.newBuilder().apply {
        addPathSegment("resource")
        addPathSegment("v2")
        segments.forEach(::addPathSegment)
    }.build()

    override fun equals(other: Any?): Boolean = other is ServerReplicaEndpoint && url == other.url
    override fun hashCode(): Int = url.hashCode()
    override fun toString(): String = canonicalUrl

    companion object {
        fun parse(value: String, allowCleartextPrivateAddress: Boolean = false): ServerReplicaEndpoint {
            require(value.length <= 4096) { "Replica endpoint is too long" }
            val parsed = value.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null &&
                parsed.fragment == null) { "Replica endpoint cannot contain credentials, query or fragment" }
            require(parsed.isHttps || (allowCleartextPrivateAddress && parsed.scheme == "http" &&
                isPrivateLiteral(parsed.host))) { "Replica endpoint requires HTTPS" }
            val builder = parsed.newBuilder()
            if (parsed.encodedPath.endsWith('/')) builder.removePathSegment(parsed.pathSize - 1)
            return ServerReplicaEndpoint(builder.build())
        }

        private fun isPrivateLiteral(host: String): Boolean {
            if (':' in host) {
                if (!host.matches(Regex("[a-fA-F0-9:]+"))) return false
                val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
                return bytes.size == 16 && (bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte() ||
                    (bytes[0].toInt() and 0xfe) == 0xfc)
            }
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
            val octets = parts.map { it.toInt() }
            if (octets.any { it > 255 }) return false
            return octets[0] == 127 || octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
    }
}

data class ServerReplicaScope(val endpoint: ServerReplicaEndpoint, val accountId: String) {
    init {
        require(accountId.matches(ServerReplicaProtocol.revisionPattern) && accountId.toLongOrNull() != null) {
            "Replica account ID must be an exact positive decimal string"
        }
    }
}

/** Owner callback must check account, auth revision, server and consent; token is never journaled. */
class ServerReplicaSession(
    val scope: ServerReplicaScope,
    internal val bearerToken: String,
    val sessionRevision: String,
    private val checkCurrent: () -> Unit,
) {
    init { require(bearerToken.isNotBlank() && bearerToken.none { it <= ' ' || it == '\u007f' }) }

    suspend fun ensureCurrent() {
        currentCoroutineContext().ensureActive()
        checkCurrent()
        currentCoroutineContext().ensureActive()
    }

    internal fun ensureCurrentNow() = checkCurrent()

    internal fun ensureCurrentForNetwork(context: CoroutineContext) {
        try {
            context.ensureActive()
            checkCurrent()
            context.ensureActive()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Replica session is no longer current", e)
        }
    }
}

data class ServerReplicaCapabilities(
    val accountId: String,
    val namespaces: Set<String>,
    val maxPayloadBytes: Int,
    val maxLanes: Int,
    val maxAccountBytes: Long,
    val maxMutationKeys: Int,
)

data class ServerReplicaMetadata(
    val accountId: String,
    val namespace: String,
    val deviceId: String,
    val payloadVersion: Int,
    val revision: String,
    val sha256: String,
    val bytes: Int,
    val updatedAt: Long,
    val etag: String,
)

data class ServerReplicaReceipt(
    val accepted: ServerReplicaMetadata,
    val current: ServerReplicaMetadata,
    val replayed: Boolean,
)

sealed interface ServerReplicaCondition {
    data object CreateIfNoneMatch : ServerReplicaCondition
    data class UpdateIfMatch(val etag: String) : ServerReplicaCondition {
        init { ServerReplicaProtocol.requireEtag(etag) }
    }
}

sealed interface ServerReplicaDownload {
    data object Missing : ServerReplicaDownload
    data class NotModified(val etag: String) : ServerReplicaDownload
    class Downloaded internal constructor(
        bytes: ByteArray,
        val etag: String,
        val sha256: String,
        val payloadVersion: Int,
    ) :
        ServerReplicaDownload {
        private val content = bytes.copyOf()
        fun bytes(): ByteArray = content.copyOf()
    }
}

/** Response errors never include tokens, server bodies or payload contents. */
class ServerReplicaHttpException(val status: Int, val errorCode: String, val current: ServerReplicaMetadata? = null) :
    IOException("Extended replica HTTP $status: $errorCode")

class ServerReplicaProtocolException(message: String) : IOException(message)
