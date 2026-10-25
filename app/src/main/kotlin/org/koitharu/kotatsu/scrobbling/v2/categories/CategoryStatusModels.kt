package org.koitharu.kotatsu.scrobbling.v2.categories

import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.scrobbling.v2.TrackerConnection
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import java.security.MessageDigest

data class CategoryStatusChoice(val value: String, val label: String) {
    init {
        require(value.isNotBlank() && value.length <= 128 && label.isNotBlank() && label.length <= 256)
    }
}

data class CategoryStatusAccount(
    val connection: TrackerConnection,
    val displayName: String,
    val statuses: List<CategoryStatusChoice>,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 512)
        require(statuses.size in 1..32 && statuses.map { it.value }.distinct().size == statuses.size)
    }
}

/** Category creation time prevents a recycled numeric ID from inheriting a previous category's mapping. */
data class CategoryStatusBinding(val categoryId: Long, val categoryCreatedAt: Long, val title: String) {
    init {
        require(categoryId > 0 && categoryCreatedAt >= 0 && title.length <= 1024)
    }
}

data class CategoryStatusMapping(val categoryId: Long, val categoryCreatedAt: Long, val status: String) {
    init {
        require(categoryId > 0 && categoryCreatedAt >= 0 && status.isNotBlank() && status.length <= 128)
    }

    fun matches(category: CategoryStatusBinding): Boolean = categoryId == category.categoryId &&
        categoryCreatedAt == category.categoryCreatedAt
}

/** A committed local snapshot. Implementations must not fetch source metadata merely to enumerate links. */
data class CategoryStatusTitle(
    val mangaId: Long,
    val title: String,
    val sourceName: String,
    val sourceUrl: String,
    val editionKey: String?,
    val reference: TrackerReference,
    val linkRevision: String,
    val memberships: List<CategoryStatusBinding>,
) {
    init {
        require(mangaId != 0L && title.length <= 2048 && sourceName.isNotBlank() && sourceName.length <= 1024)
        require(sourceUrl.length <= 8192 && (editionKey == null || editionKey.length <= 4096))
        require(linkRevision.isNotBlank() && linkRevision.length <= 4096 && memberships.size <= 2000)
        require(memberships.map { it.categoryId }.distinct().size == memberships.size)
    }

    fun membershipFingerprint(): String = categoryDigest(memberships.sortedBy { it.categoryId }.flatMap {
        listOf(it.categoryId.toString(), it.categoryCreatedAt.toString())
    })

    fun identityFingerprint(): String = categoryDigest(listOf(mangaId.toString(), sourceName, sourceUrl,
        editionKey.orEmpty(), reference.owner.provider, reference.owner.accountId, reference.mediaId,
        reference.listEntryId.orEmpty(), linkRevision))
}

data class CategoryStatusRevision(val counter: Long, val replicaId: String) : Comparable<CategoryStatusRevision> {
    init {
        require(counter > 0 && replicaId.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
    }

    override fun compareTo(other: CategoryStatusRevision): Int = compareValuesBy(this, other,
        CategoryStatusRevision::counter, CategoryStatusRevision::replicaId)
}

data class CategoryStatusConfiguration(
    val owner: TrackerOwner,
    val revision: CategoryStatusRevision,
    val requestedEnabled: Boolean,
    val mappings: List<CategoryStatusMapping>,
    val deleted: Boolean = false,
) {
    init {
        require(mappings.size <= CategoryStatusLimits.MAPPINGS)
        require(mappings.map { it.categoryId }.distinct().size == mappings.size)
        require(!deleted || !requestedEnabled && mappings.isEmpty())
    }
}

data class CategoryStatusGuard(
    val connection: TrackerConnection,
    val mangaId: Long,
    val sourceName: String,
    val sourceUrl: String,
    val editionKey: String?,
    val reference: TrackerReference,
    val linkRevision: String,
    val configRevision: CategoryStatusRevision,
    val membershipFingerprint: String,
) {
    val owner: TrackerOwner get() = connection.owner

    init {
        require(reference.owner == connection.owner && mangaId != 0L)
        require(sourceName.isNotBlank() && sourceName.length <= 1024 && sourceUrl.length <= 8192)
        require(editionKey == null || editionKey.length <= 4096)
        require(linkRevision.isNotBlank() && linkRevision.length <= 4096)
        require(membershipFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    fun matches(title: CategoryStatusTitle): Boolean = mangaId == title.mangaId && sourceName == title.sourceName &&
        sourceUrl == title.sourceUrl && editionKey == title.editionKey && reference == title.reference &&
        linkRevision == title.linkRevision && membershipFingerprint == title.membershipFingerprint()

    companion object {
        fun create(connection: TrackerConnection, title: CategoryStatusTitle, revision: CategoryStatusRevision) =
            CategoryStatusGuard(connection, title.mangaId, title.sourceName, title.sourceUrl, title.editionKey,
                title.reference, title.linkRevision, revision, title.membershipFingerprint())
    }
}

/**
 * The root provider bridge owns account/session locking and actual status-only payloads. Readback/send callbacks
 * run after provider pacing, immediately before HTTP; the engine then checks the current journal and memberships.
 * No method may create a remote entry or change local favorites, progress, history or completion.
 */
interface CategoryStatusAccess {
    val changes: Flow<Unit>
    suspend fun accounts(): List<CategoryStatusAccount>
    /** Local-only verified read access for checking an already uncertain write after reconnect. */
    suspend fun readbackConnection(owner: TrackerOwner): TrackerConnection? =
        accounts().singleOrNull { it.connection.owner == owner }?.connection
    suspend fun categories(): List<CategoryStatusBinding>
    suspend fun titles(connection: TrackerConnection, limit: Int): List<CategoryStatusTitle>
    suspend fun title(connection: TrackerConnection, mangaId: Long): CategoryStatusTitle?
    suspend fun readback(guard: CategoryStatusGuard, beforeRequest: suspend () -> Unit): TrackerRemoteEntry?
    suspend fun updateStatusOnly(
        guard: CategoryStatusGuard,
        status: String,
        beforeSend: suspend () -> Unit,
    ): TrackerRemoteEntry
}

enum class CategoryStatusActionState {
    READY, CONFLICT, SENDING, READBACK_REQUIRED, REVIEW_REQUIRED, PAUSED,
}

data class CategoryStatusPending(
    val id: String,
    val title: String,
    val guard: CategoryStatusGuard,
    val state: CategoryStatusActionState,
    val status: String?,
    val conflicts: Map<String, List<CategoryStatusBinding>> = emptyMap(),
    val error: String? = null,
    val retryAtMillis: Long = 0,
    val attempts: Int = 0,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && title.length <= 2048)
        require(status == null || status.isNotBlank() && status.length <= 128)
        require(conflicts.size <= 32 && conflicts.values.sumOf { it.size } <= 2000)
        require(error == null || error.length <= 32 * 1024)
        require(retryAtMillis >= 0 && attempts in 0..10000)
        require(state != CategoryStatusActionState.CONFLICT || status == null && conflicts.size > 1)
        require(state == CategoryStatusActionState.CONFLICT || status != null)
    }
}

data class CategoryStatusPreviewItem(
    val title: CategoryStatusTitle,
    val guard: CategoryStatusGuard,
    val status: String?,
    val conflicts: Map<String, List<CategoryStatusBinding>>,
)

data class CategoryStatusPreview(
    val id: String,
    val account: CategoryStatusAccount,
    val expectedRevision: CategoryStatusRevision?,
    val enabled: Boolean,
    val mappings: List<CategoryStatusMapping>,
    val items: List<CategoryStatusPreviewItem>,
    val expiresAtMillis: Long,
)

data class CategoryStatusView(
    val configurations: List<CategoryStatusConfiguration>,
    val armedOwners: Set<TrackerOwner>,
    val pending: List<CategoryStatusPending>,
)

object CategoryStatusLimits {
    const val ACCOUNTS = 32
    const val MAPPINGS = 500
    const val BASELINES = 20_000
    const val PENDING = 1000
    const val BYTES = 16 * 1024 * 1024
    const val ACTIONS_PER_PASS = 8
}

internal fun categoryDigest(values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
