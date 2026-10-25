package org.koitharu.kotatsu.scrobbling.v2

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.backups.mihon.mihonImportFingerprint
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.exact.ChapterReadRepository

@RunWith(AndroidJUnit4::class)
class IncomingProgressTest {
    private val connection = TrackerConnection(TrackerOwner("COMICK", "fixture-account"), 1)
    private val reference = TrackerReference(connection.owner, "opaque-media", "opaque-list")
    private val chapters = listOf(
        ChapterEntity(101, 481, "One", 1f, 0, "/1", null, 0, null, "REMANGA", 0),
        ChapterEntity(102, 481, "Two", 2f, 0, "/2", null, 0, null, "REMANGA", 1),
        ChapterEntity(103, 481, "Three", 3f, 0, "/3", null, 0, null, "REMANGA", 2),
        ChapterEntity(104, 481, "Other edition", 3f, 0, "/other/3", null, 0, "other", "REMANGA", 3),
    )
    private val history = HistoryEntity(481, 10, 20, 102, 7, .3f, .5f, 0, 3)

    @Test fun prepareIsReadOnlyAndSelectedGapsPreserveResumeAndAllUnrelatedTables() = runBlocking {
        fixture { db, reads, access, importer ->
            val manga = db.getMangaDao().find(481)!!.toManga(chapters)
            reads.convert(manga, setOf(101))
            reads.mark(manga, setOf(102), false)
            val before = fingerprint(db)
            val preview = importer.prepare(connection, 481)
            assertEquals(before, fingerprint(db))
            assertEquals("3.0", preview.entry.progress!!.number)
            assertEquals(1, access.fetches)
            importer.apply(preview.id, IncomingProgressSelection("N", setOf(103)))
            assertEquals(setOf(101L, 103L), reads.snapshot(481).readIds(manga.chapters!!))
            assertEquals(history, db.getHistoryDao().findForRestore(481))
            assertEquals(0, count(db, "stats"))
            assertEquals(0, count(db, "favourites"))
            assertEquals(0, count(db, "scrobblings"))
            assertEquals(0, count(db, "tracker_links_v2"))
            assertEquals(1, access.fetches) // Apply does not fetch or write a provider.
            val applied = fingerprint(db)
            assertTrue(runCatching { importer.apply(preview.id, IncomingProgressSelection("N", setOf(101))) }.isFailure)
            assertEquals(applied, fingerprint(db))
        }
    }

    @Test fun conversionIsExplicitAndNeverInfersEarlierCompletion() = runBlocking {
        fixture { db, reads, _, importer ->
            val preview = importer.prepare(connection, 481)
            val before = fingerprint(db)
            assertTrue(runCatching { importer.apply(preview.id, IncomingProgressSelection("N", setOf(103))) }.isFailure)
            assertEquals(before, fingerprint(db))
            importer.apply(preview.id, IncomingProgressSelection("N", setOf(103), enableExactCompletion = true))
            assertEquals(setOf(103L), reads.snapshot(481).readIds(preview.manga.chapters!!))
            assertEquals(history, db.getHistoryDao().findForRestore(481))
        }
    }

    @Test fun accountLinkCursorChapterEditionAndCompletionChangesInvalidatePreviews() = runBlocking {
        for (change in 0..5) fixture { db, reads, access, importer ->
            val preview = importer.prepare(connection, 481)
            when (change) {
                0 -> access.current = connection.copy(revision = 2)
                1 -> access.link = access.link.copy(revision = "relinked")
                2 -> db.getHistoryDao().restore(history.copy(page = 12))
                3 -> db.getChaptersDao().replaceAll(481, chapters.map { it.copy(url = it.url + "?changed") })
                4 -> db.getMangaDao().upsert(db.getMangaDao().find(481)!!.manga.copy(source = "MANGADEX"))
                5 -> reads.convert(preview.manga, setOf(101))
            }
            val before = fingerprint(db)
            assertTrue("change=$change", runCatching { importer.apply(preview.id,
                IncomingProgressSelection("N", setOf(103), true)) }.isFailure)
            assertEquals(before, fingerprint(db))
        }
    }

    @Test fun cancellationAfterWritesRollsBackExactStateAndOptionalCursor() = runBlocking {
        fixture { db, _, access, importer ->
            val preview = importer.prepare(connection, 481)
            val before = fingerprint(db)
            var calls = 0
            access.onCheck = { if (++calls == 3) throw CancellationException("fixture cancellation before commit") }
            assertTrue(runCatching { importer.apply(preview.id,
                IncomingProgressSelection("N", setOf(103), true, resumeChapterId = 103)) }.exceptionOrNull() is CancellationException)
            assertEquals(before, fingerprint(db))
        }
    }

    @Test fun explicitResumeMovePreservesSessionDatesAndDoesNotMarkAPrefix() = runBlocking {
        fixture { db, reads, _, importer ->
            val preview = importer.prepare(connection, 481)
            importer.apply(preview.id, IncomingProgressSelection("N", enableExactCompletion = true, resumeChapterId = 103))
            assertEquals(history.copy(chapterId = 103, page = 0, scroll = 0f, percent = 0f), db.getHistoryDao().findForRestore(481))
            assertEquals(emptySet<Long>(), reads.snapshot(481).readIds(preview.manga.chapters!!))
            assertEquals(0, count(db, "stats"))
        }
    }

    @Test fun wrongBranchExpiredPreviewAndAccountChangeDuringReadMakeNoChanges() = runBlocking {
        fixture { db, _, access, importer ->
            val preview = importer.prepare(connection, 481)
            val before = fingerprint(db)
            assertTrue(runCatching { importer.apply(preview.id, IncomingProgressSelection("N", setOf(104), true)) }.isFailure)
            assertEquals(before, fingerprint(db))
            access.onFetch = { access.current = connection.copy(owner = TrackerOwner("COMICK", "another-account")) }
            assertTrue(runCatching { importer.prepare(connection, 481) }.isFailure)
            assertEquals(before, fingerprint(db))
        }
        fixture { db, reads, access, _ ->
            var now = 100L
            val importer = IncomingProgressRepository(db, reads, access) { now }
            val preview = importer.prepare(connection, 481)
            now += 15 * 60_000L
            val before = fingerprint(db)
            assertTrue(runCatching { importer.apply(preview.id, IncomingProgressSelection("N", setOf(103), true)) }.isFailure)
            assertEquals(before, fingerprint(db))
        }
    }

    private inner class Access : IncomingProgressAccess {
        var current = connection
        var link = IncomingProgressLink(reference, "fixture-link-revision")
        var fetches = 0
        var onCheck: () -> Unit = {}
        var onFetch: () -> Unit = {}
        override fun checkConnection(connection: TrackerConnection) { onCheck(); check(current == connection) }
        override suspend fun incomingLink(connection: TrackerConnection, mangaId: Long) = link
        override suspend fun incomingEntry(connection: TrackerConnection, mediaId: String): TrackerRemoteEntry {
            fetches++; onFetch()
            return TrackerRemoteEntry(reference, TrackerTitle(mediaId, "Remote", url = "https://fixture.invalid/title"),
                "CURRENT", TrackerRawProgress("3.0"))
        }
    }

    private suspend fun fixture(block: suspend (MangaDatabase, ChapterReadRepository, Access, IncomingProgressRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try {
            db.getMangaDao().upsert(MangaBackup(481, "Fixture", url = "/fixture", publicUrl = "https://fixture.invalid/title",
                coverUrl = "", source = "REMANGA").toEntity())
            db.getChaptersDao().replaceAll(481, chapters)
            db.getHistoryDao().restore(history)
            val reads = ChapterReadRepository(db)
            val access = Access()
            block(db, reads, access, IncomingProgressRepository(db, reads, access) { 100L })
        } finally { db.close() }
    }

    private suspend fun fingerprint(db: MangaDatabase) = db.withTransaction {
        db.mihonImportFingerprint(setOf(481))
    }
    private fun count(db: MangaDatabase, table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
}
