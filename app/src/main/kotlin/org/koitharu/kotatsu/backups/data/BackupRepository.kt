package org.koitharu.kotatsu.backups.data

import android.content.Context
import android.net.Uri
import androidx.collection.ArrayMap
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import kotlinx.serialization.decodeFromString
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.backups.data.model.BackupIndex
import org.koitharu.kotatsu.backups.data.model.BookmarkBackup
import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.backups.data.model.FavoriteOrderBackup
import org.koitharu.kotatsu.backups.data.model.HistoryBackup
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.backups.data.model.MangaPreferencesBackup
import org.koitharu.kotatsu.backups.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backups.data.model.SourceBackup
import org.koitharu.kotatsu.backups.data.model.StatisticBackup
import org.koitharu.kotatsu.backups.data.model.TrackBackup
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.migrations.MangaIdentityMerge
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.data.PersistableFilter
import org.koitharu.kotatsu.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.sync.drive.DriveReplicaCodec.readBounded
import org.koitharu.kotatsu.sync.drive.DriveReplicaJournal
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@Reusable
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val tapGridSettings: TapGridSettings,
    private val mangaSourcesRepository: MangaSourcesRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    private val metadataRefreshSettings: MetadataRefreshSettings = MetadataRefreshSettings(context),
    private val remoteLibraries: org.koitharu.kotatsu.remote.library.RemoteLibraryPersistence =
        org.koitharu.kotatsu.remote.library.RemoteLibraryPersistence(context),
    private val trackerCategories: org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusStore =
        org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusStore(context),
) {

    private val chapterReads = org.koitharu.kotatsu.history.exact.ChapterReadRepository(database)
    private val trackerLinks = org.koitharu.kotatsu.scrobbling.v2.TrackerLinkRepository(database)
    private val metadataOverrides = org.koitharu.kotatsu.sync.extended.MetadataOverrideTransfer(context, database)
    private val metadataJournal = org.koitharu.kotatsu.sync.extended.MetadataOverrideJournal(database)

    private val json = Json {
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
        encodeDefaults = false
        ignoreUnknownKeys = true
        useAlternativeNames = false
    }

    suspend fun createBackup(
        output: ZipOutputStream,
        progress: FlowCollector<Progress>?,
        sections: Set<BackupSection> = BackupSection.entries.toSet(),
        includeCustomCovers: Boolean = false,
    ) {
        output.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, sections.size)
        for (section in BackupSection.entries.filter(sections::contains)) {
            when (section) {
                BackupSection.INDEX -> output.writeJsonArray(
                    section = BackupSection.INDEX,
                    data = flowOf(BackupIndex()),
                    serializer = serializer(),
                )

                BackupSection.HISTORY -> output.writeJsonArray(
                    section = BackupSection.HISTORY,
                    data = database.getHistoryDao().dump().map { HistoryBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.CATEGORIES -> output.writeJsonArray(
                    section = BackupSection.CATEGORIES,
                    data = database.getFavouriteCategoriesDao().dump().asFlow().map { CategoryBackup(it, categorySyncUid(it.categoryId)) },
                    serializer = serializer(),
                )

                BackupSection.FAVOURITES -> output.writeJsonArray(
                    section = BackupSection.FAVOURITES,
                    data = database.getFavouritesDao().dump().map { FavouriteBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.FAVORITE_ORDER -> output.writeJsonArray(
                    section = section,
                    data = database.getFavoriteOrderDao().dump().asFlow().map { order ->
                        FavoriteOrderBackup(order.mangaId, order.scopeId, order.rank, order.updatedAt, order.revision,
                            MangaBackup(checkNotNull(database.getMangaDao().find(order.mangaId))))
                    },
                    serializer = serializer(),
                )

                BackupSection.SETTINGS -> output.writeString(
                    section = BackupSection.SETTINGS,
                    data = dumpSettings(),
                )

                BackupSection.SETTINGS_READER_GRID -> output.writeString(
                    section = BackupSection.SETTINGS_READER_GRID,
                    data = dumpReaderGridSettings(),
                )

                BackupSection.BOOKMARKS -> output.writeJsonArray(
                    section = BackupSection.BOOKMARKS,
                    data = database.getBookmarksDao().dump().map { BookmarkBackup(it.first, it.second) },
                    serializer = serializer(),
                )

                BackupSection.SOURCES -> output.writeJsonArray(
                    section = BackupSection.SOURCES,
                    data = database.getSourcesDao().dumpEnabled().map { SourceBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.SCROBBLING -> output.writeJsonArray(
                    section = BackupSection.SCROBBLING,
                    data = database.getScrobblingDao().dumpEnabled().map { ScrobblingBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.STATS -> output.writeJsonArray(
                    section = BackupSection.STATS,
                    data = database.getStatsDao().dumpEnabled().map { StatisticBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.SAVED_FILTERS -> {
                    val sources = mangaSourcesRepository.getEnabledSources().let {
                        if (includeCustomCovers) (it + mangaSourcesRepository.getDisabledSources()).distinctBy { s -> s.name } else it
                    }
                    val filters = sources.filter { source ->
                        !includeCustomCovers || File(context.applicationInfo.dataDir,
                            "shared_prefs/${source.name.replace(File.separatorChar, '$')}.xml").isFile
                    }.flatMap { source ->
                        savedFiltersRepository.getAll(source)
                    }
                    output.writeJsonArray(
                        section = BackupSection.SAVED_FILTERS,
                        data = filters.asFlow(),
                        serializer = serializer(),
                    )
                }

                BackupSection.MANGA_PREFERENCES -> output.writeJsonArray(
                    section = BackupSection.MANGA_PREFERENCES,
                    data = database.getPreferencesDao().dump().asFlow().map { prefs ->
                        val manga = checkNotNull(database.getMangaDao().find(prefs.mangaId))
                        if (!org.koitharu.kotatsu.core.archive.ArchivePrivacy.canPersistMetadata(manga.toManga(null))) {
                            val reference = org.koitharu.kotatsu.core.archive.ArchivePrivacy.publicReference(manga.toManga(null))
                            val publicManga = manga.copy(manga = manga.manga.copy(title = reference.title,
                                altTitles = null, authors = null, coverUrl = "", largeCoverUrl = null), tags = emptyList())
                            MangaPreferencesBackup(publicManga, prefs.copy(titleOverride = null, coverUrlOverride = null,
                                descriptionOverride = null, contentRatingOverride = null))
                        } else if (includeCustomCovers) backupCustomCover(MangaPreferencesBackup(manga, prefs))
                        else MangaPreferencesBackup(manga, prefs).withoutCustomCover()
                    },
                    serializer = serializer(),
                )

                BackupSection.TRACKS -> output.writeJsonArray(
                    section = BackupSection.TRACKS,
                    data = database.getTracksDao().dump().asFlow().map { track ->
                        TrackBackup(checkNotNull(database.getMangaDao().find(track.mangaId)), track)
                    },
                    serializer = serializer(),
                )

                BackupSection.EXACT_COMPLETION -> output.writeJsonArray(
                    section = section,
                    data = flowOf(chapterReads.export()),
                    serializer = serializer(),
                )

                BackupSection.FOREIGN_IMPORT_STATE -> output.writeJsonArray(
                    section = section,
                    data = flowOf(org.koitharu.kotatsu.backups.mihon.ForeignImportTransfer(database).export()),
                    serializer = serializer(),
                )

                BackupSection.REMOTE_LIBRARIES -> output.writeString(section, remoteLibraries.exportTransfer())
                BackupSection.TRACKER_LINKS -> output.writeString(section,
                    org.koitharu.kotatsu.scrobbling.v2.TrackerLinkTransfer.encode(trackerLinks.export()))
                BackupSection.TRACKER_CATEGORIES -> output.writeString(section, trackerCategories.exportTransfer())
                BackupSection.METADATA_OVERRIDES -> output.writeString(section,
                    metadataOverrides.export(includeCovers = includeCustomCovers).toString(Charsets.UTF_8))
            }
            commonProgress++
            progress?.emit(commonProgress)
        }
    }

    suspend fun restoreBackup(
        input: ZipInputStream,
        sections: Set<BackupSection>,
        progress: FlowCollector<Progress>?,
        isMerge: Boolean = false,
        replaceSections: Set<BackupSection> = emptySet(),
        preserveSettingsKeys: Set<String> = emptySet(),
        authoritativeRecords: Boolean = false,
        mergedMangaIds: MutableMap<Long, Long>? = null,
    ): CompositeResult {
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, sections.size)
        val categoryIdRemap = mutableMapOf<Long, Long>()
        val categoryCreationWitnesses = mutableMapOf<Long, Long>()
        var pendingTrackerCategories: String? = null
        val pendingPreferenceMetadata = linkedMapOf<Long, MangaPreferencesBackup>()
        var preferenceMetadataBytes = 0L
        var metadataSectionCount = 0
        var pendingMetadata: ByteArray? = null
        var entry = input.nextEntry
        var result = CompositeResult.EMPTY
        while (entry != null) {
            val section = BackupSection.of(entry)
            if (section in sections) {
                result += when (section) {
                    BackupSection.INDEX -> CompositeResult.EMPTY // useless in our case
                    BackupSection.HISTORY -> input.readJsonArray<HistoryBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        val incoming = it.toEntity()
                        val existing = getHistoryDao().findForRestore(incoming.mangaId)
                        if (authoritativeRecords || !isMerge || existing == null || incoming.eventTimestamp > existing.eventTimestamp) {
                            getHistoryDao().restore(incoming)
                        }
                    }

                    BackupSection.CATEGORIES -> input.readJsonArray<CategoryBackup>(serializer()).restoreToDb {
                        restoreCategory(it, isMerge && !authoritativeRecords, categoryIdRemap)
                        categoryCreationWitnesses[it.categoryId.toLong()] = it.createdAt
                    }

                    BackupSection.FAVOURITES -> input.readJsonArray<FavouriteBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        val categoryId = categoryIdRemap[it.categoryId] ?: it.categoryId
                        val incoming = it.toEntity(categoryId)
                        val existing = getFavouritesDao().findForRestore(incoming.mangaId, incoming.categoryId)
                        if (authoritativeRecords || !isMerge || existing == null || incoming.eventTimestamp > existing.eventTimestamp) {
                            getFavouritesDao().upsert(incoming)
                        }
                    }

                    BackupSection.FAVORITE_ORDER -> input.readJsonArray<FavoriteOrderBackup>(serializer()).restoreToDb {
                        val scope = if (it.scopeId == 0L) 0L else categoryIdRemap[it.scopeId] ?: it.scopeId
                        val incoming = it.toEntity(scope)
                        require(scope == 0L || getFavouriteCategoriesDao().findForRestore(scope.toInt()) != null) {
                            "Manual order references an unknown category"
                        }
                        val previous = getFavoriteOrderDao().find(incoming.mangaId, scope)
                        if (authoritativeRecords || !isMerge || previous == null || incoming.isNewerThan(previous)) {
                            upsertManga(it.manga)
                            getFavoriteOrderDao().upsert(incoming)
                        }
                    }

                    BackupSection.SETTINGS -> input.readMap().let {
                        settings.upsertAll(it.filterKeys { key -> !SensitiveBackupKeys.isSensitive(key) && key !in preserveSettingsKeys }, isMerge)
                        CompositeResult.success()
                    }

                    BackupSection.SETTINGS_READER_GRID -> input.readMap().let {
                        tapGridSettings.upsertAll(it, isMerge && BackupSection.SETTINGS_READER_GRID !in replaceSections)
                        CompositeResult.success()
                    }

                    BackupSection.BOOKMARKS -> input.readJsonArray<BookmarkBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
                    }

                    BackupSection.SOURCES -> input.readJsonArray<SourceBackup>(serializer()).restoreToDb {
                        getSourcesDao().upsert(it.toEntity())
                    }

                    BackupSection.SCROBBLING -> input.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb {
                        getScrobblingDao().upsert(it.toEntity())
                    }

                    BackupSection.STATS -> input.readJsonArray<StatisticBackup>(serializer()).restoreToDb {
                        getStatsDao().upsert(it.toEntity())
                    }

                    BackupSection.SAVED_FILTERS -> input.readJsonArray<PersistableFilter>(serializer())
                        .restoreWithoutTransaction {
                            savedFiltersRepository.save(it)
                        }

                    BackupSection.MANGA_PREFERENCES -> input.readJsonArray<MangaPreferencesBackup>(serializer())
                        .restoreToDb {
                            upsertManga(it.manga)
                            require(it.metadataVersion in 0..1) { "Unsupported metadata override version" }
                            require(it.manga.id !in pendingPreferenceMetadata) { "Duplicate preference metadata identity" }
                            preferenceMetadataBytes += json.encodeToString(serializer<MangaPreferencesBackup>(), it).length.toLong() * 4
                            require(pendingPreferenceMetadata.size < 20_000 && preferenceMetadataBytes <=
                                org.koitharu.kotatsu.sync.extended.MetadataOverrideTransfer.MAX_BYTES) { "Preference metadata is too large" }
                            val old = getPreferencesDao().find(it.manga.id)
                            // Causal metadata is applied after section discovery, independent of ZIP order.
                            getPreferencesDao().upsert(it.toEntity(old?.coverUrlOverride, old?.descriptionOverride).copy(
                                titleOverride = old?.titleOverride, coverUrlOverride = old?.coverUrlOverride,
                                descriptionOverride = old?.descriptionOverride, contentRatingOverride = old?.contentRatingOverride,
                            ))
                            pendingPreferenceMetadata[it.manga.id] = it
                        }

                    BackupSection.TRACKS -> input.readJsonArray<TrackBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        getTracksDao().upsert(it.toEntity())
                    }

                    BackupSection.EXACT_COMPLETION -> CompositeResult.EMPTY + runCatchingCancellable {
                        val bytes = input.readBounded(org.koitharu.kotatsu.history.exact.ChapterReadTransfer.MAX_BYTES)
                        val envelope = json.decodeFromString<List<org.koitharu.kotatsu.history.exact.ChapterReadEnvelope>>(
                            bytes.decodeToString(),
                        ).single()
                        chapterReads.merge(envelope)
                    }

                    BackupSection.FOREIGN_IMPORT_STATE -> CompositeResult.EMPTY + runCatchingCancellable {
                        val bytes = input.readBounded(org.koitharu.kotatsu.backups.mihon.ForeignImportTransfer.MAX_BYTES)
                        val envelope = json.decodeFromString<List<org.koitharu.kotatsu.backups.mihon.ForeignImportEnvelope>>(
                            bytes.decodeToString(),
                        ).single()
                        org.koitharu.kotatsu.backups.mihon.ForeignImportTransfer(database).merge(envelope)
                    }

                    BackupSection.REMOTE_LIBRARIES -> CompositeResult.EMPTY + runCatchingCancellable {
                        val value = json.parseToJsonElement(input.readBounded(16 * 1024 * 1024 + 2).decodeToString())
                        val sectionValue = (value as? kotlinx.serialization.json.JsonArray)?.singleOrNull()
                            ?: error("Invalid remote-library backup section")
                        remoteLibraries.mergeTransfer(sectionValue.toString())
                    }

                    BackupSection.TRACKER_LINKS -> CompositeResult.EMPTY + runCatchingCancellable {
                        val sectionValue = readExtendedObject(input,
                            org.koitharu.kotatsu.scrobbling.v2.TrackerLinkTransfer.MAX_BYTES)
                        trackerLinks.merge(org.koitharu.kotatsu.scrobbling.v2.TrackerLinkTransfer.decode(
                            sectionValue.toByteArray(Charsets.UTF_8)))
                    }

                    BackupSection.TRACKER_CATEGORIES -> CompositeResult.EMPTY + runCatchingCancellable {
                        check(pendingTrackerCategories == null) { "Duplicate tracker mapping section" }
                        val value = readExtendedObject(input,
                            org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusLimits.BYTES)
                        org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusCodec.decodeTransfer(value)
                        pendingTrackerCategories = value
                    }

                    BackupSection.METADATA_OVERRIDES -> CompositeResult.EMPTY + runCatchingCancellable {
                        metadataSectionCount++
                        if (metadataSectionCount > 1) pendingMetadata = null
                        require(metadataSectionCount == 1) { "Duplicate metadata override section" }
                        pendingMetadata = readExtendedObject(input,
                            org.koitharu.kotatsu.sync.extended.MetadataOverrideTransfer.MAX_BYTES).toByteArray(Charsets.UTF_8)
                    }

                    null -> CompositeResult.EMPTY // skip unknown entries
                }
                commonProgress++
                progress?.emit(commonProgress)
            }
            input.closeEntry()
            entry = input.nextEntry
        }
        if (metadataSectionCount == 1 && pendingMetadata != null) {
            result += runCatchingCancellable { metadataOverrides.merge(requireNotNull(pendingMetadata)) }
        } else if (metadataSectionCount == 0) {
            for (intent in pendingPreferenceMetadata.values) result += runCatchingCancellable {
                database.withTransaction {
                    val manga = requireNotNull(database.getMangaDao().find(intent.manga.id))
                    require(manga.manga.source == intent.manga.source && manga.manga.url == intent.manga.url) {
                        "Preference metadata source edition changed during restore"
                    }
                    val old = requireNotNull(database.getPreferencesDao().find(intent.manga.id))
                    if (!org.koitharu.kotatsu.core.archive.ArchivePrivacy.canPersistMetadata(manga.toManga(null))) {
                        // Reader appearance was restored above; archive metadata stays session-only.
                    } else if (authoritativeRecords) {
                        database.getPreferencesDao().upsert(old.copy(titleOverride = intent.titleOverride,
                            coverUrlOverride = restoreCustomCover(intent), contentRatingOverride = intent.contentRatingOverride,
                            descriptionOverride = if (intent.metadataVersion == 0) old.descriptionOverride else intent.descriptionOverride))
                    } else {
                        val next = old.copy(titleOverride = intent.titleOverride, coverUrlOverride = restoreCustomCover(intent),
                            contentRatingOverride = intent.contentRatingOverride,
                            descriptionOverride = if (intent.metadataVersion == 0) old.descriptionOverride else intent.descriptionOverride)
                        database.getPreferencesDao().upsert(next)
                        metadataJournal.record(manga.manga, next, explicitReset = true)
                    }
                }
            }
        }
        // Invalid or newer sections never leave a legacy mirror over the durable local fields.
        if (metadataSectionCount > 0 || authoritativeRecords) {
            result += runCatchingCancellable { metadataJournal.overlayOwned() }
        }

        pendingTrackerCategories?.let { value ->
            result += runCatchingCancellable {
                val bindings = database.withTransaction {
                    categoryIdRemap.mapNotNull { (old, target) ->
                        val created = categoryCreationWitnesses[old] ?: return@mapNotNull null
                        require(target in 1..Int.MAX_VALUE.toLong()) { "Invalid restored category identity" }
                        val category = database.getFavouriteCategoriesDao().findForRestore(target.toInt())
                            ?: error("Restored tracker mapping category is unavailable")
                        (old to created) to (target to category.createdAt)
                    }.toMap()
                }
                trackerCategories.mergeTransfer(value, bindings)
            }
        }
        if (sections.any { it.canContainMangaIdentityDuplicates() }) {
            result += runCatchingCancellable {
                val mergedIds = database.withTransaction {
                    MangaIdentityMerge.mergeDuplicateMangaByIdentity(database.openHelper.writableDatabase)
                }
                if (mergedMangaIds == null) {
                    metadataRefreshSettings.copyMergedTitleFlags(mergedIds)
                } else {
                    // A caller with an enclosing transaction copies only after that transaction commits.
                    mergedMangaIds.putAll(mergedIds)
                }
            }
        }
        return result
    }

    private fun readExtendedObject(input: InputStream, limit: Int): String {
        val raw = input.readBounded(limit + 2).decodeToString(throwOnInvalidSequence = true)
        org.koitharu.kotatsu.core.util.BoundedJson.validateObject(
            ("{\"section\":" + raw + "}").toByteArray(Charsets.UTF_8), limit + 14)
        val value = json.parseToJsonElement(raw)
        return ((value as? kotlinx.serialization.json.JsonArray)?.singleOrNull()
            as? kotlinx.serialization.json.JsonObject)?.toString() ?: error("Invalid extended backup section")
    }

    private suspend fun <T> ZipOutputStream.writeJsonArray(
        section: BackupSection,
        data: Flow<T>,
        serializer: SerializationStrategy<T>,
    ) {
        data.onStart {
            putNextEntry(ZipEntry(section.entryName))
            write("[")
        }.onCompletion { error ->
            if (error == null) {
                write("]")
            }
            closeEntry()
            flush()
        }.collectIndexed { index, value ->
            if (index > 0) {
                write(",")
            }
            json.encodeToStream(serializer, value, this)
        }
    }

    private fun <T> InputStream.readJsonArray(
        serializer: DeserializationStrategy<T>,
    ): Sequence<T> = json.decodeToSequence(this, serializer, DecodeSequenceMode.ARRAY_WRAPPED)

    private fun InputStream.readMap(): Map<String, Any?> {
        val jo = JSONArray(readString()).getJSONObject(0)
        val map = ArrayMap<String, Any?>(jo.length())
        val keys = jo.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jo.get(key)
        }
        return map
    }

    private fun ZipOutputStream.writeString(
        section: BackupSection,
        data: String,
    ) {
        putNextEntry(ZipEntry(section.entryName))
        try {
            write("[")
            write(data)
            write("]")
        } finally {
            closeEntry()
            flush()
        }
    }

    private fun OutputStream.write(str: String) = write(str.toByteArray())

    private fun InputStream.readString(): String = readBytes().decodeToString()

    private fun dumpSettings(): String {
        val map = settings.getAllValues().toMutableMap()
        map.keys.removeAll(SensitiveBackupKeys::isSensitive)
        return JSONObject(map).toString()
    }

    private fun dumpReaderGridSettings(): String {
        return JSONObject(tapGridSettings.getAllValues()).toString()
    }

    private fun categorySyncUid(id: Int): String? = database.openHelper.readableDatabase.query(
        "SELECT uid FROM ${DriveReplicaJournal.CATEGORIES} WHERE category_id=?", arrayOf(id),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun restoreCategorySyncUid(id: Int, uid: String?) {
        if (uid != null && uid.isNotEmpty()) database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ${DriveReplicaJournal.CATEGORIES}(category_id,uid) VALUES(?,?)", arrayOf(id, uid),
        )
    }

    private fun backupCustomCover(backup: MangaPreferencesBackup): MangaPreferencesBackup {
        val uri = backup.coverOverride?.let(Uri::parse) ?: return backup
        if (uri.scheme != "file" && uri.scheme != "content") return backup
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(MAX_CUSTOM_COVER_BYTES) }
            ?: return backup.copy(coverOverride = null)
        check(bytes.size in 1..MAX_CUSTOM_COVER_BYTES) { "Custom cover exceeds Google Drive size limit" }
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }
        return backup.copy(coverOverride = null, coverData = Base64.getEncoder().encodeToString(bytes),
            coverExtension = extension)
    }

    private fun restoreCustomCover(backup: MangaPreferencesBackup): String? {
        val data = backup.coverData ?: return backup.coverOverride
        if (data.length > MAX_CUSTOM_COVER_BYTES * 4 / 3 + 4) return backup.coverOverride
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return backup.coverOverride
        if (bytes.size !in 1..MAX_CUSTOM_COVER_BYTES) return backup.coverOverride
        val dir = context.getExternalFilesDir("covers") ?: return backup.coverOverride
        val suffix = backup.coverExtension?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }
            ?.let { ".$it" }.orEmpty()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val file = File(dir, "sync-${backup.manga.id}-$hash$suffix")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private suspend fun MangaDatabase.upsertManga(manga: MangaBackup) {
        val tags = manga.tags.map { it.toEntity() }
        getTagsDao().upsert(tags)
        getMangaDao().upsert(manga.toEntity(), tags)
    }

    private suspend fun MangaDatabase.restoreCategory(
        backup: CategoryBackup,
        isMerge: Boolean,
        categoryIdRemap: MutableMap<Long, Long>,
    ) {
        val dao = getFavouriteCategoriesDao()
        if (!isMerge) {
            dao.upsert(backup.toEntity())
            restoreCategorySyncUid(backup.categoryId, backup.syncUid)
            categoryIdRemap[backup.categoryId.toLong()] = backup.categoryId.toLong()
            return
        }
        val sameTitle = dao.findByTitleForRestore(backup.title)
        val sameId = dao.findForRestore(backup.categoryId)
        val existing = sameTitle ?: sameId?.takeIf { it.title == backup.title }
        val targetId = when {
            existing != null -> existing.categoryId
            sameId == null -> backup.categoryId
            else -> dao.insert(backup.toEntity(categoryId = 0)).toInt()
        }
        categoryIdRemap[backup.categoryId.toLong()] = targetId.toLong()
        restoreCategorySyncUid(targetId, backup.syncUid)
        if (existing == null && sameId != null) return // inserted above after an id collision
        val incoming = backup.toEntity(targetId)
        if (existing == null || incoming.eventTimestamp > existing.eventTimestamp) {
            dao.upsert(incoming)
        }
    }

    private val org.koitharu.kotatsu.history.data.HistoryEntity.eventTimestamp: Long
        get() = maxOf(updatedAt, deletedAt)

    private val org.koitharu.kotatsu.favourites.data.FavouriteEntity.eventTimestamp: Long
        get() = maxOf(createdAt, deletedAt)

    private val org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity.eventTimestamp: Long
        get() = maxOf(createdAt, deletedAt)

    private suspend inline fun <T> Sequence<T>.restoreToDb(crossinline block: suspend MangaDatabase.(T) -> Unit): CompositeResult {
        return fold(CompositeResult.EMPTY) { result, item ->
            result + runCatchingCancellable {
                database.withTransaction {
                    database.block(item)
                }
            }
        }
    }

    private suspend inline fun <T> Sequence<T>.restoreWithoutTransaction(crossinline block: suspend (T) -> Unit): CompositeResult {
        return fold(CompositeResult.EMPTY) { result, item ->
            result + runCatchingCancellable {
                block(item)
            }
        }
    }

    private fun BackupSection.canContainMangaIdentityDuplicates() = when (this) {
        BackupSection.HISTORY,
        BackupSection.FAVOURITES,
        BackupSection.FAVORITE_ORDER,
        BackupSection.BOOKMARKS,
        BackupSection.STATS,
        BackupSection.MANGA_PREFERENCES,
        BackupSection.TRACKS,
        BackupSection.EXACT_COMPLETION,
        BackupSection.FOREIGN_IMPORT_STATE,
        BackupSection.TRACKER_LINKS,
        BackupSection.METADATA_OVERRIDES,
        -> true

        BackupSection.INDEX,
        BackupSection.CATEGORIES,
        BackupSection.SETTINGS,
        BackupSection.SETTINGS_READER_GRID,
        BackupSection.SOURCES,
        BackupSection.SCROBBLING,
        BackupSection.SAVED_FILTERS,
        BackupSection.REMOTE_LIBRARIES,
        BackupSection.TRACKER_CATEGORIES,
        -> false
    }

    private companion object {
        const val MAX_CUSTOM_COVER_BYTES = 10 * 1024 * 1024
    }
}
