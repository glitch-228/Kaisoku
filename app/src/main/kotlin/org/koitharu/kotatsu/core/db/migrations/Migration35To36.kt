package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Imported tracker evidence and immutable acceptance receipts never replace active library state. */
class Migration35To36 : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) = SQL.forEach(db::execSQL)

    companion object {
        val SQL = listOf(
            """CREATE TABLE IF NOT EXISTS tracker_import_pending (import_id TEXT NOT NULL, import_digest TEXT NOT NULL, manga_id INTEGER NOT NULL, source TEXT NOT NULL, edition_url TEXT NOT NULL, provider_key TEXT NOT NULL, foreign_provider_id INTEGER NOT NULL, media_id TEXT NOT NULL, library_id TEXT NOT NULL, raw_progress TEXT NOT NULL, raw_status TEXT NOT NULL, raw_score TEXT NOT NULL, accepted_at INTEGER NOT NULL, raw_payload TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(import_id))""",
            """CREATE TABLE IF NOT EXISTS foreign_import_receipt (import_digest TEXT NOT NULL, manga_id INTEGER NOT NULL, source TEXT NOT NULL, edition_url TEXT NOT NULL, accepted_at INTEGER NOT NULL, PRIMARY KEY(import_digest,manga_id))""",
            "CREATE INDEX IF NOT EXISTS index_tracker_import_pending_manga_id ON tracker_import_pending(manga_id)",
            "CREATE INDEX IF NOT EXISTS index_foreign_import_receipt_manga_id ON foreign_import_receipt(manga_id)",
        )
    }
}
