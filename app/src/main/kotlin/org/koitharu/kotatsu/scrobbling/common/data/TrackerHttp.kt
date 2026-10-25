package org.koitharu.kotatsu.scrobbling.common.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.util.await

suspend fun OkHttpClient.trackerCall(request: Request, storage: ScrobblerStorage): Response {
    if (request.url.pathSegments.any { it == "oauth" || it == "oauth2" }) {
        val version = storage.sessionVersion
        val response = newCall(request).await()
        if (version != storage.sessionVersion) {
            response.close()
            throw TrackerSessionChangedException()
        }
        return response
    }
    val session = storage.requestSession()
    val guarded = kotlinx.coroutines.currentCoroutineContext()[TrackerRequestGuard] != null
    // A reviewed automatic patch must not be resent by connection recovery or token authenticators.
    val transport = if (guarded) newBuilder().retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).build() else this
    val call = transport.newCall(request.newBuilder().tag(TrackerSession::class.java, session).build())
    val response = storage.requestGate.execute {
        storage.checkSession(session)
        checkTrackerRequestGuard()
        call.await().also { storage.requestGate.update(it) }
    }
    try {
        storage.checkSession(session)
        if (!response.isSuccessful) {
            val retry = storage.requestGate.retryDelay(response.header("Retry-After"), System.currentTimeMillis())
            val detail = response.body?.string().orEmpty().replace(session.token, "[redacted]").take(500)
            response.close()
            throw TrackerHttpException(response.code, retry ?: 60_000L, detail)
        }
        return response
    } catch (error: Throwable) {
        response.close()
        throw error
    }
}
