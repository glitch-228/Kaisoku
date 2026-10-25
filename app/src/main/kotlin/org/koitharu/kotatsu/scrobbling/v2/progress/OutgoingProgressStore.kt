package org.koitharu.kotatsu.scrobbling.v2.progress

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
internal data class OutgoingProgressJournal(
    val version: Int = 1,
    val clock: Long = 0,
    val consents: List<OutgoingProgressConsent> = emptyList(),
    val lanes: List<OutgoingProgressLane> = emptyList(),
    val cooldowns: List<OutgoingProgressCooldown> = emptyList(),
) {
    fun validate() {
        require(version == 1 && clock >= 0 && consents.size <= 32 && cooldowns.size <= 32 && lanes.size <= 20_000)
        require(consents.map { it.connection.owner() }.distinct().size == consents.size &&
            consents.all { it.revision <= clock })
        require(cooldowns.map { it.provider to it.accountId }.distinct().size == cooldowns.size)
        require(lanes.map { Triple(it.provider, it.accountId, it.mangaId) }.distinct().size == lanes.size)
        val pending = lanes.flatMap { listOfNotNull(it.primary, it.later) }
        require(pending.size <= 1000 && pending.map { it.id }.distinct().size == pending.size)
        require(pending.none { it.state == OutgoingProgressState.ACKNOWLEDGED })
    }
}

/** Device consent, receipts and uncertain request bodies have no transfer codec and never enter backups. */
@Singleton
class OutgoingProgressStore internal constructor(private val file: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, "tracker-outgoing-progress-v1.json"),
    )
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private var loaded: OutgoingProgressJournal? = null
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    @Synchronized
    internal fun current(): OutgoingProgressJournal {
        loaded?.let { return it }
        val original = if (!file.exists()) OutgoingProgressJournal() else {
            require(file.length() <= MAX_BYTES) { "Outgoing progress journal exceeds its byte bound" }
            json.decodeFromString<OutgoingProgressJournal>(BoundedJson.validateObject(file.readBytes(), MAX_BYTES))
        }.also { it.validate() }
        val recovered = original.copy(lanes = original.lanes.map { lane ->
            val action = lane.primary
            if (action?.state != OutgoingProgressState.SENDING) lane else lane.copy(primary = action.copy(
                state = OutgoingProgressState.READBACK_REQUIRED, uncertain = true,
                error = "Interrupted progress request; check the remote entry before any further send",
            ))
        })
        if (recovered != original) save(recovered)
        loaded = recovered
        return recovered
    }

    @Synchronized
    internal fun mutate(block: (OutgoingProgressJournal) -> OutgoingProgressJournal): OutgoingProgressJournal {
        val before = current()
        val after = block(before)
        after.validate()
        if (after != before) {
            save(after)
            loaded = after
            revision.value += 1
        }
        return after
    }

    private fun save(value: OutgoingProgressJournal) {
        val bytes = json.encodeToString(OutgoingProgressJournal.serializer(), value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Outgoing progress journal exceeds its byte bound" }
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val stage = File(directory, "." + file.name + "." + UUID.randomUUID() + ".tmp")
        try {
            FileOutputStream(stage).use { it.write(bytes); it.fd.sync() }
            Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }

    private companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
