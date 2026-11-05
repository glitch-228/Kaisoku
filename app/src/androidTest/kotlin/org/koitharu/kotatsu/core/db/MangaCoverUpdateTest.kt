package org.koitharu.kotatsu.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@RunWith(AndroidJUnit4::class)
class MangaCoverUpdateTest {

    private fun entity(id: Long, cover: String, large: String? = null) = MangaEntity(
        id = id, title = "Title $id", altTitles = null, url = "/m/$id", publicUrl = "https://x.test/m/$id",
        rating = -1f, isNsfw = false, contentRating = null, coverUrl = cover, largeCoverUrl = large,
        state = null, authors = null, source = "TEST",
    )

    @Test
    fun coverMovesWithTheSiteAndNothingElseChanges() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MangaDatabase::class.java,
        ).build()
        try {
            val dao = db.getMangaDao()
            dao.upsert(entity(1, "https://old.test/1.jpg", "https://old.test/1-large.jpg"))
            dao.upsert(entity(2, "https://old.test/2.jpg", "https://old.test/2-large.jpg"))

            // Moved cover without a large variant: the old large one would point at the dead address
            assertEquals(1, dao.updateCover(1, "https://new.test/1.jpg", null))
            assertEquals("https://new.test/1.jpg", dao.find(1)!!.manga.coverUrl)
            assertNull(dao.find(1)!!.manga.largeCoverUrl)

            // Same cover, no new large variant: nothing is touched
            assertEquals(0, dao.updateCover(2, "https://old.test/2.jpg", null))
            assertEquals("https://old.test/2-large.jpg", dao.find(2)!!.manga.largeCoverUrl)

            // Same cover, new large variant
            assertEquals(1, dao.updateCover(2, "https://old.test/2.jpg", "https://new.test/2-large.jpg"))
            assertEquals("https://new.test/2-large.jpg", dao.find(2)!!.manga.largeCoverUrl)

            // Unknown manga is not created
            assertEquals(0, dao.updateCover(99, "https://new.test/99.jpg", null))
            assertNull(dao.find(99))
            assertEquals("Title 2", dao.find(2)!!.manga.title)
        } finally {
            db.close()
        }
    }
}
