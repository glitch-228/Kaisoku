package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve existing local links; restored links get a separate, device-local confirmation step. */
class Migration36To37 : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SQL.forEach(db::execSQL)
        // Existing explicit links were confirmed on this device. Stamp them during migration, not a read preview.
        db.execSQL("""UPDATE tracker_links_v2 SET
            owner_source = COALESCE((SELECT source FROM manga WHERE manga.manga_id=tracker_links_v2.manga_id), ''),
            edition_url = COALESCE((SELECT url FROM manga WHERE manga.manga_id=tracker_links_v2.manga_id), ''),
            revision_logical = MAX(0, revision), revision_actor = ?""".trimIndent(),
            arrayOf(java.util.UUID.randomUUID().toString()))
    }

    companion object {
        val SQL = listOf(
            "ALTER TABLE tracker_links_v2 ADD COLUMN owner_source TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tracker_links_v2 ADD COLUMN edition_url TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tracker_links_v2 ADD COLUMN revision_wall INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE tracker_links_v2 ADD COLUMN revision_logical INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE tracker_links_v2 ADD COLUMN revision_actor TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tracker_links_v2 ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 1",
        )
    }
}
