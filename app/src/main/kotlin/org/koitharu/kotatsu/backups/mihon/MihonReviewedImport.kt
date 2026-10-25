package org.koitharu.kotatsu.backups.mihon

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.parser.mihon.mihonStableId
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.longHashCode
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Preview and commit are deliberately separated; neither has an outgoing tracker dependency. */
@Singleton
class MihonReviewedImport internal constructor(
    private val context: Context,
    private val database: MangaDatabase,
    private val installedSources: () -> List<MihonMangaSource>,
    private val clock: () -> Long,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        database: MangaDatabase,
        manager: MihonExtensionManager,
    ) : this(context, database, manager::getInstalledSources, System::currentTimeMillis)

    suspend fun prepare(uri: Uri, selectedBindings: Map<Long, String> = emptyMap()): MihonImportPreview = withContext(Dispatchers.IO) {
        val decoded = decode(uri)
        val installed = runInterruptible { installedSources() }
        val binding = bind(decoded.backup, installed, selectedBindings)
        database.withTransaction {
            val mapped = mappedTitles(decoded.backup, binding)
            val previews = mapped.map { (item, id) ->
                currentCoroutineContext().ensureActive()
                val source = binding.names.getValue(item.source)
                val chapters = chapters(item, id, source)
                val byUrl = item.chapters.associateBy { it.url }
                val resume = resume(item, chapters)?.let { chapter -> MihonImportResumePreview(
                    chapter.chapterId, chapter.title, MihonBackupCodec.checkedPage(byUrl.getValue(chapter.url).lastPageRead)) }
                val existing = database.getMangaDao().find(id)?.manga
                require(existing == null || existing.source == source && (existing.url == item.url || existing.publicUrl == item.url ||
                    database.getChapterReadDao().policy(id)?.editionUrl == item.url)) {
                    "A different local edition has the same identifier; review its mapping first"
                }
                MihonImportTitlePreview(id, item.title.ifBlank { item.url }, source, item.url,
                    chapters.map { chapter -> MihonImportChapterPreview(chapter.chapterId, chapter.title,
                        chapter.url, byUrl.getValue(chapter.url).read) },
                    database.getHistoryDao().find(id) != null,
                    database.getChapterReadDao().policy(id) != null,
                    database.getMihonImportDao().receipt(decoded.digest, id) != null, resume)
            }
            MihonImportPreview(uri, decoded.digest, previews, binding.missing, binding.ambiguous,
                binding.names, sourceFingerprint(installed), database.mihonImportFingerprint(mapped.mapTo(HashSet()) { it.second }))
        }
    }

    suspend fun apply(preview: MihonImportPreview, choices: MihonImportChoices): MihonBackupManager.RestoreReport =
        withContext(Dispatchers.IO) {
            choices.validate(preview)
            require(preview.ambiguousSources.isEmpty()) { "Choose the extension package for each ambiguous source first" }
            val decoded = decode(preview.uri)
            if (decoded.digest != preview.digest) throw StaleMihonImportException()
            val installed = runInterruptible { installedSources() }
            if (sourceFingerprint(installed) != preview.sourceFingerprint) throw StaleMihonImportException()
            val binding = bind(decoded.backup, installed, preview.sourceBindings)
            if (binding.names != preview.sourceBindings) throw StaleMihonImportException()
            val expectedIds = preview.titles.mapTo(HashSet()) { it.mangaId }
            database.withTransaction {
                val mapped = mappedTitles(decoded.backup, binding)
                if (mapped.mapTo(HashSet()) { it.second } != expectedIds) throw StaleMihonImportException()
                if (database.mihonImportFingerprint(expectedIds) != preview.databaseFingerprint) throw StaleMihonImportException()
                val categories = Categories(decoded.backup.categories)
                val tagCache = HashMap<String, MutableMap<Long, TagEntity>>()
                val exact = ChapterReadRepository(database)
                var restored = 0
                var chapterCount = 0
                var pendingTracking = 0
                var exactCount = 0
                var unchanged = 0
                for ((item, id) in mapped) {
                    currentCoroutineContext().ensureActive()
                    if (id !in choices.selectedTitles) continue
                    if (database.getMihonImportDao().receipt(decoded.digest, id) != null) {
                        unchanged++
                        continue
                    }
                    val source = binding.names.getValue(item.source)
                    val foreignChapters = chapters(item, id, source)
                    val previous = database.getMangaDao().find(id)
                    if (previous == null) {
                        val tagsById = tagCache.getOrPut(source) {
                            database.getTagsDao().findTags(source).associateByTo(HashMap()) { it.id }
                        }
                        val tags = item.genre.mapNotNull { raw ->
                            val title = raw.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                            val key = title.lowercase(Locale.ROOT)
                            val tagId = "$source:$key".longHashCode()
                            tagsById[tagId]?.also { require(it.source == source && it.key == key) { "Conflicting tag identity" } }
                                ?: TagEntity(tagId, title, key, source, false).also { tagsById[tagId] = it }
                        }
                        database.getTagsDao().upsert(tags)
                        val sourceInfo = installed.singleOrNull { it.name == source }
                        database.getMangaDao().upsert(MangaEntity(id, item.title.ifBlank { item.url }, null,
                            item.url, item.url, -1f, sourceInfo?.isNsfwSource ?: true, null,
                            item.thumbnailUrl.orEmpty(), null, null,
                            listOfNotNull(item.author, item.artist).distinct().joinToString().ifBlank { null }, source), tags)
                    }
                    if (item.favorite) for (category in categories.resolve(item.categories)) {
                        val favourites = database.getFavouritesDao()
                        if (favourites.findForRestore(id, category) == null) favourites.upsert(FavouriteEntity(
                            id, category, 0, false,
                            item.dateAdded.takeIf { it > 0 } ?: clock(), 0))
                    }
                    val cached = database.getChaptersDao().findAll(id)
                    if (cached.isEmpty() && foreignChapters.isNotEmpty()) database.getChaptersDao().replaceAll(id, foreignChapters)
                    val currentChapters = cached.ifEmpty { foreignChapters }
                    if (id in choices.exactTitles && foreignChapters.isNotEmpty()) {
                        val manga = checkNotNull(database.getMangaDao().find(id)).toManga(currentChapters)
                        val flags = item.chapters.associate { it.url to it.read }
                        exact.importReviewed(manga, foreignChapters.map { it.asChapter() },
                            foreignChapters.filter { flags[it.url] == true }.mapTo(HashSet()) { it.chapterId }, decoded.digest)
                        exactCount++
                    }
                    val existingHistory = database.getHistoryDao().find(id)
                    if (existingHistory == null || id in choices.replaceResumeTitles) {
                        resume(item, foreignChapters)?.let { chapter ->
                            val original = item.chapters.single { it.url == chapter.url }
                            val timestamp = item.history.filter { it.url == chapter.url }.maxOfOrNull { it.lastRead }
                                ?.takeIf { it > 0 } ?: item.lastModifiedAt.takeIf { it > 0 } ?: clock()
                            database.getHistoryDao().upsert(HistoryEntity(id, existingHistory?.createdAt ?: timestamp,
                                timestamp, chapter.chapterId, MihonBackupCodec.checkedPage(original.lastPageRead), 0f,
                                ((chapter.index + if (original.read) 1 else 0).toFloat() / foreignChapters.size.coerceAtLeast(1))
                                    .coerceIn(0f, 1f), 0, foreignChapters.size))
                        }
                    }
                    val byUrl = foreignChapters.associateBy { it.url }
                    for (original in item.chapters.filter { it.bookmark }) {
                        val chapter = byUrl.getValue(original.url)
                        val page = MihonBackupCodec.checkedPage(original.lastPageRead)
                        val pageId = "$id:${original.url}:$page".longHashCode()
                        if (database.getBookmarksDao().find(pageId) == null) database.getBookmarksDao().insert(
                            BookmarkEntity(id, pageId, chapter.chapterId, page, 0, "", clock(), 0f))
                    }
                    val pending = item.tracking.mapIndexed { index, tracking -> PendingTrackerImportEntity(
                        MihonBackupCodec.sha256("${decoded.digest}:$id:$index".toByteArray()), decoded.digest, id,
                        source, item.url, providerKey(tracking.syncId), tracking.syncId, tracking.effectiveMediaId.toString(),
                        tracking.libraryId.toString(), tracking.lastChapterRead.toString(), tracking.status.toString(),
                        tracking.score.toString(), clock(), Json.encodeToString(tracking)) }
                    database.getMihonImportDao().put(pending)
                    database.getMihonImportDao().put(ForeignImportReceiptEntity(decoded.digest, id, source, item.url, clock()))
                    pendingTracking += pending.size
                    chapterCount += foreignChapters.size
                    restored++
                }
                currentCoroutineContext().ensureActive()
                if (sourceFingerprint(installedSources()) != preview.sourceFingerprint) throw StaleMihonImportException()
                currentCoroutineContext().ensureActive()
                MihonBackupManager.RestoreReport(restored, chapterCount, binding.missing, pendingTracking, unchanged,
                    exactCount)
            }
        }

    private suspend fun decode(uri: Uri): MihonBackupCodec.Decoded = runInterruptible(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use(MihonBackupCodec::decode)
            ?: throw IOException("Cannot open Mihon backup")
    }

    private data class Bindings(val names: Map<Long, String>, val missing: List<String>,
        val ambiguous: List<MihonImportSourceChoice>)

    private fun bind(backup: MihonBackup, installed: List<MihonMangaSource>, requested: Map<Long, String>): Bindings {
        val names = LinkedHashMap<Long, String>()
        val missing = ArrayList<String>()
        val ambiguous = ArrayList<MihonImportSourceChoice>()
        for (id in backup.manga.map { it.source }.distinct()) {
            val options = installed.filter { it.sourceId == id && it.isCatalogue }.distinctBy { it.name }.sortedBy { it.name }
            val fallback = "mihon:$id"
            val request = requested[id]?.takeUnless { it == fallback }
            val chosen = if (request?.startsWith("sha256:") == true) options.singleOrNull {
                MihonBackupCodec.sha256(it.name.toByteArray()) == request.removePrefix("sha256:")
            }?.name.also { require(it != null) { "The selected extension package changed; review its binding again" } } else request
            require(chosen == null || options.any { it.name == chosen }) { "The selected extension is no longer available" }
            names[id] = chosen ?: options.singleOrNull()?.name ?: fallback
            val title = backup.sources.firstOrNull { it.sourceId == id }?.name?.ifBlank { null } ?: fallback
            if (options.isEmpty()) missing += title
            else if (options.size > 1 && chosen == null) ambiguous += MihonImportSourceChoice(id, title,
                options.map { MihonImportSourceOption(it.name, it.displayName ?: it.name) })
        }
        return Bindings(names, missing, ambiguous)
    }

    private fun sourceFingerprint(sources: List<MihonMangaSource>): String = MihonBackupCodec.sha256(
        sources.sortedBy { it.name }.joinToString("\n") {
            "${it.name.length}:${it.name}:${it.recommendationVersion.length}:${it.recommendationVersion}:" +
                "${it.isCatalogue}:${it.isNsfwSource}:${it.isNovelSource}:${it.contentClass}"
        }.toByteArray())

    private fun titleId(source: String, url: String) = mihonStableId(source, url)

    private suspend fun mappedTitles(backup: MihonBackup, bindings: Bindings): List<Pair<MihonBackupManga, Long>> {
        val policies = database.getChapterReadDao().allPolicies().groupBy { it.source to it.editionUrl }
        val result = backup.manga.map { item ->
            val source = bindings.names.getValue(item.source)
            val known = database.getMangaDao().findForExactIdentity(source, item.url).map { it.manga.id } +
                policies[source to item.url].orEmpty().map { it.mangaId }
            val identities = known.toSet()
            require(identities.size <= 1) { "Several local editions match; review their source mapping first" }
            item to (identities.singleOrNull() ?: titleId(source, item.url))
        }
        require(result.map { it.second }.toSet().size == result.size) { "Conflicting title identities in backup" }
        return result
    }

    private fun chapters(item: MihonBackupManga, id: Long, source: String): List<ChapterEntity> = item.chapters
        .sortedWith(compareByDescending<MihonBackupChapter> { it.sourceOrder }.thenBy { it.chapterNumber })
        .mapIndexed { index, chapter -> ChapterEntity(mihonStableId(source, chapter.url), id, chapter.name,
            chapter.chapterNumber, 0, chapter.url, chapter.scanlator, chapter.dateUpload, null, source, index) }
        .also { require(it.map { chapter -> chapter.chapterId }.toSet().size == it.size) { "Conflicting chapter identifiers" } }

    private fun resume(item: MihonBackupManga, chapters: List<ChapterEntity>): ChapterEntity? {
        val byUrl = chapters.associateBy { it.url }
        val progressed = item.chapters.filter { it.read || it.lastPageRead > 0 }.mapTo(HashSet()) { it.url }
        return item.history.filter { it.url in byUrl }.maxByOrNull { it.lastRead }?.url?.let(byUrl::get)
            ?: chapters.lastOrNull { it.url in progressed }
    }

    private fun ChapterEntity.asChapter() = MangaChapter(chapterId, title, number, volume, url, scanlator,
        uploadDate, branch, MangaSource(source))

    private inner class Categories(private val foreign: List<MihonBackupCategory>) {
        private var orders: Map<Long, Long>? = null
        private var default: Long? = null

        suspend fun resolve(selected: List<Long>): List<Long> {
            val dao = database.getFavouriteCategoriesDao()
            if (orders == null) {
                val existing = dao.findAll().associateByTo(HashMap()) { it.title }
                orders = foreign.sortedBy { it.order }.mapNotNull { item ->
                    val title = item.name.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    val id = existing[title]?.categoryId?.toLong() ?: dao.insert(FavouriteCategoryEntity(0,
                        clock(), dao.getNextSortKey(), title, "NEWEST", true, true, 0)).also {
                        existing[title] = FavouriteCategoryEntity(it.toInt(), clock(), 0, title, "NEWEST", true, true, 0)
                    }
                    item.order to id
                }.toMap()
            }
            val found = selected.mapNotNull(checkNotNull(orders)::get).distinct()
            if (found.isNotEmpty()) return found
            if (default == null) default = dao.findAll().firstOrNull { it.title == "Default" }?.categoryId?.toLong()
                ?: dao.insert(FavouriteCategoryEntity(0, clock(), dao.getNextSortKey(), "Default", "NEWEST", true, true, 0))
            return listOf(checkNotNull(default))
        }
    }

    private fun providerKey(id: Int): String = when (id) {
        1 -> "mal"
        2 -> "anilist"
        3 -> "kitsu"
        4 -> "shikimori"
        else -> "mihon:$id"
    }
}
