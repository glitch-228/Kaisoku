package org.koitharu.kotatsu.scrobbling.v2.bulk

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.core.util.BoundedJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class BulkTrackerJournal(val version: Int = 1, val drafts: List<BulkTrackerDraft> = emptyList(),
    val libraries: List<BulkTrackerLibrary> = emptyList()) {
    fun validate() {
        require(version == 1 && drafts.size <= 32 && libraries.size <= 32)
        require(drafts.map { it.id }.distinct().size == drafts.size)
        require(libraries.map { it.account.provider to it.account.account }.distinct().size == libraries.size)
        require(libraries.sumOf { it.entries.size } <= 20_000)
    }
}

/** Private unresolved choices are excluded from Android backup and all replica codecs. */
@Singleton
class BulkTrackerStore internal constructor(private val file: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "tracker-bulk-v1.json"))
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private var loaded: BulkTrackerJournal? = null
    private val revisions = MutableStateFlow(0L)
    val changes = revisions.asStateFlow()

    @Synchronized
    internal fun current(): BulkTrackerJournal {
        loaded?.let { return it }
        val value = if (!file.exists()) BulkTrackerJournal() else {
            require(file.length() <= MAX_BYTES) { "Bulk tracker journal is too large" }
            json.decodeFromString<BulkTrackerJournal>(BoundedJson.validateObject(file.readBytes(), MAX_BYTES))
        }
        value.validate()
        val recovered = value.copy(drafts = value.drafts.map { draft -> draft.copy(items = draft.items.map { item ->
            if (item.state == BulkTrackerItemState.CREATING) item.copy(state = BulkTrackerItemState.READBACK_REQUIRED,
                error = "Interrupted remote addition; check its remote entry before retrying") else item
        }) })
        if (value != recovered) save(recovered)
        loaded = recovered
        return recovered
    }

    @Synchronized
    internal fun mutate(block: (BulkTrackerJournal) -> BulkTrackerJournal): BulkTrackerJournal {
        val before = current()
        val after = block(before)
        after.validate()
        if (after != before) {
            save(after)
            loaded = after
            revisions.value += 1
        }
        return after
    }

    private fun save(value: BulkTrackerJournal) {
        val bytes = json.encodeToString(BulkTrackerJournal.serializer(), value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Bulk tracker journal is too large" }
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val stage = File(directory, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(stage).use { it.write(bytes); it.fd.sync() }
            Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }

    private companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
