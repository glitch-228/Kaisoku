package org.koitharu.kotatsu.scrobbling.v2.categories

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.scrobbling.v2.TrackerOwner
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal data class CategoryStatusKey(val owner: TrackerOwner, val mangaId: Long)

internal data class CategoryStatusBaseline(
    val key: CategoryStatusKey,
    val identity: String,
    val memberships: String,
    val configuration: CategoryStatusRevision,
)

internal data class CategoryStatusJournal(
    val replicaId: String = UUID.randomUUID().toString(),
    val clock: Long = 0,
    val configurations: Map<TrackerOwner, CategoryStatusConfiguration> = emptyMap(),
    val armed: Map<TrackerOwner, CategoryStatusRevision> = emptyMap(),
    val baselines: Map<CategoryStatusKey, CategoryStatusBaseline> = emptyMap(),
    val pending: Map<CategoryStatusKey, CategoryStatusPending> = emptyMap(),
) {
    fun isArmed(config: CategoryStatusConfiguration): Boolean = !config.deleted && config.requestedEnabled &&
        armed[config.owner] == config.revision

    fun validate() {
        require(replicaId.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && clock >= 0)
        require(configurations.size <= CategoryStatusLimits.ACCOUNTS) { "Too many tracker account mappings" }
        require(baselines.size <= CategoryStatusLimits.BASELINES) { "Category mapping title limit reached" }
        require(pending.size <= CategoryStatusLimits.PENDING) { "Category mapping pending-work limit reached" }
        require(configurations.all { it.key == it.value.owner && it.value.revision.counter <= clock })
        require(armed.all { (owner, revision) -> configurations[owner]?.revision == revision })
        require(baselines.all { (key, value) -> key == value.key && key.mangaId != 0L &&
            value.identity.matches(HASH) && value.memberships.matches(HASH) })
        require(pending.all { (key, value) -> key == CategoryStatusKey(value.guard.owner, value.guard.mangaId) })
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
    }
}

data class CategoryStatusTransferPreview(val changedOwners: Set<TrackerOwner>, val conflicts: List<String>)

/** Device queues/arming never leave this file. Only versioned configuration intent has a transfer codec. */
@Singleton
class CategoryStatusStore internal constructor(private val file: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, "tracker_categories/state-v1.json"),
    )

    private var loaded: CategoryStatusJournal? = null
    private val updates = MutableStateFlow(0L)
    val changes = updates.asStateFlow()

    @Synchronized
    internal fun current(): CategoryStatusJournal {
        loaded?.let { return it }
        val original = if (!file.exists()) CategoryStatusJournal() else {
            require(file.length() <= CategoryStatusLimits.BYTES) { "Category mapping journal is too large" }
            CategoryStatusCodec.decodeJournal(file.readText(Charsets.UTF_8))
        }
        original.validate()
        val recovered = original.copy(pending = original.pending.mapValues { (_, action) ->
            if (action.state == CategoryStatusActionState.SENDING) action.copy(
                state = CategoryStatusActionState.READBACK_REQUIRED,
                error = "The previous send was interrupted. Check the remote status before sending again.",
            ) else action
        })
        if (recovered != original) save(recovered)
        loaded = recovered
        return recovered
    }

    @Synchronized
    internal fun mutate(checkCurrent: () -> Unit = {}, block: (CategoryStatusJournal) -> CategoryStatusJournal): CategoryStatusJournal {
        checkCurrent()
        val old = current()
        val next = block(old)
        if (old == next) return old
        next.validate()
        save(next, checkCurrent)
        loaded = next
        updates.value = Math.addExact(updates.value, 1)
        return next
    }

    internal fun view(): CategoryStatusView = current().let { journal ->
        CategoryStatusView(journal.configurations.values.sortedWith(compareBy({ it.owner.provider }, { it.owner.accountId })),
            journal.configurations.values.filter(journal::isArmed).mapTo(linkedSetOf()) { it.owner },
            journal.pending.values.sortedWith(compareBy({ it.guard.owner.provider }, { it.guard.owner.accountId },
                { it.title }, { it.guard.mangaId })))
    }

    suspend fun exportTransfer(): String = withContext(Dispatchers.IO) {
        CategoryStatusCodec.encodeTransfer(current().configurations.values.toList())
    }

    suspend fun previewTransfer(value: String): CategoryStatusTransferPreview = withContext(Dispatchers.IO) {
        val incoming = CategoryStatusCodec.decodeTransfer(value)
        preview(current(), incoming)
    }

    /** The same monitor covers conflict validation and replacement, so restore cannot partially merge. */
    suspend fun mergeTransfer(
        value: String,
        categoryRemaps: Map<Pair<Long, Long>, Pair<Long, Long>> = emptyMap(),
        checkCurrent: () -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val incoming = CategoryStatusCodec.decodeTransfer(value)
        mutate(checkCurrent) { old ->
            val preview = preview(old, incoming)
            check(preview.conflicts.isEmpty()) { preview.conflicts.joinToString("\n") }
            var clock = maxOf(old.clock, incoming.maxOfOrNull { it.revision.counter } ?: 0)
            val changed = incoming.filter { candidate ->
                old.configurations[candidate.owner]?.revision?.let { candidate.revision > it } ?: true
            }.map { candidate ->
                val mappings = candidate.mappings.map { mapping ->
                    categoryRemaps[mapping.categoryId to mapping.categoryCreatedAt]?.let { target ->
                        mapping.copy(categoryId = target.first, categoryCreatedAt = target.second)
                    } ?: mapping
                }
                if (mappings == candidate.mappings) candidate else {
                    clock = Math.addExact(clock, 1)
                    candidate.copy(revision = CategoryStatusRevision(clock, old.replicaId), mappings = mappings)
                }
            }
            if (changed.isEmpty()) return@mutate old
            val owners = changed.mapTo(hashSetOf()) { it.owner }
            old.copy(
                clock = clock,
                configurations = old.configurations + changed.associateBy { it.owner },
                armed = old.armed - owners,
                baselines = old.baselines.filterKeys { it.owner !in owners },
                pending = old.pending.mapNotNull { (key, action) ->
                    when {
                        key.owner !in owners -> key to action
                        action.state in setOf(CategoryStatusActionState.SENDING,
                            CategoryStatusActionState.READBACK_REQUIRED) -> key to action.copy(
                            state = CategoryStatusActionState.READBACK_REQUIRED,
                            error = "Restored mappings are disabled. Check this previous send before retrying.",
                        )
                        else -> null
                    }
                }.toMap(),
            )
        }
        Unit
    }

    private fun preview(old: CategoryStatusJournal, incoming: List<CategoryStatusConfiguration>): CategoryStatusTransferPreview {
        val changed = linkedSetOf<TrackerOwner>()
        val conflicts = arrayListOf<String>()
        for (candidate in incoming) {
            val previous = old.configurations[candidate.owner]
            if (previous == null || candidate.revision > previous.revision) changed += candidate.owner
            else if (candidate.revision == previous.revision && candidate != previous) {
                conflicts += "Conflicting category mappings for ${candidate.owner.provider}; review this transfer."
            }
        }
        if ((old.configurations.keys + incoming.map { it.owner }).size > CategoryStatusLimits.ACCOUNTS) {
            conflicts += "Too many tracker account mappings"
        }
        return CategoryStatusTransferPreview(changed, conflicts)
    }

    private fun save(value: CategoryStatusJournal, checkCurrent: () -> Unit = {}) {
        val bytes = CategoryStatusCodec.encodeJournal(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= CategoryStatusLimits.BYTES) { "Category mapping journal is too large" }
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create category mapping storage" }
        val stage = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(stage).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            checkCurrent()
            Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            stage.delete()
        }
    }
}
