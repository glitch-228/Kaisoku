package org.koitharu.kotatsu.sync.extended.server

import org.koitharu.kotatsu.core.util.BoundedJson

/** Preserve the wire-specific error boundary while sharing strict byte-preserving JSON validation. */
internal object ServerReplicaJson {
    fun validate(bytes: ByteArray, maximum: Int = ServerReplicaProtocol.MAX_BYTES): String = try {
        BoundedJson.validateObject(bytes, maximum)
    } catch (error: IllegalArgumentException) {
        throw ServerReplicaProtocolException(checkNotNull(error.message))
    }
}
