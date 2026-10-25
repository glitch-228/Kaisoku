package org.koitharu.kotatsu.history.exact

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.history.data.HistoryEntity

@RunWith(AndroidJUnit4::class)
class ChapterReadRepositoryTest {
    @Test fun downloadedSubsetsKeepRemoteIdentityTotalAndUnreadGaps() = runBlocking {
        fixture { db ->
            val remote = seed(db)
            val history = HistoryEntity(481, 10, 20, 104, 7, 123f, 1f, 0, 4)
            db.getHistoryDao().upsert(history)
            val exact = ChapterReadRepository(db)
            exact.convert(remote)
            val before = exact.snapshot(481).counts.single()
            val local = remote.copy(source = LocalMangaSource, url = "file:///fixture.cbz",
                chapters = remote.chapters!!.takeLast(2).map {
                    it.copy(source = LocalMangaSource, url = "file+zip:///fixture.cbz#${it.id}.jpg")
                })
            exact.confirmed(local, 103)
            exact.confirmed(local, 104)
            val saved = exact.snapshot(481)
            assertEquals(setOf(103L, 104L), saved.readIds(remote.chapters!!))
            assertEquals(setOf(103L, 104L), saved.readIds(local.chapters!!))
            assertEquals(4, saved.counts.single().totalCount)
            assertEquals(2, saved.counts.single().readCount)
            assertEquals(before.listRevision, saved.counts.single().listRevision)
            assertTrue(saved.records.all { it.source == "REMANGA" && !it.originalUrl.startsWith("file") })
            val info = HistoryInfo(MangaDetails(local), null, null, false, null, saved)
            assertEquals(4, info.totalChapters)
            assertEquals(.5f, info.percent)
            exact.mark(local, setOf(103), false)
            exact.refreshCounts(remote)
            assertEquals(setOf(104L), exact.snapshot(481).readIds(remote.chapters!!))
            assertEquals(history, db.getHistoryDao().find(481))
            assertEquals(1, exact.activeCounts(listOf(481))[481]?.readCount)
        }
    }

    @Test fun offlineConversionWithoutFullRemoteEvidenceKeepsItsTotalUnknown() = runBlocking {
        fixture { db ->
            val remote = seed(db)
            val local = remote.copy(source = LocalMangaSource, url = "file:///fixture.cbz",
                chapters = remote.chapters!!.takeLast(1).map {
                    it.copy(source = LocalMangaSource, url = "file+zip:///fixture.cbz#104.jpg")
                })
            val exact = ChapterReadRepository(db)
            exact.convert(local)
            exact.confirmed(local, 104)
            val saved = exact.snapshot(481)
            assertEquals("REMANGA", saved.policy?.source)
            assertEquals("/fixture", saved.policy?.editionUrl)
            assertTrue(saved.counts.isEmpty())
            assertEquals(setOf(104L), saved.readIds(remote.chapters!!))
            assertNull(exact.activeCounts(listOf(481))[481])
            assertEquals(0f, HistoryInfo(MangaDetails(local), null, null, false, null, saved).percent)
            exact.refreshCounts(remote)
            assertEquals(4, exact.activeCounts(listOf(481))[481]?.totalCount)
            assertEquals(1, exact.activeCounts(listOf(481))[481]?.readCount)
        }
    }

    private suspend fun seed(db: MangaDatabase): org.koitharu.kotatsu.parsers.model.Manga {
        db.getMangaDao().upsert(MangaBackup(481, "Fixture", url = "/fixture",
            publicUrl = "https://fixture.invalid/fixture", coverUrl = "", source = "REMANGA").toEntity())
        val chapters = (1..4).map {
            ChapterEntity(100L + it, 481, "Chapter $it", it.toFloat(), 0, "/chapter/$it", null, 0, null, "REMANGA", it - 1)
        }
        db.getChaptersDao().replaceAll(481, chapters)
        return checkNotNull(db.getMangaDao().find(481)).toManga(chapters)
    }

    private suspend fun fixture(block: suspend (MangaDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
}
