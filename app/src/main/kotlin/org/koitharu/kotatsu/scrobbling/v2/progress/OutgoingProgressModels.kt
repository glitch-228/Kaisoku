package org.koitharu.kotatsu.scrobbling.v2.progress

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.scrobbling.v2.TrackerConnection
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerRawProgress
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry

@Serializable
data class OutgoingProgressConnection(
    val provider: String,
    val accountId: String,
    val revision: Long,
    val scopes: Set<String> = emptySet(),
    val nickname: String? = null,
) {
    init {
        TrackerOwner(provider, accountId)
        require(revision >= 0 && scopes.size <= 64 && scopes.all { it.length <= 256 })
        require(nickname == null || nickname.length <= 512)
    }
    fun tracker() = TrackerConnection(owner(), revision, scopes, nickname)
    fun owner() = TrackerOwner(provider, accountId)
    fun sameSession(other: OutgoingProgressConnection) =
        provider == other.provider && accountId == other.accountId && revision == other.revision && scopes == other.scopes
    companion object {
        fun from(value: TrackerConnection) = OutgoingProgressConnection(value.owner.provider, value.owner.accountId,
            value.revision, value.scopes, value.nickname)
    }
}

data class OutgoingProgressAccount(
    val connection: OutgoingProgressConnection,
    val displayName: String,
    val writable: Boolean,
    val maxChapterScale: Int = 0,
    val maxVolumeScale: Int? = null,
    val supportsEntryIds: Boolean = false,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 512)
        require(maxChapterScale in 0..12 && (maxVolumeScale == null || maxVolumeScale in 0..12))
    }
    fun check(fields: OutgoingProgressFields) {
        require(writable) { "This verified account does not permit progress writes" }
        fields.number?.let { require(decimal(it).stripTrailingZeros().scale() <= maxChapterScale) {
            "This provider does not support the selected fractional chapter"
        } }
        fields.volume?.let { require(maxVolumeScale != null &&
            decimal(it).stripTrailingZeros().scale() <= maxVolumeScale) { "This provider does not support this volume field" } }
        require(fields.entryId == null || supportsEntryIds) { "This provider does not support chapter entry identities" }
    }
}

/** Null fields are omitted. Nothing here can clear a value, create an entry or select status/rating/notes. */
@Serializable
data class OutgoingProgressFields(val number: String? = null, val volume: String? = null, val entryId: String? = null) {
    init {
        require(number != null || volume != null || entryId != null)
        require(number == null || entryId == null) { "Choose a chapter number or an explicit chapter entry" }
        listOfNotNull(number, volume).forEach(::decimal)
        require(entryId == null || entryId.isNotBlank() && entryId.length <= 512)
    }
    fun raw() = TrackerRawProgress(number, volume, entryId)
}

@Serializable
data class OutgoingProgressSnapshot(
    val mangaId: Long,
    val title: String,
    val source: String,
    val editionUrl: String,
    val editionKey: String?,
    val branchKey: String,
    val mediaId: String,
    val listEntryId: String?,
    val linkRevision: String,
    val evidenceRevision: String,
    val positionRevision: String? = null,
) {
    init {
        require(mangaId != 0L && title.length <= 2048 && source.isNotBlank() && source.length <= 1024)
        require(editionUrl.isNotBlank() && editionUrl.length <= 8192 && (editionKey == null || editionKey.length <= 4096))
        require(branchKey.isNotBlank() && branchKey.length <= 2048)
        require(mediaId.isNotBlank() && mediaId.length <= 512 && (listEntryId == null || listEntryId.length in 1..512))
        require(linkRevision.isNotBlank() && linkRevision.length <= 4096)
        require(evidenceRevision.isNotBlank() && evidenceRevision.length <= 4096)
        require(positionRevision == null || positionRevision.length <= 4096)
    }
    fun sameEvidence(other: OutgoingProgressSnapshot, ignorePosition: Boolean = false): Boolean =
        copy(title = "", positionRevision = if (ignorePosition) null else positionRevision) ==
            other.copy(title = "", positionRevision = if (ignorePosition) null else other.positionRevision)
}

/** Root validates this as a confirmed reader event, never an imported read mark or a cursor estimate. */
@Serializable
data class OutgoingProgressCompletion(
    val eventId: String,
    val chapterReference: String,
    val number: String,
    val branchKey: String,
    val evidenceRevision: String,
) {
    init {
        require(eventId.isNotBlank() && eventId.length <= 4096 && chapterReference.isNotBlank() &&
            chapterReference.length <= 4096 && branchKey.isNotBlank() && branchKey.length <= 2048)
        require(evidenceRevision.isNotBlank() && evidenceRevision.length <= 4096)
        decimal(number)
    }
}

@Serializable
data class OutgoingProgressGuard(
    val connection: OutgoingProgressConnection,
    val snapshot: OutgoingProgressSnapshot,
    val completion: OutgoingProgressCompletion? = null,
) {
    init {
        require(completion == null || completion.branchKey == snapshot.branchKey &&
            completion.evidenceRevision == snapshot.evidenceRevision)
    }
    fun reference() = TrackerReference(connection.owner(), snapshot.mediaId, snapshot.listEntryId)
}

/** Only committed local/cache reads are permitted outside the two explicit provider operations. */
interface OutgoingProgressAccess {
    suspend fun accounts(): List<OutgoingProgressAccount>
    suspend fun snapshot(connection: OutgoingProgressConnection, mangaId: Long): OutgoingProgressSnapshot?
    suspend fun validate(guard: OutgoingProgressGuard)
    suspend fun readback(guard: OutgoingProgressGuard, beforeRequest: suspend () -> Unit): TrackerRemoteEntry?
    /** Read-only uncertainty recovery checks exact verified owner/reference, even after local cursor/reset/relink changes. */
    suspend fun recoveryReadback(guard: OutgoingProgressGuard, beforeRequest: suspend () -> Unit): TrackerRemoteEntry? =
        readback(guard, beforeRequest)
    suspend fun updateProgressOnly(
        guard: OutgoingProgressGuard,
        fields: OutgoingProgressFields,
        expectedRemote: OutgoingProgressRemote?,
        allowLower: Boolean,
        allowUnknown: Boolean,
        beforeSend: suspend () -> Unit,
    ): TrackerRemoteEntry
}

@Serializable
data class OutgoingProgressRemote(val number: String?, val volume: String?, val entryId: String?, val unit: String) {
    init { require(listOf(number, volume, entryId).all { it == null || it.length <= 512 } && unit.length in 1..32) }
    fun raw() = TrackerRawProgress(number, volume, entryId, unit)
    companion object {
        fun from(value: TrackerRawProgress?) = value?.let { OutgoingProgressRemote(it.number, it.volume, it.entryId, it.unit) }
    }
}

enum class OutgoingProgressEnqueue { NOT_ENABLED, ENQUEUED, ALREADY_OBSERVED, REVIEW_REQUIRED }
@Serializable enum class OutgoingProgressOrigin { AUTOMATIC, MANUAL }
@Serializable enum class OutgoingProgressState { QUEUED, SENDING, READBACK_REQUIRED, PAUSED, ACKNOWLEDGED }

@Serializable
data class OutgoingProgressAction(
    val id: String,
    val guard: OutgoingProgressGuard,
    val fields: OutgoingProgressFields,
    val origin: OutgoingProgressOrigin,
    val consentRevision: Long?,
    val state: OutgoingProgressState = OutgoingProgressState.QUEUED,
    val reviewedRemote: OutgoingProgressRemote? = null,
    val allowLower: Boolean = false,
    val allowUnknown: Boolean = false,
    val uncertain: Boolean = false,
    val requiresReview: Boolean = false,
    val error: String? = null,
    val retryAt: Long = 0,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && retryAt >= 0 && (error == null || error.length <= 2048))
        require(origin != OutgoingProgressOrigin.AUTOMATIC || consentRevision != null && consentRevision > 0 &&
            guard.completion != null && fields.volume == null && fields.entryId == null && fields.number != null &&
            !allowLower && !allowUnknown)
        require(state != OutgoingProgressState.SENDING && state != OutgoingProgressState.READBACK_REQUIRED || uncertain)
    }
}

@Serializable
data class OutgoingProgressConsent(val connection: OutgoingProgressConnection, val revision: Long, val automatic: Boolean) {
    init { require(revision > 0) }
}

@Serializable
data class OutgoingProgressAcknowledgment(val action: OutgoingProgressAction, val observed: OutgoingProgressRemote?, val at: Long) {
    init { require(action.state == OutgoingProgressState.ACKNOWLEDGED && at >= 0) }
}

@Serializable
data class OutgoingProgressLane(
    val provider: String,
    val accountId: String,
    val mangaId: Long,
    val primary: OutgoingProgressAction? = null,
    val later: OutgoingProgressAction? = null,
    val acknowledgment: OutgoingProgressAcknowledgment? = null,
) {
    init {
        TrackerOwner(provider, accountId)
        require(mangaId != 0L && (later == null || primary != null))
        require(listOfNotNull(primary, later, acknowledgment?.action).all {
            it.guard.connection.owner() == TrackerOwner(provider, accountId) && it.guard.snapshot.mangaId == mangaId
        })
        require(later == null || later.origin == OutgoingProgressOrigin.AUTOMATIC && !later.uncertain)
    }
}

data class OutgoingProgressPreview(
    val id: String,
    val guard: OutgoingProgressGuard,
    val fields: OutgoingProgressFields,
    val remote: OutgoingProgressRemote?,
    val requiresLowerConsent: Boolean,
    val requiresUnknownConsent: Boolean,
    val expiresAt: Long,
)

@Serializable
data class OutgoingProgressCooldown(val provider: String, val accountId: String, val until: Long, val error: String) {
    init { TrackerOwner(provider, accountId); require(until >= 0 && error.length <= 2048) }
}

data class OutgoingProgressView(
    val consents: List<OutgoingProgressConsent>,
    val lanes: List<OutgoingProgressLane>,
    val cooldowns: List<OutgoingProgressCooldown>,
)

internal fun decimal(value: String): java.math.BigDecimal {
    require(value.matches(Regex("[0-9]{1,38}(\\.[0-9]{1,12})?"))) { "Progress needs a finite nonnegative plain decimal" }
    return requireNotNull(TrackerRawProgress(value).numericNumber()) { "Progress exceeds the supported numeric bound" }
}
