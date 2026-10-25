package org.koitharu.kotatsu.core.archive

import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Memory-only credentials and resource revocation. There is deliberately no serialization API. */
class ArchiveSessionManager(val policy: ArchiveSessionPolicy = ArchiveSessionPolicy()) : Closeable {
    private val sessions = HashMap<String, Session>()
    private val sequence = AtomicLong()
    private val listeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    internal data class Fingerprint(val path: String, val size: Long, val modified: Long) {
        companion object {
            fun of(file: File) = Fingerprint(file.canonicalPath, file.length(), file.lastModified())
        }
    }

    internal data class Token(val fingerprint: Fingerprint, val revision: Long)
    private data class Session(
        val token: Token,
        val password: CharArray,
        val resources: MutableSet<Closeable> = HashSet(),
    )

    /** Invoked after resources are revoked. Listeners must clear sensitive views/derived memory state. */
    fun addLockListener(listener: (Set<String>) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    internal fun remember(file: File, password: CharArray) {
        lock(file)
        val fingerprint = Fingerprint.of(file)
        synchronized(this) {
            sessions[fingerprint.path] = Session(Token(fingerprint, sequence.incrementAndGet()), password.copyOf())
        }
    }

    fun isUnlocked(file: File): Boolean = try { token(file); true } catch (_: ArchiveAccessException.Locked) { false }

    internal fun token(file: File): Token {
        enforceTimeout()
        val fingerprint = Fingerprint.of(file)
        val token = synchronized(this) { sessions[fingerprint.path]?.token }
        if (token == null) throw ArchiveAccessException.Locked()
        if (token.fingerprint != fingerprint) {
            lock(file)
            throw ArchiveAccessException.Locked()
        }
        return token
    }

    internal fun password(token: Token): CharArray = synchronized(this) {
        val current = sessions[token.fingerprint.path]
        if (current?.token != token) throw ArchiveAccessException.Locked()
        current.password.copyOf()
    }

    internal fun requireActive(token: Token) {
        if (this.token(File(token.fingerprint.path)) != token) throw ArchiveAccessException.Locked()
    }

    internal fun register(token: Token, resource: Closeable) {
        synchronized(this) {
            val session = sessions[token.fingerprint.path]
            if (session?.token == token) {
                session.resources += resource
                return
            }
        }
        resource.close()
        throw ArchiveAccessException.Locked()
    }

    internal fun unregister(token: Token, resource: Closeable) {
        synchronized(this) { sessions[token.fingerprint.path]?.takeIf { it.token == token }?.resources?.remove(resource) }
    }

    fun lock(file: File) = revoke(setOf(file.canonicalPath))

    fun lockAll() = revoke(synchronized(this) { sessions.keys.toSet() })

    fun onForeground() { if (policy.onForeground()) lockAll() }
    fun onBackground() { if (policy.onBackground()) lockAll() }
    fun onSpeechChanged(active: Boolean) { if (policy.onSpeechChanged(active)) lockAll() }
    fun enforceTimeout() { if (policy.isExpired()) lockAll() }

    private fun revoke(paths: Set<String>) {
        val revoked = synchronized(this) {
            paths.mapNotNull { sessions.remove(it) }.onEach { it.password.fill('\u0000') }
        }
        if (revoked.isEmpty()) return
        revoked.forEach { session -> session.resources.toList().forEach { runCatching { it.close() } } }
        val affected = revoked.mapTo(HashSet()) { it.token.fingerprint.path }
        listeners.forEach { listener -> runCatching { listener(affected) } }
    }

    override fun close() = lockAll()
}
