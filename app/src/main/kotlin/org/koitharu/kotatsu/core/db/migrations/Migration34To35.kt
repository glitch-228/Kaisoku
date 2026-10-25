package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Opaque tracker identities and local synopsis overrides are additive; legacy records stay intact. */
class Migration34To35 : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SQL.forEach(db::execSQL)
    }

    companion object {
        val SQL = listOf(
            "ALTER TABLE preferences ADD COLUMN description_override TEXT",
            """CREATE TABLE IF NOT EXISTS tracker_links_v2 (provider_key TEXT NOT NULL, account_id TEXT NOT NULL, manga_id INTEGER NOT NULL, media_id TEXT NOT NULL, list_entry_id TEXT, progress_number TEXT, progress_volume TEXT, progress_entry_id TEXT, progress_unit TEXT, remote_status TEXT, remote_updated_at TEXT, revision INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider_key,account_id,manga_id))""",
            "CREATE INDEX IF NOT EXISTS index_tracker_links_v2_provider_key_account_id_media_id ON tracker_links_v2(provider_key,account_id,media_id)",
        )
    }
}
