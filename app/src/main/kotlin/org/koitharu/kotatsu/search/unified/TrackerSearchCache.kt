package org.koitharu.kotatsu.search.unified

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.search.domain.SearchTrackerAccount
import java.io.File
import java.security.MessageDigest

@Serializable
internal data class TrackerLibrarySnapshot(
    val account: SearchTrackerAccount,
    val savedAt: Long,
    val titles: List<SavedTrackerTitle>,
)

@Serializable
internal data class CatalogueSnapshot(val key: String, val savedAt: Long, val page: CataloguePage)

/** Derived data only, in noBackup storage. Originals and library tables are never changed. */
internal class TrackerSearchCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun library(account: SearchTrackerAccount): TrackerLibrarySnapshot? = read("library_${account.provider}") {
        json.decodeFromString(TrackerLibrarySnapshot.serializer(), it)
    }?.takeIf { it.account == account && it.titles.size <= 20_000 }

    @Synchronized
    fun account(provider: String): SearchTrackerAccount? = read("account_$provider") {
        json.decodeFromString(SearchTrackerAccount.serializer(), it)
    }

    @Synchronized
    fun saveLibrary(account: SearchTrackerAccount, titles: List<SavedTrackerTitle>) {
        require(titles.size <= 20_000)
        write("library_${account.provider}", json.encodeToString(TrackerLibrarySnapshot.serializer(),
            TrackerLibrarySnapshot(account, now(), titles)))
        write("account_${account.provider}", json.encodeToString(SearchTrackerAccount.serializer(), account))
    }

    @Synchronized
    fun catalogue(key: String, stale: Boolean = false): CataloguePage? = read("catalogue_${hash(key)}") {
        json.decodeFromString(CatalogueSnapshot.serializer(), it)
    }?.takeIf { it.key == key && it.page.titles.size <= 100 && (stale || fresh(it.savedAt, 900_000)) }?.page

    @Synchronized
    fun saveCatalogue(key: String, page: CataloguePage) {
        require(page.titles.size <= 100)
        write("catalogue_${hash(key)}", json.encodeToString(CatalogueSnapshot.serializer(), CatalogueSnapshot(key, now(), page)))
        var count = 0
        directory.listFiles { f -> f.name.startsWith("catalogue_") && f.extension == "json" }
            ?.sortedByDescending(File::lastModified)?.forEachIndexed { index, file ->
                val snapshot = runCatching { json.decodeFromString(CatalogueSnapshot.serializer(), file.readText()) }.getOrNull()
                count += snapshot?.page?.titles?.size ?: 1001
                if (count > 1000 || index >= 64) file.delete()
            }
    }

    fun fresh(savedAt: Long, ttl: Long): Boolean = savedAt > 0 && now() >= savedAt && now() - savedAt < ttl

    private fun <T> read(name: String, parse: (String) -> T): T? = runCatching {
        val file = File(directory, "$name.json")
        require(file.length() in 1..32_000_000)
        parse(file.readText())
    }.getOrNull()

    private fun write(name: String, contents: String) {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$name.json")
        val staging = File(directory, "$name.tmp")
        staging.outputStream().use { it.write(contents.toByteArray()); it.fd.sync() }
        check(staging.renameTo(target)) { "Unable to save tracker search cache" }
    }

    private fun hash(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
