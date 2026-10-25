package org.koitharu.kotatsu.backups.mihon

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.history.exact.ChapterReadRepository
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class MihonReviewedImportTest {
    @Test fun actualImportPreservesUnreadGapsAndLosslessUnverifiedTrackerReferences() = runBlocking {
        fixture { context, db, file ->
            write(file, backup())
            val importer = importer(context, db)
            val preview = importer.prepare(file.toUri())
            assertEquals(listOf(true, false, true), preview.titles.single().chapters.map { it.read })
            val report = importer.apply(preview, preview.defaultChoices())
            val id = preview.titles.single().mangaId
            assertEquals(1, report.restoredManga)
            assertEquals(1, report.exactTitles)
            val manga = db.getMangaDao().find(id)!!.toManga(db.getChaptersDao().findAll(id))
            val snapshot = ChapterReadRepository(db).snapshot(id)
            assertEquals(setOf(manga.chapters!![0].id, manga.chapters!![2].id), snapshot.readIds(manga.chapters!!))
            assertEquals(2, snapshot.counts.single().readCount)
            assertEquals(3, snapshot.counts.single().totalCount)
            assertEquals(manga.chapters!![1].id, db.getHistoryDao().find(id)!!.chapterId)
            assertEquals(7, db.getHistoryDao().find(id)!!.page)
            val pending = db.getMihonImportDao().pending().single()
            assertEquals(Long.MAX_VALUE.toString(), pending.mediaId)
            assertEquals((Long.MAX_VALUE - 1).toString(), pending.libraryId)
            assertEquals("12.5", pending.rawProgress)
            assertEquals(0, count(db, "scrobblings"))
            assertEquals(0, count(db, "tracker_links_v2"))
            assertEquals(0, count(db, "stats"))
        }
    }

    @Test fun existingResumeMetadataFavoriteOrderAndExactFlagsStayUnchangedByDefault() = runBlocking {
        fixture { context, db, file ->
            write(file, backup())
            val importer = importer(context, db)
            val first = importer.prepare(file.toUri())
            importer.apply(first, first.defaultChoices())
            val id = first.titles.single().mangaId
            val history = db.getHistoryDao().find(id)!!.copy(page = 19, scroll = 0.34f, updatedAt = 900)
            db.getHistoryDao().upsert(history)
            val local = db.getMangaDao().find(id)!!.manga.copy(title = "Source metadata already saved")
            db.getMangaDao().upsert(local)
            val favourite = db.getFavouritesDao().findAllRaw(id).single().copy(sortKey = 77, isPinned = true, createdAt = 123)
            db.getFavouritesDao().upsert(favourite)
            val reads = ChapterReadRepository(db)
            val manga = db.getMangaDao().find(id)!!.toManga(db.getChaptersDao().findAll(id))
            reads.mark(manga, setOf(manga.chapters!!.last().id), false)
            val previousExact = reads.snapshot(id)
            write(file, backup().let { it.copy(manga = it.manga.map { title -> title.copy(title = "Foreign replacement", dateAdded = 999) }) })
            val preview = importer.prepare(file.toUri())
            assertTrue(preview.titles.single().hasExistingExactState)
            assertTrue(preview.defaultChoices().exactTitles.isEmpty())
            assertTrue(preview.defaultChoices().replaceResumeTitles.isEmpty())
            importer.apply(preview, preview.defaultChoices())
            assertEquals(history, db.getHistoryDao().find(id))
            assertEquals(local, db.getMangaDao().find(id)!!.manga)
            assertEquals(favourite, db.getFavouritesDao().findAllRaw(id).single())
            assertEquals(previousExact, reads.snapshot(id))
        }
    }

    @Test fun explicitReplacementPreservesPositionAndRepeatAfterResetCannotReviveFlags() = runBlocking {
        fixture { context, db, file ->
            write(file, backup())
            val importer = importer(context, db)
            val first = importer.prepare(file.toUri())
            importer.apply(first, first.defaultChoices())
            val id = first.titles.single().mangaId
            val history = db.getHistoryDao().find(id)
            val changed = backup().let { b -> b.copy(manga = b.manga.map { m ->
                m.copy(chapters = m.chapters.map { it.copy(read = it.url == "/chapter/2") }) }) }
            write(file, changed)
            val preview = importer.prepare(file.toUri())
            importer.apply(preview, preview.defaultChoices().copy(exactTitles = setOf(id)))
            val reads = ChapterReadRepository(db)
            val manga = db.getMangaDao().find(id)!!.toManga(db.getChaptersDao().findAll(id))
            assertEquals(setOf(manga.chapters!![1].id), reads.snapshot(id).readIds(manga.chapters!!))
            assertEquals(history, db.getHistoryDao().find(id))
            reads.reset(listOf(id))
            val reset = reads.snapshot(id)
            val repeated = importer.prepare(file.toUri())
            assertTrue(repeated.titles.single().alreadyImported)
            assertTrue(repeated.defaultChoices().selectedTitles.isEmpty())
            val report = importer.apply(repeated, MihonImportChoices(setOf(id), setOf(id)))
            assertEquals(0, report.restoredManga)
            assertEquals(reset, reads.snapshot(id))
            assertEquals(history, db.getHistoryDao().find(id))
        }
    }

    @Test fun changedFileAndChangedDatabaseInvalidatePreviewsWithoutPartialWrites() = runBlocking {
        fixture { context, db, file ->
            val importer = importer(context, db)
            write(file, backup())
            val first = importer.prepare(file.toUri())
            write(file, backup().let { b -> b.copy(manga = b.manga.map { it.copy(title = "Changed file") }) })
            assertStale { importer.apply(first, first.defaultChoices()) }
            assertEquals(0, count(db, "manga"))
            val accepted = importer.prepare(file.toUri())
            importer.apply(accepted, accepted.defaultChoices())
            write(file, backup())
            val beforeEdit = importer.prepare(file.toUri())
            val id = beforeEdit.titles.single().mangaId
            val history = db.getHistoryDao().find(id)!!.copy(page = 25, updatedAt = 999)
            db.getHistoryDao().upsert(history)
            val receipts = db.getMihonImportDao().receipts()
            assertStale { importer.apply(beforeEdit, beforeEdit.defaultChoices()) }
            assertEquals(history, db.getHistoryDao().find(id))
            assertEquals(receipts, db.getMihonImportDao().receipts())
        }
    }

    @Test fun sourceChangesAndCancellationAtCommitBoundaryRollBackTheWholeImport() = runBlocking {
        fixture { context, db, file ->
            write(file, backup())
            var calls = 0
            val importer = MihonReviewedImport(context, db, {
                calls++
                listOf(source(if (calls >= 3) "changed" else "one"))
            }, { 500L })
            val preview = importer.prepare(file.toUri())
            assertStale { importer.apply(preview, preview.defaultChoices()) }
            for (table in listOf("manga", "favourite_categories", "chapter_read_policy", "tracker_import_pending",
                "foreign_import_receipt", "history")) assertEquals(table, 0, count(db, table))
            calls = 0
            val cancelled = MihonReviewedImport(context, db, {
                if (++calls >= 3) throw CancellationException("fixture cancelled before commit")
                listOf(source())
            }, { 500L })
            val second = cancelled.prepare(file.toUri())
            val error = runCatching { cancelled.apply(second, second.defaultChoices()) }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(0, count(db, "manga"))
            assertEquals(0, count(db, "foreign_import_receipt"))
        }
    }

    @Test fun ambiguousPackagesRequireChoiceAndUnavailableSourcesRetainTheirIdentity() = runBlocking {
        fixture { context, db, file ->
            write(file, backup())
            val alternatives = listOf(source(), MihonMangaSource("fixture.other", 7, "Other", "en"))
            val importer = MihonReviewedImport(context, db, { alternatives }, { 500L })
            val ambiguous = importer.prepare(file.toUri())
            assertEquals(2, ambiguous.ambiguousSources.single().options.size)
            assertTrue(runCatching { importer.apply(ambiguous, ambiguous.defaultChoices()) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, count(db, "manga"))
            val chosen = importer.prepare(file.toUri(), mapOf(7L to alternatives[1].name))
            importer.apply(chosen, chosen.defaultChoices())
            assertEquals(alternatives[1].name, db.getMangaDao().find(chosen.titles.single().mangaId)!!.manga.source)
            write(file, backup().let { b -> b.copy(manga = b.manga.map { it.copy(source = 9, url = "/unavailable") }) })
            val missingImporter = MihonReviewedImport(context, db, { emptyList() }, { 500L })
            val missing = missingImporter.prepare(file.toUri())
            missingImporter.apply(missing, missing.defaultChoices())
            val stored = db.getMangaDao().find(missing.titles.single().mangaId)!!
            assertEquals("mihon:9", stored.manga.source)
            assertEquals("mihon:9", stored.toManga().source.name)
            assertTrue(stored.manga.isNsfw) // Unknown metadata cannot establish that the title is safe.
        }
    }

    private suspend fun fixture(block: suspend (Context, MangaDatabase, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "mihon-reviewed-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try { block(context, db, File(folder, "fixture.tachibk")) }
        finally { db.close(); folder.deleteRecursively() }
    }

    private fun importer(context: Context, db: MangaDatabase) = MihonReviewedImport(context, db, { listOf(source()) }, { 500L })
    private fun source(version: String = "one") = MihonMangaSource("fixture.backup", 7, "Fixture", "en", recommendationVersion = version)
    private fun backup() = MihonBackup(manga = listOf(MihonBackupManga(source = 7, url = "/book", title = "Fixture",
        dateAdded = 100, chapters = (1..3).map { index -> MihonBackupChapter(url = "/chapter/$index",
            name = "Chapter $index", read = index != 2, lastPageRead = if (index == 2) 7 else 0,
            chapterNumber = index.toFloat(), sourceOrder = (4 - index).toLong()) },
        history = listOf(MihonBackupHistory("/chapter/2", 200)), tracking = listOf(MihonBackupTracking(
            syncId = 2, mediaId = Long.MAX_VALUE, libraryId = Long.MAX_VALUE - 1, lastChapterRead = 12.5f)))))

    private fun write(file: File, backup: MihonBackup) = GZIPOutputStream(file.outputStream()).use {
        it.write(ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup))
    }

    private suspend fun assertStale(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected stale preview, got $error", error is StaleMihonImportException)
    }

    private suspend fun count(db: MangaDatabase, table: String): Int = db.withTransaction {
        db.openHelper.writableDatabase.query("SELECT count(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
    }
}
