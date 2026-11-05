package org.koitharu.kotatsu.reader.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A page the reader asked never to see again, known only by what its file contains. */
data class HiddenPage(
    /** SHA-256 of the page file, in hex. */
    val hash: String,
    val sizeBytes: Long,
    val addedAt: Long,
    /** Where it was hidden, for the list the pages can be shown again from. */
    val label: String,
)

/**
 * Pages hidden by exact content: two pages match only when their files are byte for byte the same, so a
 * different page can never be hidden by mistake. Kept in a file of its own, outside the library database and
 * the settings (nothing here goes into backups or sync), and bounded.
 */
@Singleton
class HiddenPagesStore internal constructor(private val file: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    private val lock = Any()
    private var pages: List<HiddenPage>? = null
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits whenever the set of hidden pages changes, so pages on screen can be checked again. */
    val changes: SharedFlow<Unit> = changesFlow.asSharedFlow()

    fun isEmpty(): Boolean = synchronized(lock) { load().isEmpty() }

    fun all(): List<HiddenPage> = synchronized(lock) { load() }

    /** Whether a page with this content and size is hidden. */
    fun contains(hash: String, sizeBytes: Long): Boolean = synchronized(lock) {
        load().any { it.hash == hash && it.sizeBytes == sizeBytes }
    }

    fun add(page: HiddenPage) {
        synchronized(lock) {
            val updated = (listOf(page) + load().filterNot { it.hash == page.hash }).take(MAX_PAGES)
            save(updated)
        }
        changesFlow.tryEmit(Unit)
    }

    fun remove(hashes: Collection<String>) {
        synchronized(lock) { save(load().filterNot { it.hash in hashes }) }
        changesFlow.tryEmit(Unit)
    }

    fun clear() {
        synchronized(lock) { save(emptyList()) }
        changesFlow.tryEmit(Unit)
    }

    private fun load(): List<HiddenPage> {
        pages?.let { return it }
        val loaded = runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                val array = JSONArray(file.readText())
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    HiddenPage(
                        hash = item.getString("hash"),
                        sizeBytes = item.getLong("size"),
                        addedAt = item.optLong("at"),
                        label = item.optString("label"),
                    )
                }
            }
        }.getOrDefault(emptyList())
        pages = loaded
        return loaded
    }

    private fun save(value: List<HiddenPage>) {
        pages = value
        runCatching {
            val array = JSONArray()
            value.forEach {
                array.put(JSONObject().put("hash", it.hash).put("size", it.sizeBytes).put("at", it.addedAt).put("label", it.label))
            }
            val temporary = File(file.parentFile, "$FILE_NAME.tmp")
            temporary.writeText(array.toString())
            temporary.renameTo(file)
        }
    }

    private companion object {
        const val FILE_NAME = "hidden_pages.json"
        const val MAX_PAGES = 500
    }
}
